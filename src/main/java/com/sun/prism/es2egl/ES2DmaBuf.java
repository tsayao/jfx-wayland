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

import com.sun.prism.es2egl.ES2Native.EGL;
import com.sun.prism.es2egl.ES2Native.EGLX;
import com.sun.prism.es2egl.ES2Native.GBM;
import com.sun.prism.es2egl.ES2Native.GL;
import com.sun.prism.es2egl.ES2Native.LibC;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A linear buffer of the render node, which the compositor shows as a
 * dma-buf: imported back as an EGL image, a texture is bound to it, and
 * a framebuffer to the texture, into which the frames are copied.
 */
final class ES2DmaBuf {

    static final int DRM_FORMAT_ARGB8888 = 0x34325241;
    // The same buffer, whose alpha is ignored: for opaque frames
    static final int DRM_FORMAT_XRGB8888 = 0x34325258;
    static final long DRM_FORMAT_MOD_LINEAR = 0L;

    private static final int GBM_BO_USE_RENDERING = 1 << 2;
    private static final int GBM_BO_USE_LINEAR = 1 << 4;
    private static final int EGL_NONE = 0x3038;
    private static final int EGL_WIDTH = 0x3057;
    private static final int EGL_HEIGHT = 0x3056;
    private static final int EGL_LINUX_DMA_BUF_EXT = 0x3270;
    private static final int EGL_LINUX_DRM_FOURCC_EXT = 0x3271;
    private static final int EGL_DMA_BUF_PLANE0_FD_EXT = 0x3272;
    private static final int EGL_DMA_BUF_PLANE0_OFFSET_EXT = 0x3273;
    private static final int EGL_DMA_BUF_PLANE0_PITCH_EXT = 0x3274;
    private static final int EGL_SYNC_NATIVE_FENCE_ANDROID = 0x3144;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_NEAREST = 0x2600;
    private static final int DMA_BUF_SYNC_WRITE = 2;
    private static final long DMA_BUF_IOCTL_IMPORT_SYNC_FILE = 0x40086203L;

    final int width;
    final int height;
    private final GLContext context;
    private MemorySegment bo = MemorySegment.NULL;
    private MemorySegment eglImage = MemorySegment.NULL;
    private int fd = -1;
    private int stride;
    private int texture;
    private int fbo;

    private ES2DmaBuf(GLContext context, int width, int height) {
        this.context = context;
        this.width = width;
        this.height = height;
    }

    /** Creates a buffer, or returns null if it cannot be created or rendered to. */
    static ES2DmaBuf create(GLContext context, int width, int height) {
        ES2DmaBuf buffer = new ES2DmaBuf(context, width, height);
        if (buffer.allocate() && buffer.importAsTexture() && buffer.createFramebuffer()) {
            return buffer;
        }
        buffer.dispose();
        return null;
    }

    int fd() {
        return fd;
    }

    int stride() {
        return stride;
    }

    int fbo() {
        return fbo;
    }

    private boolean allocate() {
        bo = GBM.gbm_bo_create(context.getFactory().getGbm(), width, height, DRM_FORMAT_ARGB8888,
                GBM_BO_USE_RENDERING | GBM_BO_USE_LINEAR);
        if (bo.address() == 0) {
            return false;
        }
        fd = GBM.gbm_bo_get_fd(bo);
        stride = GBM.gbm_bo_get_stride(bo);
        return fd >= 0;
    }

    private boolean importAsTexture() {
        eglImage = importDmaBuf();
        if (eglImage.address() == 0) {
            System.err.println("Prism ES2 EGL: cannot import a dma-buf (EGL error 0x"
                    + Integer.toHexString(EGL.eglGetError()) + ")");
            return false;
        }
        texture = context.genAndBindTexture();
        GL.glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, eglImage);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        context.setBoundTexture(0);
        return true;
    }

    private MemorySegment importDmaBuf() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment attribs = a.allocateFrom(JAVA_INT,
                    EGL_WIDTH, width, EGL_HEIGHT, height,
                    EGL_LINUX_DRM_FOURCC_EXT, DRM_FORMAT_ARGB8888,
                    EGL_DMA_BUF_PLANE0_FD_EXT, fd,
                    EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
                    EGL_DMA_BUF_PLANE0_PITCH_EXT, stride,
                    EGL_NONE);
            return EGLX.eglCreateImageKHR(context.getFactory().getDisplay(), MemorySegment.NULL,
                    EGL_LINUX_DMA_BUF_EXT, MemorySegment.NULL, attribs);
        }
    }

    private boolean createFramebuffer() {
        fbo = context.createFBO(texture);
        return fbo != 0;
    }

    /**
     * Makes the rendering submitted so far wait for by the readers of the
     * buffer, through a sync file attached to the dma-buf.
     *
     * @return false when the sync file cannot be created or attached: the
     *     caller waits for the GPU instead
     */
    boolean attachRenderingFence() {
        int syncFd = createSyncFile();
        if (syncFd < 0) {
            return false;
        }
        try (Arena a = Arena.ofConfined()) {
            // struct dma_buf_import_sync_file { __u32 flags; __s32 fd; }
            MemorySegment request = a.allocateFrom(JAVA_INT, DMA_BUF_SYNC_WRITE, syncFd);
            return LibC.ioctl(fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, request) == 0;
        } finally {
            LibC.close(syncFd);
        }
    }

    /** A sync file of the commands submitted so far, through a native fence of EGL, or -1. */
    private int createSyncFile() {
        MemorySegment display = context.getFactory().getDisplay();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sync = EGLX.eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID,
                    a.allocateFrom(JAVA_INT, EGL_NONE));
            if (sync.address() == 0) {
                return -1;
            }
            // The fence is created when the commands are flushed
            context.flush();
            int syncFd = EGLX.eglDupNativeFenceFDANDROID(display, sync);
            EGLX.eglDestroySyncKHR(display, sync);
            return syncFd;
        }
    }

    /** Releases the framebuffer, the texture, the EGL image, the dma-buf and the buffer. */
    void dispose() {
        context.deleteFBO(fbo);
        context.deleteTexture(texture);
        fbo = texture = 0;
        if (eglImage.address() != 0) {
            EGLX.eglDestroyImageKHR(context.getFactory().getDisplay(), eglImage);
            eglImage = MemorySegment.NULL;
        }
        if (fd >= 0) {
            LibC.close(fd);
            fd = -1;
        }
        if (bo.address() != 0) {
            GBM.gbm_bo_destroy(bo);
            bo = MemorySegment.NULL;
        }
    }
}
