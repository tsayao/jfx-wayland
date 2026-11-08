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

package com.sun.prism.es2egl;

import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.prism.GraphicsResource;
import com.sun.prism.Presentable;
import com.sun.prism.PresentableState;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.QueuedPixelSource;
import com.sun.prism.vk.DmaBufPresenter;

import java.nio.IntBuffer;

/**
 * Presents the frames rendered into a stable back buffer, a render target
 * texture, as there is no window surface to draw to.
 * <p>
 * When the window is composited (GTK 4), the back buffer is copied into one of
 * a few dma-bufs, which the window shows. Otherwise the pixels are read back
 * and uploaded to the window.
 */
class ES2SwapChain implements Presentable, GraphicsResource {

    private static final int SLOT_COUNT = 3;
    private static final long SLOT_WAIT_MILLIS = 100;

    private final ES2Context context;
    private final PresentableState pState;
    private final float pixelScaleFactorX;
    private final float pixelScaleFactorY;
    private final boolean msaa;
    private final boolean composited;
    // The window could not show a frame of a dma-buf: its pixels are presented instead
    private boolean dmabufFailed;
    // The rendering cannot be waited for through a sync file: glFinish instead
    private boolean syncFileFailed;
    // A frame was dropped as the compositor held every buffer (guarded by this)
    private boolean repaintOnRelease;
    private final QueuedPixelSource pixelSource = new QueuedPixelSource(false);

    private ES2RTTexture stableBackbuffer;
    // The resolved frame of a multisampled back buffer, to read its pixels
    private ES2RTTexture resolvedBackbuffer;
    private int w;
    private int h;
    private boolean opaque;

    // The dma-bufs and whether the compositor still reads them
    private final ES2DmaBuf[] slots = new ES2DmaBuf[SLOT_COUNT];
    private final boolean[] slotBusy = new boolean[SLOT_COUNT];
    private int slotWidth;
    private int slotHeight;
    private int nextSlot;
    private boolean slotsCreated;
    // Incremented when the slots are recreated, so late releases are ignored
    private int slotGeneration;

    ES2SwapChain(ES2Context context, PresentableState pState) {
        this.context = context;
        this.pState = pState;
        this.pixelScaleFactorX = pState.getRenderScaleX();
        this.pixelScaleFactorY = pState.getRenderScaleY();
        this.msaa = pState.isMSAA();
        GLFactory factory = context.getGLContext().getFactory();
        this.composited = pState.getWindow() instanceof DmaBufPresenter presenter
                && presenter.isComposited() && factory.isExportSupported();
        this.syncFileFailed = !factory.isSyncFileSupported();
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
        if (stableBackbuffer != null && !needsResize()) {
            stableBackbuffer.lock();
            if (stableBackbuffer.isSurfaceLost()) {
                // The scene is painted again in its entirety into a new back buffer
                stableBackbuffer = null;
                return true;
            }
        }
        return false;
    }

    private boolean needsResize() {
        return w != pState.getRenderWidth() || h != pState.getRenderHeight();
    }

    @Override
    public ES2Graphics createGraphics() {
        context.makeCurrent();
        if (stableBackbuffer == null || needsResize()) {
            recreateBackbuffer();
            if (stableBackbuffer == null) {
                return null;
            }
        }
        ES2Graphics g = ES2Graphics.create(context, stableBackbuffer);
        g.scale(pixelScaleFactorX, pixelScaleFactorY);
        return g;
    }

    private void recreateBackbuffer() {
        if (stableBackbuffer != null) {
            context.flushVertexBuffer();
            stableBackbuffer.dispose();
            stableBackbuffer = null;
        }
        w = pState.getRenderWidth();
        h = pState.getRenderHeight();
        stableBackbuffer = (ES2RTTexture) context.getResourceFactory()
                .createRTTexture(w, h, WrapMode.CLAMP_NOT_NEEDED, msaa);
        if (stableBackbuffer != null) {
            stableBackbuffer.setOpaque(opaque);
            if (PrismSettings.dirtyOptsEnabled) {
                stableBackbuffer.contentsUseful();
            }
        }
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
        if (stableBackbuffer == null) {
            return false;
        }
        context.makeCurrent();
        // Locked while its frame is copied or read
        stableBackbuffer.lock();
        try {
            return composited && !dmabufFailed ? presentDmaBuf() : presentPixels();
        } finally {
            stableBackbuffer.unlock();
        }
    }

    /**
     * Copies the frame into a dma-buf and publishes it. An opaque frame is
     * published as XRGB8888, which GTK can then offload below the window when
     * something of the window is drawn over the frame.
     */
    private boolean presentDmaBuf() {
        if (!(pState.getWindow() instanceof DmaBufPresenter presenter)) {
            return false;
        }
        ensureSlots(w, h);
        if (!hasSlots()) {
            // The dma-bufs cannot be created: read the frames back instead
            return presentPixels();
        }
        int slot = acquireSlot();
        if (slot < 0) {
            dropFrame();
            return true;
        }
        copyToDmaBuf(slots[slot]);
        if (publish(presenter, slot)) {
            return true;
        }
        dmabufFailed = true;
        return presentPixels();
    }

