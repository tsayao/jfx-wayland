/*
 * Copyright (c) 2026, Thiago Sayão. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holder designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.sun.prism.gl;

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.prism.Graphics;
import com.sun.prism.GraphicsResource;
import com.sun.prism.Presentable;
import com.sun.prism.PresentableState;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.QueuedPixelSource;
import com.sun.prism.vk.DmaBufPresenter;

import java.nio.IntBuffer;

/**
 * Presents the frames rendered into a stable back buffer.
 * <p>
 * When the window is composited (GTK 4), the back buffer is copied into one of
 * a few exported linear images, which the window shows as a dma-buf. Otherwise
 * the pixels are read back and uploaded to the window.
 */
class GLSwapChain implements Presentable, GLRenderTarget, GraphicsResource {

    private static final int SLOT_COUNT = 3;
    private static final long SLOT_WAIT_MILLIS = 100;

    private final GLContext context;
    private final PresentableState pState;
    private final float pixelScaleFactorX;
    private final float pixelScaleFactorY;
    private final boolean composited;
    // The window could not show a frame of a dma-buf: its pixels are presented instead
    private boolean dmabufFailed;
    // A frame was dropped as the compositor held every buffer (guarded by this)
    private boolean repaintOnRelease;
    private final QueuedPixelSource pixelSource = new QueuedPixelSource(false);

    private GLRTTexture stableBackbuffer;
    private int w;
    private int h;
    private boolean opaque;

    // Exported images and whether the compositor still reads them
    private final GLImage[] slots = new GLImage[SLOT_COUNT];
    private final boolean[] slotBusy = new boolean[SLOT_COUNT];
    private int slotWidth;
    private int slotHeight;
    private int nextSlot;
    private boolean slotsCreated;
    // Incremented when the slots are recreated, so late releases are ignored
    private int slotGeneration;

    GLSwapChain(GLContext context, PresentableState pState) {
        this.context = context;
        this.pState = pState;
        this.pixelScaleFactorX = pState.getRenderScaleX();
        this.pixelScaleFactorY = pState.getRenderScaleY();
        this.composited = pState.getWindow() instanceof DmaBufPresenter presenter
                && presenter.isComposited() && context.isExportSupported();
        this.w = pState.getRenderWidth();
        this.h = pState.getRenderHeight();
    }

    @Override
    public boolean lockResources(PresentableState state) {
        if (pState != state
                || pixelScaleFactorX != state.getRenderScaleX()
                || pixelScaleFactorY != state.getRenderScaleY()) {
            return true;
        }
        boolean needsResize = w != state.getRenderWidth() || h != state.getRenderHeight();
        if (stableBackbuffer != null && !needsResize) {
            stableBackbuffer.lock();
            if (stableBackbuffer.isSurfaceLost()) {
                stableBackbuffer = null;
                return true;
            }
        }
        return false;
    }

    @Override
    public Graphics createGraphics() {
        boolean needsResize = w != pState.getRenderWidth() || h != pState.getRenderHeight();
        if (stableBackbuffer == null || needsResize) {
            if (stableBackbuffer != null) {
                context.flushVertexBuffer();
                stableBackbuffer.dispose();
                stableBackbuffer = null;
            }
            w = pState.getRenderWidth();
            h = pState.getRenderHeight();
            stableBackbuffer = (GLRTTexture) context.getResourceFactory()
                    .createRTTexture(w, h, WrapMode.CLAMP_NOT_NEEDED);
            if (stableBackbuffer == null) {
                return null;
            }
            if (PrismSettings.dirtyOptsEnabled) {
                stableBackbuffer.contentsUseful();
            }
        }
        Graphics g = GLGraphics.create(context, stableBackbuffer);
        if (g != null) {
            g.scale(pixelScaleFactorX, pixelScaleFactorY);
        }
        return g;
    }

    @Override
    public boolean prepare(Rectangle dirtyRegion) {
        context.flushVertexBuffer();
        if (stableBackbuffer != null) {
            stableBackbuffer.unlock();
        }
        return true;
    }

    @Override
    public boolean present() {
        if (context.isDisposed() || stableBackbuffer == null) {
            return false;
        }
        return composited && !dmabufFailed ? presentDmaBuf() : presentPixels();
    }

