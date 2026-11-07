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
package com.sun.glass.ui.gtk4;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;
import com.sun.glass.ui.gtk4.wrappers.GdkDmabufTextureBuilder;
import com.sun.glass.ui.gtk4.wrappers.GdkTexture;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkGraphicsOffload;
import com.sun.glass.ui.gtk4.wrappers.GtkPicture;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class Gtk4Compositor {

    private static final Gtk4Log.Category COMPOSITOR = Gtk4Log.Category.COMPOSITOR;
    private static final MethodHandle POSIX_DUP;
    private static final MethodHandle POSIX_CLOSE;

    // Release callbacks of the external frames shown by all the windows, by
    // texture token; one destroy notify serves them all
    private static final Map<Long, Runnable> EXTERNAL_RELEASES = new ConcurrentHashMap<>();
    private static final AtomicLong EXTERNAL_TOKENS = new AtomicLong(1);
    private static final MemorySegment EXTERNAL_DESTROY_NOTIFY;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        POSIX_DUP = linker.downcallHandle(libc.find("dup").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        POSIX_CLOSE = linker.downcallHandle(libc.find("close").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MemorySegment notify;
        try {
            notify = linker.upcallStub(
                    MethodHandles.lookup().findStatic(Gtk4Compositor.class, "onExternalTextureDestroyed",
                            MethodType.methodType(void.class, MemorySegment.class)),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                    Arena.global());
        } catch (NoSuchMethodException | IllegalAccessException e) {
            notify = MemorySegment.NULL;
        }
        EXTERNAL_DESTROY_NOTIFY = notify;
    }

    private static int dupFd(int fd) {
        try {
            return (int) POSIX_DUP.invokeExact(fd);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void closeFd(int fd) {
        try {
            int unused = (int) POSIX_CLOSE.invokeExact(fd);
        } catch (Throwable ignored) {
        }
    }

    private final AtomicReference<ExternalFrame> pendingExternal = new AtomicReference<>();
    private final AtomicBoolean externalUpdateScheduled = new AtomicBoolean(false);
    private GdkTexture externalTexture;
    private final GtkPicture picture;
    private final GtkWidget widget;
    private final boolean composited;
    private GdkTexture softwareTexture;
    private volatile boolean disposed;

    /**
     * Creates the DMA-BUF compositor. Builds the widget hierarchy:
     * GtkGraphicsOffload &rarr; GtkPicture.
     * <p>
     * GtkPicture is used because GtkGraphicsOffload inspects its child's
     * render nodes for {@code GdkDmabufTexture} entries to offload to a
     * Wayland subsurface. GtkPicture produces exactly that render node
     * when its paintable is a GdkTexture.
     * <p>
     * Uses {@code GTK_CONTENT_FIT_FILL} so the rendered texture always
     * fills the widget area without any transforms.  This is critical for
     * {@code GtkGraphicsOffload}: the default {@code CONTAIN} mode wraps
     * the texture in a scale/translate {@code TransformNode} that
     * GtkGraphicsOffload (GTK 4.14) cannot reliably offload.
     * <p>
     * Offloading is disabled when GTK blurs the area behind the window with a
     * CSS {@code backdrop-filter}, because GTK 4.24 then shows nothing of the
     * window while the texture is offloaded.
     */
    Gtk4Compositor(boolean offload) {
        this.picture = GtkPicture.create();

        picture.setContentFit(Gtk.CONTENT_FIT_FILL);
        picture.setCanShrink(true);

        if (isAvailable()) {
            this.composited = true;
            GtkGraphicsOffload offloadRef = GtkGraphicsOffload.create(picture);
            offloadRef.setEnabled(offload ? Gtk.GRAPHICS_OFFLOAD_ENABLED : Gtk.GRAPHICS_OFFLOAD_DISABLED);
            this.widget = offloadRef;
        } else {
            this.composited = false;
            this.widget = picture;
        }

        widget.setHExpand(true);
        widget.setVExpand(true);
        widget.setHAlign(Gtk.ALIGN_FILL);
        widget.setVAlign(Gtk.ALIGN_FILL);
    }

    static boolean isAvailable() {
        boolean graphicsOffloadAvailable = GtkGraphicsOffload.isAvailable();
        boolean dmabufTextureBuilderAvailable = GdkDmabufTextureBuilder.isAvailable();

        return graphicsOffloadAvailable
                && dmabufTextureBuilderAvailable;
    }


    public GtkWidget getWidget() {
        return widget;
    }

    public void publishPixels(Pixels pixels) {
        if (disposed || pixels == null) {
            return;
        }
        GdkTexture texture = Gtk4Pixels.toTexture(pixels);
        if (texture == null) {
            return;
        }
        picture.setPaintable(texture);
        if (softwareTexture != null) {
            softwareTexture.unref();
        }
        softwareTexture = texture;
    }

    // ---------------------------------------------------------------------
    // Frames rendered by the pipeline into its own dma-bufs

    private record ExternalFrame(int fd, int stride, int offset, int fourcc, long modifier,
                                 int width, int height, Runnable release) {}

    /**
     * Publishes a dma-buf owned by the caller. Called on the render thread;
     * the texture is created on the GTK thread, and {@code release} is called
     * when GTK destroys it.
     */
    boolean publishDmaBuf(int fd, int stride, int offset, int fourcc, long modifier,
                          int width, int height, Runnable release) {
        if (!composited || disposed || width <= 0 || height <= 0) {
            return false;
        }
        int dup = dupFd(fd);
        if (dup < 0) {
            return false;
        }
        ExternalFrame old = pendingExternal.getAndSet(
                new ExternalFrame(dup, stride, offset, fourcc, modifier, width, height, release));
        if (old != null) {
            // Superseded before it was shown
            release(old);
        }
        if (externalUpdateScheduled.compareAndSet(false, true)) {
            Application.invokeLater(this::showExternalFrame);
        }
        return true;
    }

    private void showExternalFrame() {
        externalUpdateScheduled.set(false);
        ExternalFrame frame = pendingExternal.getAndSet(null);
        if (frame == null) {
            return;
        }
        if (disposed) {
            release(frame);
            return;
        }
        GdkTexture texture = importFrame(frame);
        if (texture == null) {
            Gtk4Log.log(COMPOSITOR, "showExternalFrame: cannot import the dma-buf");
            return;
        }
        picture.setPaintable(texture);
        // GTK keeps the texture while it is shown; the destroy notify releases the buffer
        if (externalTexture != null) {
            externalTexture.unref();
        }
        externalTexture = texture;
    }

    /** Closes the descriptor of a frame and gives its buffer back to the pipeline. */
    private static void release(ExternalFrame frame) {
        closeFd(frame.fd());
        frame.release().run();
    }

    /**
     * A texture of the dma-buf of a frame, whose destroy notify releases the
     * frame, or null, after releasing it, when GTK cannot import it.
     */
    private static GdkTexture importFrame(ExternalFrame frame) {
        GdkDmabufTextureBuilder builder = GdkDmabufTextureBuilder.create();
        builder.setDisplay(GdkDisplay.getDefault());
        builder.setWidth(frame.width());
        builder.setHeight(frame.height());
        builder.setFourcc(frame.fourcc());
        builder.setModifier(frame.modifier());
        builder.setNPlanes(1);
        builder.setFd(0, frame.fd());
        builder.setStride(0, frame.stride());
        builder.setOffset(0, frame.offset());
        long token = EXTERNAL_TOKENS.getAndIncrement();
        EXTERNAL_RELEASES.put(token, () -> release(frame));
        GdkTexture texture = builder.build(EXTERNAL_DESTROY_NOTIFY, MemorySegment.ofAddress(token));
        builder.unref();
        if (!texture.isNull()) {
            return texture;
        }
        Runnable cleanup = EXTERNAL_RELEASES.remove(token);
        if (cleanup != null) {
            cleanup.run();
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static void onExternalTextureDestroyed(MemorySegment data) {
        Runnable cleanup = EXTERNAL_RELEASES.remove(data.address());
        if (cleanup != null) {
            cleanup.run();
        }
    }

    void clearPaintable() {
        if (!disposed) {
            picture.setPaintable(null);
        }
    }

    public void dispose() {
        disposed = true;
        Gtk4Log.log(COMPOSITOR, "dispose: composited=%b", composited);

        // Release the software-fallback texture (if used)
        if (softwareTexture != null) {
            softwareTexture.unref();
            softwareTexture = null;
        }
        if (externalTexture != null) {
            externalTexture.unref();
            externalTexture = null;
        }
        ExternalFrame external = pendingExternal.getAndSet(null);
        if (external != null) {
            release(external);
        }
    }

    boolean isComposited() {
        return composited;
    }

    /**
     * The texture currently shown, or {@code null}.
     */
    GdkTexture getCurrentTexture() {
        if (externalTexture != null) {
            return externalTexture;
        }
        return softwareTexture;
    }
}