    /**
     * The compositor still holds all the buffers: the frame is dropped, and
     * painted again once a buffer comes back.
     */
    private void dropFrame() {
        context.getGLContext().flush();
        synchronized (this) {
            repaintOnRelease = true;
        }
    }

    /**
     * Shows the dma-buf of a slot in the window, which releases the slot when
     * it no longer reads it.
     *
     * @return false, with the slot released, when the window cannot show it
     */
    private boolean publish(DmaBufPresenter presenter, int slot) {
        ES2DmaBuf buffer = slots[slot];
        int generation = slotGeneration;
        int fourcc = isOpaque() ? ES2DmaBuf.DRM_FORMAT_XRGB8888 : ES2DmaBuf.DRM_FORMAT_ARGB8888;
        boolean published = presenter.publishDmaBuf(buffer.fd(), buffer.stride(), 0, fourcc,
                ES2DmaBuf.DRM_FORMAT_MOD_LINEAR, w, h, () -> releaseSlot(slot, generation));
        if (!published) {
            releaseSlot(slot, generation);
        }
        return published;
    }

    /**
     * Copies the back buffer, whose first row is the top of the frame, into
     * a dma-buf, and makes the frame readable by the compositor: through a
     * sync file attached to the dma-buf, or by waiting for the GPU.
     */
    private void copyToDmaBuf(ES2DmaBuf buffer) {
        int cw = Math.min(w, buffer.width);
        int ch = Math.min(h, buffer.height);
        context.getGLContext().blitFBO(stableBackbuffer.getFboID(), buffer.fbo(), 0, 0, cw, ch, 0, 0, cw, ch);
        if (syncFileFailed || !buffer.attachRenderingFence()) {
            syncFileFailed = true;
            context.getGLContext().finish();
        }
    }

    private boolean presentPixels() {
        ES2RTTexture readable = readableBackbuffer();
        if (readable == null) {
            return false;
        }
        Pixels pixels = pixelSource.getUnusedPixels(w, h, pixelScaleFactorX, pixelScaleFactorY);
        IntBuffer buffer = (IntBuffer) pixels.getPixels();
        if (!readable.readPixels(buffer, 0, 0, w, h)) {
            return false;
        }
        pixelSource.enqueuePixels(pixels);
        pState.uploadPixels(pixelSource);
        return true;
    }

    /** The back buffer, or the resolved copy of its frame when it is multisampled. */
    private ES2RTTexture readableBackbuffer() {
        if (!stableBackbuffer.isMSAA()) {
            return stableBackbuffer;
        }
        if (resolvedBackbuffer == null || resolvedBackbuffer.getContentWidth() != w
                || resolvedBackbuffer.getContentHeight() != h) {
            disposeResolved();
            resolvedBackbuffer = (ES2RTTexture) context.getResourceFactory()
                    .createRTTexture(w, h, WrapMode.CLAMP_NOT_NEEDED, false);
            if (resolvedBackbuffer == null) {
                return null;
            }
        }
        context.getGLContext().blitFBO(stableBackbuffer.getFboID(), resolvedBackbuffer.getFboID(),
                0, 0, w, h, 0, 0, w, h);
        return resolvedBackbuffer;
    }

    private void disposeResolved() {
        if (resolvedBackbuffer != null) {
            resolvedBackbuffer.dispose();
            resolvedBackbuffer = null;
        }
    }

    private void ensureSlots(int width, int height) {
        if (slotWidth == width && slotHeight == height && slotsCreated) {
            return;
        }
        releaseSlots();
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = ES2DmaBuf.create(context.getGLContext(), width, height);
        }
        slotWidth = width;
        slotHeight = height;
        // Not retried for this size when the buffers cannot be created
        slotsCreated = true;
    }

    private boolean hasSlots() {
        for (ES2DmaBuf slot : slots) {
            if (slot != null) {
                return true;
            }
        }
        return false;
    }

    private synchronized int acquireSlot() {
        long deadline = System.currentTimeMillis() + SLOT_WAIT_MILLIS;
        while (true) {
            int free = freeSlot();
            if (free >= 0) {
                slotBusy[free] = true;
                nextSlot = (free + 1) % SLOT_COUNT;
                return free;
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

    /** The next buffer that the compositor does not read, or -1. Called with the lock held. */
    private int freeSlot() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            int index = (nextSlot + i) % SLOT_COUNT;
            if (slots[index] != null && !slotBusy[index]) {
                return index;
            }
        }
        return -1;
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
        this.opaque = opaque;
        if (stableBackbuffer != null) {
            stableBackbuffer.setOpaque(opaque);
        }
    }

    @Override
    public boolean isMSAA() {
        return stableBackbuffer != null ? stableBackbuffer.isMSAA() : msaa;
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
        context.makeCurrent();
        if (stableBackbuffer != null) {
            stableBackbuffer.dispose();
            stableBackbuffer = null;
        }
        disposeResolved();
        releaseSlots();
    }
}