    /**
     * Copies the frame into an exported image and publishes it. An opaque
     * frame is published as XRGB8888, which GTK can then offload below the
     * window when something of the window is drawn over the frame.
     */
    private boolean presentDmaBuf() {
        if (!(pState.getWindow() instanceof DmaBufPresenter presenter)) {
            return false;
        }
        ensureSlots(w, h);
        if (!hasSlots()) {
            // The exported images cannot be created: read the frames back instead
            return presentPixels();
        }
        int slot = acquireSlot();
        if (slot < 0) {
            // The compositor still holds all the buffers: the frame is dropped, and
            // painted again once a buffer comes back
            context.flush();
            synchronized (this) {
                repaintOnRelease = true;
            }
            return true;
        }
        GLImage exported = slots[slot];
        context.copyToExported(stableBackbuffer, exported, w, h);
        final int index = slot;
        final int generation = slotGeneration;
        boolean published = presenter.publishDmaBuf(exported.fd, exported.stride, 0,
                isOpaque() ? GLDevice.DRM_FORMAT_XRGB8888 : exported.fourcc, GLDevice.DRM_FORMAT_MOD_LINEAR, w, h,
                () -> releaseSlot(index, generation));
        if (published) {
            return true;
        }
        releaseSlot(index, generation);
        dmabufFailed = true;
        return presentPixels();
    }

    private boolean presentPixels() {
        Pixels pixels = pixelSource.getUnusedPixels(w, h, pixelScaleFactorX, pixelScaleFactorY);
        IntBuffer buffer = (IntBuffer) pixels.getPixels();
        if (!stableBackbuffer.readPixels(buffer, 0, 0, w, h)) {
            return false;
        }
        pixelSource.enqueuePixels(pixels);
        pState.uploadPixels(pixelSource);
        return true;
    }

    private void ensureSlots(int width, int height) {
        if (slotWidth == width && slotHeight == height && slotsCreated) {
            return;
        }
        releaseSlots();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = context.getDevice().createImage(GLImage.FORMAT_BGRA8, width, height, 1, false, true);
        }
        slotWidth = width;
        slotHeight = height;
        // Not retried for this size when the images cannot be created
        slotsCreated = true;
    }

    private boolean hasSlots() {
        for (GLImage slot : slots) {
            if (slot != null && slot.fd >= 0) {
                return true;
            }
        }
        return false;
    }

    private synchronized int acquireSlot() {
        long deadline = System.currentTimeMillis() + SLOT_WAIT_MILLIS;
        while (true) {
            for (int i = 0; i < SLOT_COUNT; i++) {
                int index = (nextSlot + i) % SLOT_COUNT;
                if (slots[index] != null && slots[index].fd >= 0 && !slotBusy[index]) {
                    slotBusy[index] = true;
                    nextSlot = (index + 1) % SLOT_COUNT;
                    return index;
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return -1;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    /** A buffer came back; the frame dropped meanwhile is asked for again. */
    private void releaseSlot(int index, int generation) {
        boolean repaint;
        synchronized (this) {
            if (generation != slotGeneration) {
                return;
            }
            slotBusy[index] = false;
            notifyAll();
            repaint = repaintOnRelease;
            repaintOnRelease = false;
        }
        if (repaint && pState.getWindow() instanceof DmaBufPresenter presenter) {
            presenter.requestRepaint();
        }
    }

    private void releaseSlots() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            if (slots[i] != null) {
                // The compositor holds its own references to the buffers it shows
                slots[i].dispose();
                slots[i] = null;
            }
        }
        slotsCreated = false;
        synchronized (this) {
            slotGeneration++;
            for (int i = 0; i < SLOT_COUNT; i++) {
                slotBusy[i] = false;
            }
        }
    }

    @Override
    public GLImage getTargetImage() {
        return stableBackbuffer != null ? stableBackbuffer.getImage() : null;
    }

    @Override
    public float getPixelScaleFactorX() {
        return pixelScaleFactorX;
    }

    @Override
    public float getPixelScaleFactorY() {
        return pixelScaleFactorY;
    }

    @Override
    public Screen getAssociatedScreen() {
        return context.getAssociatedScreen();
    }

    @Override
    public boolean isOpaque() {
        return stableBackbuffer != null ? stableBackbuffer.isOpaque() : opaque;
    }

    @Override
    public void setOpaque(boolean opaque) {
        if (stableBackbuffer != null) {
            stableBackbuffer.setOpaque(opaque);
        } else {
            this.opaque = opaque;
        }
    }

    @Override
    public boolean isMSAA() {
        return false;
    }

    @Override
    public int getPhysicalWidth() {
        return pState.getOutputWidth();
    }

    @Override
    public int getPhysicalHeight() {
        return pState.getOutputHeight();
    }

    @Override
    public int getContentX() {
        return 0;
    }

    @Override
    public int getContentY() {
        return 0;
    }

    @Override
    public int getContentWidth() {
        return pState.getOutputWidth();
    }

    @Override
    public int getContentHeight() {
        return pState.getOutputHeight();
    }

    @Override
    public void dispose() {
        if (stableBackbuffer != null) {
            stableBackbuffer.dispose();
            stableBackbuffer = null;
        }
        releaseSlots();
    }
}
