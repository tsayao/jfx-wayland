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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The EGL display and the OpenGL context of the pipeline, in place of the
 * native LinuxEGLFactory of the egl branch: the display is opened on a
 * render node through GBM, as the windows of GTK 4 have no native window to
 * draw to, and the one context renders without a surface into framebuffer
 * objects. Its frames reach the windows as dma-bufs or as pixels.
 */
final class GLFactory {

    private static final int EGL_PLATFORM_GBM_KHR = 0x31D7;
    private static final int EGL_EXTENSIONS = 0x3055;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_NONE = 0x3038;
    private static final int EGL_RENDERABLE_TYPE = 0x3040;
    private static final int EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_RED_SIZE = 0x3024;
    private static final int EGL_GREEN_SIZE = 0x3023;
    private static final int EGL_BLUE_SIZE = 0x3022;
    private static final int EGL_ALPHA_SIZE = 0x3021;
    private static final int EGL_DEPTH_SIZE = 0x3025;
    private static final int EGL_DEVICE_EXT = 0x322C;
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;
    private static final int GL_EXTENSIONS = 0x1F03;
    private static final int O_RDWR = 2;
    private static final int O_CLOEXEC = 0x80000;

    private static final List<String> RENDER_NODES = List.of(
            "/dev/dri/renderD128", "/dev/dri/renderD129", "/dev/dri/renderD130", "/dev/dri/renderD131");

    /** The OpenGL version, as GL_VERSION gives it. */
    record Version(int major, int minor) {
        static Version parse(String version) {
            String[] numbers = version.split("[ .]", 3);
            try {
                return new Version(Integer.parseInt(numbers[0]), Integer.parseInt(numbers[1]));
            } catch (RuntimeException e) {
                return new Version(0, 0);
            }
        }

        boolean atLeast(int major, int minor) {
            return this.major > major || (this.major == major && this.minor >= minor);
        }
    }

    // Lives as long as the factory: only for the allocations made once
    private final Arena arena = Arena.ofShared();
    private final boolean verbose;
    private int drmFd = -1;
    private MemorySegment gbm = MemorySegment.NULL;
    private MemorySegment display = MemorySegment.NULL;
    private MemorySegment config = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private Thread currentThread;
    private String vendor;
    private String renderer;
    private String versionString;
    private Version version;
    private Set<String> glExtensions = Set.of();
    private Set<String> eglExtensions = Set.of();

    private GLFactory(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Opens the display and creates the context, current on the calling
     * thread.
     *
     * @throws IllegalStateException with the reason when OpenGL 2.1 with
     *     framebuffer objects is not available
     */
    static GLFactory create(GLPixelFormat format, boolean verbose) {
        GLFactory factory = new GLFactory(verbose);
        try {
            factory.init(format);
            return factory;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            factory.dispose();
            throw e instanceof IllegalStateException ise ? ise : new IllegalStateException(e.toString(), e);
        }
    }

    private void init(GLPixelFormat format) {
        openDisplay(format);
        eglExtensions = Set.of(eglString(EGL_EXTENSIONS).split(" "));
        requireEGLExtension("EGL_KHR_surfaceless_context");
        createContext();
        makeCurrent();
        readDriver();
        checkVersion();
    }

    private void log(String message) {
        if (verbose) {
            System.err.println("Prism ES2 EGL: " + message);
        }
    }

    // ---------------------------------------------------------------------
    // Display

    /** Opens the first hardware render node with EGL, or the one of -Dprism.es2egl.device. */
    private void openDisplay(GLPixelFormat format) {
        String forced = System.getProperty("prism.es2egl.device");
        List<String> nodes = forced != null ? List.of(forced) : RENDER_NODES;
        List<String> reasons = new ArrayList<>();
        for (String node : nodes) {
            String reason = tryRenderNode(node, format);
            if (reason == null) {
                return;
            }
            reasons.add(node + ": " + reason);
        }
        throw new IllegalStateException("No render node with EGL: " + String.join("; ", reasons));
    }

    /**
     * Opens GBM and EGL on a render node and keeps them when the node has a
     * hardware OpenGL configuration.
     *
     * @return null when the node is used, or why it is not
     */
    private String tryRenderNode(String node, GLPixelFormat format) {
        int fd = LibC.open(arena.allocateFrom(node), O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            return "cannot open";
        }
        MemorySegment device = GBM.gbm_create_device(fd);
        if (device.address() == 0) {
            LibC.close(fd);
            return "no GBM device";
        }
        MemorySegment d = initializeDisplay(device);
        if (d == null) {
            GBM.gbm_device_destroy(device);
            LibC.close(fd);
            return "no EGL display";
        }
        MemorySegment chosen = hardwareConfig(d, format);
        if (chosen == null) {
            EGL.eglTerminate(d);
            GBM.gbm_device_destroy(device);
            LibC.close(fd);
            return "no hardware OpenGL configuration";
        }
        drmFd = fd;
        gbm = device;
        display = d;
        config = chosen;
        log("using " + node);
        return null;
    }

    private MemorySegment initializeDisplay(MemorySegment device) {
        MemorySegment d = EGL.eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, device, MemorySegment.NULL);
        if (d.address() == 0 || EGL.eglInitialize(d, MemorySegment.NULL, MemorySegment.NULL) == 0) {
            return null;
        }
        return d;
    }

    /**
     * An OpenGL configuration with the sizes of the pixel format, or null when
     * the display has none or is a software rasterizer, which is slower than
     * Prism sw.
     */
    private MemorySegment hardwareConfig(MemorySegment d, GLPixelFormat format) {
        MemorySegment attribs = arena.allocateFrom(JAVA_INT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                EGL_RED_SIZE, format.redSize(), EGL_GREEN_SIZE, format.greenSize(),
                EGL_BLUE_SIZE, format.blueSize(), EGL_ALPHA_SIZE, format.alphaSize(),
                EGL_DEPTH_SIZE, format.depthSize(),
                EGL_NONE);
        MemorySegment configs = arena.allocate(ADDRESS);
        MemorySegment count = arena.allocate(JAVA_INT);
        if (EGL.eglChooseConfig(d, attribs, configs, 1, count) == 0 || count.get(JAVA_INT, 0) < 1
                || isSoftware(d)) {
            return null;
        }
        return configs.get(ADDRESS, 0);
    }

    /** Whether the device of the display is a software rasterizer, as far as EGL_EXT_device_query tells. */
    private boolean isSoftware(MemorySegment d) {
        if (!string(EGL.eglQueryString(MemorySegment.NULL, EGL_EXTENSIONS)).contains("EGL_EXT_device_query")) {
            return false;
        }
        MemorySegment device = arena.allocate(ADDRESS);
        if (EGLX.eglQueryDisplayAttribEXT(d, EGL_DEVICE_EXT, device) == 0) {
            return false;
        }
        return string(EGLX.eglQueryDeviceStringEXT(device.get(ADDRESS, 0), EGL_EXTENSIONS))
                .contains("EGL_MESA_device_software");
    }

    private void requireEGLExtension(String name) {
        if (!eglExtensions.contains(name)) {
            throw new IllegalStateException(name + " is not supported");
        }
    }

    // ---------------------------------------------------------------------
    // Context

    /** A context of the desktop OpenGL API, of the compatibility profile, without a surface. */
    private void createContext() {
        if (EGL.eglBindAPI(EGL_OPENGL_API) == 0) {
            throw new IllegalStateException("cannot bind EGL_OPENGL_API");
        }
        context = EGL.eglCreateContext(display, config, MemorySegment.NULL, MemorySegment.NULL);
        if (context.address() == 0) {
            throw new IllegalStateException("eglCreateContext failed (EGL error 0x"
                    + Integer.toHexString(EGL.eglGetError()) + ")");
        }
    }

    /** Makes the context current on the calling thread, the render thread. */
    void makeCurrent() {
        Thread t = Thread.currentThread();
        if (currentThread == t) {
            return;
        }
        if (EGL.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, context) == 0) {
            throw new IllegalStateException("eglMakeCurrent failed (EGL error 0x"
                    + Integer.toHexString(EGL.eglGetError()) + ")");
        }
        currentThread = t;
    }

    private void readDriver() {
        vendor = glString(GL_VENDOR);
        renderer = glString(GL_RENDERER);
        versionString = glString(GL_VERSION);
        version = Version.parse(versionString);
        glExtensions = Set.of(glString(GL_EXTENSIONS).split(" "));
        log(vendor + ", " + renderer + ", " + versionString);
    }

    /**
     * OpenGL 2.1, as the ES2 pipeline needs, with the framebuffer objects of
     * OpenGL 3.0 that render the frames and copy them to the windows.
     */
    private void checkVersion() {
        if (!version.atLeast(2, 1)) {
            throw new IllegalStateException("OpenGL " + version.major() + "." + version.minor()
                    + " is older than 2.1");
        }
        if (!version.atLeast(3, 0) && !isGLExtensionSupported("GL_ARB_framebuffer_object")) {
            throw new IllegalStateException("GL_ARB_framebuffer_object is not supported");
        }
    }

    // ---------------------------------------------------------------------
    // Queries

    MemorySegment getDisplay() {
        return display;
    }

    MemorySegment getGbm() {
        return gbm;
    }

    String getVendor() {
        return vendor;
    }

    String getRenderer() {
        return renderer;
    }

    String getVersion() {
        return versionString;
    }

    boolean isGLExtensionSupported(String name) {
        return glExtensions.contains(name);
    }

    boolean isEGLExtensionSupported(String name) {
        return eglExtensions.contains(name);
    }

    /** Textures of any size, which OpenGL 2.0 made core. */
    boolean isNPOTSupported() {
        return version.atLeast(2, 0) || isGLExtensionSupported("GL_ARB_texture_non_power_of_two");
    }

    boolean isMultisampleSupported() {
        return version.atLeast(3, 0) || isGLExtensionSupported("GL_ARB_multisample");
    }

    /** Whether frames can be exported to the compositor as dma-bufs. */
    boolean isExportSupported() {
        return isGLExtensionSupported("GL_OES_EGL_image")
                && isEGLExtensionSupported("EGL_EXT_image_dma_buf_import")
                && isEGLExtensionSupported("EGL_KHR_image_base");
    }

    /** Whether the rendering can be waited for by the compositor through a sync file. */
    boolean isSyncFileSupported() {
        return isEGLExtensionSupported("EGL_ANDROID_native_fence_sync");
    }

    void printDriverInformation() {
        System.out.println("Graphics Vendor: " + vendor);
        System.out.println("       Renderer: " + renderer);
        System.out.println("        Version: " + versionString);
    }

    private String eglString(int name) {
        return string(EGL.eglQueryString(display, name));
    }

    private static String glString(int name) {
        return string(GL.glGetString(name));
    }

    static String string(MemorySegment s) {
        return s.address() == 0 ? "" : s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    // ---------------------------------------------------------------------

    void dispose() {
        if (display.address() != 0) {
            EGL.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            if (context.address() != 0) {
                EGL.eglDestroyContext(display, context);
            }
            EGL.eglTerminate(display);
        }
        if (gbm.address() != 0) {
            GBM.gbm_device_destroy(gbm);
        }
        if (drmFd >= 0) {
            LibC.close(drmFd);
        }
        context = display = gbm = MemorySegment.NULL;
        drmFd = -1;
        currentThread = null;
    }
}
