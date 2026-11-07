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

import com.sun.prism.gl.GLNative.EGL;
import com.sun.prism.gl.GLNative.EGLX;
import com.sun.prism.gl.GLNative.GBM;
import com.sun.prism.gl.GLNative.GL;
import com.sun.prism.gl.GLNative.LibC;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * The OpenGL 4.5 core device: an EGL context on the GBM platform of a render
 * node, without surfaces, and the state of the rendering.
 * <p>
 * The context renders with an upper-left origin and a [0, 1] depth range
 * (glClipControl), as Vulkan does: row 0 of the textures and framebuffers is
 * their top row, the scissor and the reads use top-down coordinates, and the
 * projections, the shaders and the exported frames are those of the Vulkan
 * pipeline. The frames are presented in linear buffers of the render node,
 * imported as EGL images and exported as dma-bufs.
 * <p>
 * Every call is made on the render thread, where the context is current.
 */
final class GLDevice {

    static final int MAX_TEXTURE_UNITS = 4;
    private static final int UNIFORM_BINDING = 0;
    private static final int TRANSFORM_BINDING = 1;
    private static final int FIRST_SAMPLER_BINDING = 1;

    private static final int VERTEX_FLOATS = 7;
    private static final int VERTEX_SIZE = VERTEX_FLOATS * 4;
    static final int MESH_VERTEX_FLOATS = 9;
    private static final int MAX_QUADS = 16383;

    static final int COMP_CLEAR = 0;
    static final int COMP_SRC = 1;
    static final int COMP_SRCOVER = 2;
    static final int COMP_DSTOUT = 3;
    static final int COMP_ADD = 4;
    static final int COMP_COUNT = 5;

    static final int WRAP_CLAMP_TO_EDGE = 0;
    static final int WRAP_REPEAT = 1;
    static final int WRAP_CLAMP_TO_ZERO = 2;
    private static final int WRAP_COUNT = 3;

    static final int CULL_NONE = 0;
    static final int CULL_BACK = 1;
    static final int CULL_FRONT = 2;

    /** The floats of the uniform block of the Phong shaders. */
    static final int PHONG_UNIFORM_FLOATS = 108;

    static final int DRM_FORMAT_ARGB8888 = 0x34325241;
    // The same with the alpha ignored, which GTK knows to be opaque
    static final int DRM_FORMAT_XRGB8888 = 0x34325258;
    static final long DRM_FORMAT_MOD_LINEAR = 0L;

    // OpenGL
    private static final int GL_NO_ERROR = 0;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;
    private static final int GL_EXTENSIONS = 0x1F03;
    private static final int GL_NUM_EXTENSIONS = 0x821D;
    private static final int GL_MAJOR_VERSION = 0x821B;
    private static final int GL_MINOR_VERSION = 0x821C;
    private static final int GL_MAX_TEXTURE_SIZE = 0x0D33;
    private static final int GL_MAX_SAMPLES = 0x8D57;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_ZERO = 0;
    private static final int GL_ONE = 1;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    private static final int GL_LEQUAL = 0x0203;
    private static final int GL_FRONT = 0x0404;
    private static final int GL_BACK = 0x0405;
    private static final int GL_FRONT_AND_BACK = 0x0408;
    private static final int GL_CCW = 0x0901;
    private static final int GL_LINE = 0x1B01;
    private static final int GL_FILL = 0x1B02;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_DEPTH_BUFFER_BIT = 0x0100;
    private static final int GL_UPPER_LEFT = 0x8CA2;
    private static final int GL_ZERO_TO_ONE = 0x935F;
    private static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    private static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    private static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    private static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    private static final int GL_PACK_ALIGNMENT = 0x0D05;
    private static final int GL_PACK_ROW_LENGTH = 0x0D02;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_TEXTURE_2D_MULTISAMPLE = 0x9100;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_R8 = 0x8229;
    private static final int GL_RGBA32F = 0x8814;
    private static final int GL_BGRA = 0x80E1;
    private static final int GL_RED = 0x1903;
    private static final int GL_ALPHA = 0x1906;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_UNSIGNED_SHORT = 0x1403;
    private static final int GL_UNSIGNED_INT = 0x1405;
    private static final int GL_FLOAT = 0x1406;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_TEXTURE_MAX_LEVEL = 0x813D;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_LINEAR = 0x2601;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_REPEAT = 0x2901;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int GL_CLAMP_TO_BORDER = 0x812D;
    private static final int GL_TEXTURE_SWIZZLE_RGBA = 0x8E46;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_RENDERBUFFER = 0x8D41;
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_INFO_LOG_LENGTH = 0x8B84;
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    private static final int GL_UNIFORM_BUFFER = 0x8A11;
    private static final int GL_STREAM_DRAW = 0x88E0;
    private static final int GL_STATIC_DRAW = 0x88E4;
    private static final int GL_TRIANGLES = 0x0004;

    // EGL
    private static final int EGL_PLATFORM_GBM_KHR = 0x31D7;
    private static final int EGL_EXTENSIONS = 0x3055;
    private static final int EGL_OPENGL_API = 0x30A2;
    private static final int EGL_NONE = 0x3038;
    private static final int EGL_CONTEXT_MAJOR_VERSION = 0x3098;
    private static final int EGL_CONTEXT_MINOR_VERSION = 0x30FB;
    private static final int EGL_CONTEXT_OPENGL_PROFILE_MASK = 0x30FD;
    private static final int EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT = 1;
    private static final int EGL_RENDERABLE_TYPE = 0x3040;
    private static final int EGL_OPENGL_BIT = 0x0008;
    private static final int EGL_CONFIG_CAVEAT = 0x3027;
    private static final int EGL_DEVICE_EXT = 0x322C;
    private static final int EGL_LINUX_DMA_BUF_EXT = 0x3270;
    private static final int EGL_WIDTH = 0x3057;
    private static final int EGL_HEIGHT = 0x3056;
    private static final int EGL_LINUX_DRM_FOURCC_EXT = 0x3271;
    private static final int EGL_DMA_BUF_PLANE0_FD_EXT = 0x3272;
    private static final int EGL_DMA_BUF_PLANE0_OFFSET_EXT = 0x3273;
    private static final int EGL_DMA_BUF_PLANE0_PITCH_EXT = 0x3274;
    private static final int EGL_SYNC_NATIVE_FENCE_ANDROID = 0x3144;

    // GBM and the kernel
    private static final int GBM_BO_USE_RENDERING = 1 << 2;
    private static final int GBM_BO_USE_LINEAR = 1 << 4;
    private static final int O_RDWR = 2;
    private static final int O_CLOEXEC = 0x80000;
    private static final int DMA_BUF_SYNC_WRITE = 2;
    private static final long DMA_BUF_IOCTL_IMPORT_SYNC_FILE = 0x40086203L;

    private static String failure;

    // Lives as long as the device: only for allocations made once, not per call
    private final Arena arena = Arena.ofShared();
    // Reused by the calls on the render thread that pass or return small values
    private final MemorySegment ints;
    private final MemorySegment eglNone;
    private final MemorySegment syncRequest;
    private final MemorySegment swizzleAlpha;
    private final MemorySegment swizzleGray;
    private final boolean verbose;
    private int drmFd = -1;
    private MemorySegment gbm = MemorySegment.NULL;
    private MemorySegment display = MemorySegment.NULL;
    private MemorySegment config = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private Thread currentThread;
    private String deviceName;
    private int maxTextureSize;
    private int msaaSamples;
    private boolean exportSupported;
    private boolean fenceSupported;

    // Objects for the quads
    private int quadVertexArray;
    private int vertexBuffer;
    private int colorBuffer;
    private int quadIndexBuffer;
    private int transformBuffer;
    private int uniformBuffer;
    private int passThroughVertex;
    private final int[][] samplers = new int[2][WRAP_COUNT];
    private final MemorySegment transform;

    // 3D
    private String phongVertexSource;
    private String phongFragmentSource;
    private int phongVertex;
    private final Map<Integer, Integer> phongPrograms = new HashMap<>();

    // The state of the rendering
    private GLImage target;
    private boolean depthTest;
    private boolean clipEnabled;
    private int clipX;
    private int clipY;
    private int clipW;
    private int clipH;
    private int compositeMode = COMP_SRCOVER;
    private GLShader shader;
    private final GLImage[] textures = new GLImage[MAX_TEXTURE_UNITS];
    private final int[] textureSamplers = new int[MAX_TEXTURE_UNITS];
    private int boundFramebuffer = -1;
    private int boundProgram = -1;
    private int blendState = -1;
    private int depthState = -1;
    private int cullState = -1;
    private boolean wireframeState;
    private boolean scissorState;

    // Statistics, with -Dprism.gl.stats=true
    private final boolean stats = Boolean.getBoolean("prism.gl.stats");
    private int statDraws;
    private int statUploads;

    private GLDevice(boolean verbose) {
        this.verbose = verbose;
        this.transform = arena.allocate(64, 16);
        this.ints = arena.allocate(JAVA_INT, 4);
        this.eglNone = arena.allocateFrom(JAVA_INT, EGL_NONE);
        this.syncRequest = arena.allocate(8, 4);
        this.swizzleAlpha = arena.allocateFrom(JAVA_INT, GL_ZERO, GL_ZERO, GL_ZERO, GL_RED);
        this.swizzleGray = arena.allocateFrom(JAVA_INT, GL_RED, GL_RED, GL_RED, GL_ONE);
        for (int i = 0; i < 16; i++) {
            transform.setAtIndex(JAVA_FLOAT, i, i % 5 == 0 ? 1f : 0f);
        }
    }

    static String getFailure() {
        return failure;
    }

    /**
     * Creates the device on a render node, or returns null if OpenGL 4.5 is
     * not available, with the reason in getFailure().
     */
    static GLDevice create(String passThroughSource, boolean verbose) {
        GLDevice device = new GLDevice(verbose);
        try {
            device.init(passThroughSource);
            return device;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            failure = e.getMessage() != null ? e.getMessage() : e.toString();
            device.destroy();
            return null;
        }
    }

    private void log(String message) {
        if (verbose) {
            System.err.println("Prism GL: " + message);
        }
    }

    private void init(String passThroughSource) {
        openDisplay();
        if (EGL.eglBindAPI(EGL_OPENGL_API) == 0) {
            throw new IllegalStateException("eglBindAPI(EGL_OPENGL_API) failed");
        }
        String eglExtensions = eglString(EGL_EXTENSIONS);
        if (!eglExtensions.contains("EGL_KHR_surfaceless_context")) {
            throw new IllegalStateException("EGL_KHR_surfaceless_context is not supported");
        }
        createContext();
        makeCurrent();
        readCapabilities(eglExtensions);
        // Upper-left origin and [0, 1] depth, as in Vulkan
        GL.glClipControl(GL_UPPER_LEFT, GL_ZERO_TO_ONE);
        GL.glFrontFace(GL_CCW);
        GL.glDepthFunc(GL_LEQUAL);
        passThroughVertex = compile(GL_VERTEX_SHADER, passThroughSource, "PassThrough.vert");
        if (passThroughVertex == 0) {
            throw new IllegalStateException("Cannot compile the vertex shader");
        }
        createQuadObjects();
        createSamplers();
        checkError("init");
    }

    /** An OpenGL 4.5 core context, without a surface. */
    private void createContext() {
        MemorySegment attribs = arena.allocateFrom(JAVA_INT,
                EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 5,
                EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE);
        context = EGL.eglCreateContext(display, config, MemorySegment.NULL, attribs);
        if (context.address() == 0) {
            throw new IllegalStateException("No OpenGL 4.5 core context (EGL error 0x"
                    + Integer.toHexString(EGL.eglGetError()) + ")");
        }
    }

    /** The limits, the multisampling and whether frames can be exported with sync files. */
    private void readCapabilities(String eglExtensions) {
        deviceName = glString(GL_RENDERER);
        log("using " + deviceName + ", " + glString(GL_VERSION));
        maxTextureSize = getInteger(GL_MAX_TEXTURE_SIZE);
        msaaSamples = getInteger(GL_MAX_SAMPLES) >= 4 ? 4 : 1;
        exportSupported = hasGLExtension("GL_OES_EGL_image")
                && eglExtensions.contains("EGL_EXT_image_dma_buf_import")
                && eglExtensions.contains("EGL_KHR_image_base");
        fenceSupported = eglExtensions.contains("EGL_ANDROID_native_fence_sync");
        log("export " + (exportSupported ? "yes" : "no") + ", sync file " + (fenceSupported ? "yes" : "no")
                + ", msaa " + msaaSamples + "x");
    }

    /** A sampler for each filter and address mode; the border color is transparent black by default. */
    private void createSamplers() {
        for (int linear = 0; linear < 2; linear++) {
            for (int wrap = 0; wrap < WRAP_COUNT; wrap++) {
                samplers[linear][wrap] = createSampler(linear == 1, wrap);
            }
        }
    }

    private int createSampler(boolean linear, int wrap) {
        int s = gen(GL::glGenSamplers);
        int filter = linear ? GL_LINEAR : GL_NEAREST;
        int mode = switch (wrap) {
            case WRAP_REPEAT -> GL_REPEAT;
            case WRAP_CLAMP_TO_ZERO -> GL_CLAMP_TO_BORDER;
            default -> GL_CLAMP_TO_EDGE;
        };
        GL.glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, filter);
        GL.glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, filter);
        GL.glSamplerParameteri(s, GL_TEXTURE_WRAP_S, mode);
        GL.glSamplerParameteri(s, GL_TEXTURE_WRAP_T, mode);
        return s;
    }

    /**
     * Whether the device of the display is a software rasterizer, as far as
     * EGL_EXT_device_query tells.
     */
    private boolean isSoftware(MemorySegment d) {
        MemorySegment s = EGL.eglQueryString(MemorySegment.NULL, EGL_EXTENSIONS);
        if (s.address() == 0 || !s.reinterpret(Long.MAX_VALUE).getString(0).contains("EGL_EXT_device_query")) {
            return false;
        }
        MemorySegment device = arena.allocate(ADDRESS);
        if (EGLX.eglQueryDisplayAttribEXT(d, EGL_DEVICE_EXT, device) == 0) {
            return false;
        }
        MemorySegment extensions = EGLX.eglQueryDeviceStringEXT(device.get(ADDRESS, 0), EGL_EXTENSIONS);
        return extensions.address() != 0
                && extensions.reinterpret(Long.MAX_VALUE).getString(0).contains("EGL_MESA_device_software");
    }

    /**
     * Opens the first hardware render node on which GBM and EGL work, or
     * the one of -Dprism.gl.device.
     */
    private void openDisplay() {
        String forced = System.getProperty("prism.gl.device");
        String[] nodes = forced != null ? new String[] {forced}
                : new String[] {"/dev/dri/renderD128", "/dev/dri/renderD129", "/dev/dri/renderD130",
                    "/dev/dri/renderD131"};
        StringBuilder reasons = new StringBuilder();
        for (String node : nodes) {
            String reason = tryRenderNode(node);
            if (reason == null) {
                return;
            }
            reasons.append(node).append(": ").append(reason).append("; ");
        }
        throw new IllegalStateException("No render node with EGL: " + reasons);
    }

    /**
     * Opens GBM and EGL on a render node and keeps them when the node has a
     * hardware OpenGL configuration.
     *
     * @return null when the node is used, or why it is not
     */
    private String tryRenderNode(String node) {
        int fd = LibC.open(arena.allocateFrom(node), O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            return "cannot open";
        }
        MemorySegment g = GBM.gbm_create_device(fd);
        if (g.address() == 0) {
            LibC.close(fd);
            return "no GBM device";
        }
        MemorySegment d = EGL.eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, g, MemorySegment.NULL);
        MemorySegment major = arena.allocate(JAVA_INT);
        MemorySegment minor = arena.allocate(JAVA_INT);
        if (d.address() == 0 || EGL.eglInitialize(d, major, minor) == 0) {
            GBM.gbm_device_destroy(g);
            LibC.close(fd);
            return "no EGL display";
        }
        MemorySegment chosen = hardwareConfig(d);
        if (chosen == null) {
            EGL.eglTerminate(d);
            GBM.gbm_device_destroy(g);
            LibC.close(fd);
            return "no hardware OpenGL configuration";
        }
        config = chosen;
        drmFd = fd;
        gbm = g;
        display = d;
        log(node + ", EGL " + major.get(JAVA_INT, 0) + "." + minor.get(JAVA_INT, 0));
        return null;
    }

    /**
     * An OpenGL configuration of a display, or null when it has none or is a
     * software rasterizer. Software rasterizers such as llvmpipe are slower
     * than Prism sw; Mesa does not mark their configs as slow, but their
     * device is EGL_MESA_device_software, as when the render node has no 3D
     * driver and GBM falls back to kms_swrast.
     */
    private MemorySegment hardwareConfig(MemorySegment d) {
        MemorySegment attribs = arena.allocateFrom(JAVA_INT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
                EGL_CONFIG_CAVEAT, EGL_NONE, EGL_NONE);
        MemorySegment configs = arena.allocate(ADDRESS);
        MemorySegment count = arena.allocate(JAVA_INT);
        if (EGL.eglChooseConfig(d, attribs, configs, 1, count) == 0 || count.get(JAVA_INT, 0) < 1 || isSoftware(d)) {
            return null;
        }
        return configs.get(ADDRESS, 0);
    }

    /**
     * Makes the context current on the calling thread, the render thread.
     */
    void makeCurrent() {
        Thread t = Thread.currentThread();
        if (currentThread != t) {
            if (EGL.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, context) == 0) {
                throw new IllegalStateException("eglMakeCurrent failed (EGL error 0x"
                        + Integer.toHexString(EGL.eglGetError()) + ")");
            }
            currentThread = t;
            // Another thread may have changed the bindings
            invalidateState();
        }
    }

    private void invalidateState() {
        boundFramebuffer = -1;
        boundProgram = -1;
        blendState = -1;
        depthState = -1;
        cullState = -1;
    }

    private String eglString(int name) {
        MemorySegment s = EGL.eglQueryString(display, name);
        return s.address() == 0 ? "" : s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static String glString(int name) {
        MemorySegment s = GL.glGetString(name);
        return s.address() == 0 ? "" : s.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private int getInteger(int name) {
        ints.set(JAVA_INT, 0, 0);
        GL.glGetIntegerv(name, ints);
        return ints.get(JAVA_INT, 0);
    }

    private boolean hasGLExtension(String name) {
        int n = getInteger(GL_NUM_EXTENSIONS);
        for (int i = 0; i < n; i++) {
            MemorySegment s = GL.glGetStringi(GL_EXTENSIONS, i);
            if (s.address() != 0 && name.equals(s.reinterpret(Long.MAX_VALUE).getString(0))) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface Generator {
        void generate(int n, MemorySegment names);
    }

    private int gen(Generator g) {
        ints.set(JAVA_INT, 0, 0);
        g.generate(1, ints);
        return ints.get(JAVA_INT, 0);
    }

    private void delete(Generator d, int name) {
        if (name != 0) {
            ints.set(JAVA_INT, 0, name);
            d.generate(1, ints);
        }
    }

    private void checkError(String where) {
        int error = GL.glGetError();
        if (error != GL_NO_ERROR && verbose) {
            System.err.println("Prism GL error 0x" + Integer.toHexString(error) + " in " + where);
        }
    }

    private void createQuadObjects() {
        quadVertexArray = gen(GL::glGenVertexArrays);
        GL.glBindVertexArray(quadVertexArray);
        vertexBuffer = streamBuffer(GL_ARRAY_BUFFER, (long) MAX_QUADS * 4 * VERTEX_SIZE);
        // x, y, z, u0, v0, u1, v1
        floatAttribute(0, 3, VERTEX_SIZE, 0);
        floatAttribute(1, 2, VERTEX_SIZE, 12);
        floatAttribute(2, 2, VERTEX_SIZE, 20);
        colorBuffer = streamBuffer(GL_ARRAY_BUFFER, (long) MAX_QUADS * 4 * 4);
        GL.glEnableVertexAttribArray(3);
        GL.glVertexAttribPointer(3, 4, GL_UNSIGNED_BYTE, (byte) 1, 4, 0);
        quadIndexBuffer = gen(GL::glGenBuffers);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, quadIndexBuffer);
        short[] indices = quadIndices();
        GL.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices.length * 2L, MemorySegment.ofArray(indices), GL_STATIC_DRAW);
        GL.glBindVertexArray(0);
        transformBuffer = streamBuffer(GL_UNIFORM_BUFFER, 64);
        uniformBuffer = gen(GL::glGenBuffers);
    }

    /** A buffer of a size, whose data the draws stream into. */
    private int streamBuffer(int target, long size) {
        int buffer = gen(GL::glGenBuffers);
        GL.glBindBuffer(target, buffer);
        GL.glBufferData(target, size, MemorySegment.NULL, GL_STREAM_DRAW);
        return buffer;
    }

    private static void floatAttribute(int index, int size, int stride, long offset) {
        GL.glEnableVertexAttribArray(index);
        GL.glVertexAttribPointer(index, size, GL_FLOAT, (byte) 0, stride, offset);
    }

    /** The indices of the quads: 0 1 2, 1 2 3 for each quad. */
    private static short[] quadIndices() {
        short[] indices = new short[MAX_QUADS * 6];
        for (int q = 0; q < MAX_QUADS; q++) {
            short v = (short) (q * 4);
            int base = q * 6;
            indices[base] = v;
            indices[base + 1] = (short) (v + 1);
            indices[base + 2] = (short) (v + 2);
            indices[base + 3] = (short) (v + 1);
            indices[base + 4] = (short) (v + 2);
            indices[base + 5] = (short) (v + 3);
        }
        return indices;
    }

    void destroy() {
        if (context.address() != 0) {
            try {
                makeCurrent();
                deleteObjects();
            } catch (RuntimeException | UnsatisfiedLinkError ignored) {
                // The context is going away
            }
            EGL.eglMakeCurrent(display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
            EGL.eglDestroyContext(display, context);
            context = MemorySegment.NULL;
        }
        if (display.address() != 0) {
            EGL.eglTerminate(display);
            display = MemorySegment.NULL;
        }
        if (gbm.address() != 0) {
            GBM.gbm_device_destroy(gbm);
            gbm = MemorySegment.NULL;
        }
        if (drmFd >= 0) {
            LibC.close(drmFd);
            drmFd = -1;
        }
    }

    /** The samplers, the buffers, the vertex array and the shaders of the device. */
    private void deleteObjects() {
        for (int[] row : samplers) {
            for (int s : row) {
                delete(GL::glDeleteSamplers, s);
            }
        }
        delete(GL::glDeleteVertexArrays, quadVertexArray);
        for (int buffer : new int[] {vertexBuffer, colorBuffer, quadIndexBuffer, transformBuffer, uniformBuffer}) {
            delete(GL::glDeleteBuffers, buffer);
        }
        for (int program : phongPrograms.values()) {
            GL.glDeleteProgram(program);
        }
        for (int shader : new int[] {passThroughVertex, phongVertex}) {
            if (shader != 0) {
                GL.glDeleteShader(shader);
            }
        }
    }

    String getDeviceName() {
        return deviceName;
    }

    int getMaxTextureSize() {
        return maxTextureSize;
    }

    boolean isExportSupported() {
        return exportSupported;
    }

    int getMSAASamples() {
        return msaaSamples;
    }

    boolean is3DSupported() {
        return true;
    }

    // ---------------------------------------------------------------------
    // Shaders

    /**
     * Compiles a shader, or returns 0 and prints the log.
     */
    int compile(int type, String source, String name) {
        int s = GL.glCreateShader(type);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment strings = a.allocateFrom(ADDRESS, a.allocateFrom(source));
            GL.glShaderSource(s, 1, strings, MemorySegment.NULL);
            GL.glCompileShader(s);
            MemorySegment status = a.allocate(JAVA_INT);
            GL.glGetShaderiv(s, GL_COMPILE_STATUS, status);
            if (status.get(JAVA_INT, 0) == 0) {
                GL.glGetShaderiv(s, GL_INFO_LOG_LENGTH, status);
                int length = Math.max(status.get(JAVA_INT, 0), 1);
                MemorySegment log = a.allocate(length);
                GL.glGetShaderInfoLog(s, length, MemorySegment.NULL, log);
                System.err.println("Prism GL: cannot compile " + name + ": " + log.getString(0));
                GL.glDeleteShader(s);
                return 0;
            }
        }
        return s;
    }

    private int link(int vertex, int fragment, String name) {
        int p = GL.glCreateProgram();
        GL.glAttachShader(p, vertex);
        GL.glAttachShader(p, fragment);
        GL.glLinkProgram(p);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment status = a.allocate(JAVA_INT);
            GL.glGetProgramiv(p, GL_LINK_STATUS, status);
            if (status.get(JAVA_INT, 0) == 0) {
                GL.glGetProgramiv(p, GL_INFO_LOG_LENGTH, status);
                int length = Math.max(status.get(JAVA_INT, 0), 1);
                MemorySegment log = a.allocate(length);
                GL.glGetProgramInfoLog(p, length, MemorySegment.NULL, log);
                System.err.println("Prism GL: cannot link " + name + ": " + log.getString(0));
                GL.glDeleteProgram(p);
                return 0;
            }
        }
        return p;
    }

    /**
     * Links a fragment shader of the JSL compiler with the vertex shader of
     * the quads.
     */
    int createQuadProgram(String fragmentSource, String name) {
        makeCurrent();
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource, name);
        if (fragment == 0) {
            return 0;
        }
        int program = link(passThroughVertex, fragment, name);
        GL.glDeleteShader(fragment);
        return program;
    }

    void deleteProgram(int program) {
        if (program != 0) {
            makeCurrent();
            if (boundProgram == program) {
                boundProgram = -1;
            }
            GL.glDeleteProgram(program);
        }
    }

    // ---------------------------------------------------------------------
    // State

    void setRenderTarget(GLImage img, boolean depth) {
        makeCurrent();
        target = img;
        depthTest = depth;
        if (depth && img != null && !img.hasDepth()) {
            createDepth(img);
        }
        // The context resets its clip state with the target and sets it again if needed
        clipEnabled = false;
    }

    void setTransform(float[] matrix) {
        MemorySegment.copy(matrix, 0, transform, JAVA_FLOAT, 0, 16);
    }

    void setClipRect(int x, int y, int width, int height) {
        clipEnabled = true;
        clipX = x;
        clipY = y;
        clipW = Math.max(width, 0);
        clipH = Math.max(height, 0);
    }

    void resetClipRect() {
        clipEnabled = false;
    }

    void setCompositeMode(int mode) {
        if (mode >= 0 && mode < COMP_COUNT) {
            compositeMode = mode;
        }
    }

    void setShader(GLShader s) {
        shader = s;
    }

    GLShader getShader() {
        return shader;
    }

    void setTexture(int unit, GLImage img, boolean linear, int wrap) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            return;
        }
        textures[unit] = img;
        if (wrap < 0 || wrap >= WRAP_COUNT) {
            wrap = WRAP_CLAMP_TO_EDGE;
        }
        textureSamplers[unit] = samplers[linear ? 1 : 0][wrap];
    }

    private void bindFramebuffer(int framebuffer) {
        if (boundFramebuffer != framebuffer) {
            GL.glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            boundFramebuffer = framebuffer;
        }
    }

    private void bindTarget() {
        bindFramebuffer(target.framebuffer);
        GL.glViewport(0, 0, target.width, target.height);
    }

    private void useProgram(int program) {
        if (boundProgram != program) {
            GL.glUseProgram(program);
            boundProgram = program;
        }
    }

    private void setBlend(int mode) {
        if (blendState == mode) {
            return;
        }
        blendState = mode;
        switch (mode) {
            case COMP_SRC -> GL.glDisable(GL_BLEND);
            case COMP_CLEAR -> {
                GL.glEnable(GL_BLEND);
                GL.glBlendFunc(GL_ZERO, GL_ZERO);
            }
            case COMP_DSTOUT -> {
                GL.glEnable(GL_BLEND);
                GL.glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
            }
            case COMP_ADD -> {
                GL.glEnable(GL_BLEND);
                GL.glBlendFunc(GL_ONE, GL_ONE);
            }
            default -> {
                GL.glEnable(GL_BLEND);
                GL.glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            }
        }
    }

    private void setScissor(boolean clip) {
        int x0 = 0;
        int y0 = 0;
        int x1 = target.width;
        int y1 = target.height;
        if (clip && clipEnabled) {
            x0 = Math.max(clipX, 0);
            y0 = Math.max(clipY, 0);
            x1 = Math.min(clipX + clipW, target.width);
            y1 = Math.min(clipY + clipH, target.height);
        }
        if (!scissorState) {
            GL.glEnable(GL_SCISSOR_TEST);
            scissorState = true;
        }
        GL.glScissor(x0, y0, Math.max(x1 - x0, 0), Math.max(y1 - y0, 0));
    }

    private boolean scissorEmpty(boolean clip) {
        return clip && clipEnabled && (clipW == 0 || clipH == 0);
    }

    private void setDrawState(boolean clip, int cull, boolean wireframe) {
        setScissor(clip);
        int depth = depthTest && target.hasDepth() ? 1 : 0;
        if (depth != depthState) {
            if (depth == 1) {
                GL.glEnable(GL_DEPTH_TEST);
                GL.glDepthMask((byte) 1);
            } else {
                GL.glDisable(GL_DEPTH_TEST);
                GL.glDepthMask((byte) 0);
            }
            depthState = depth;
        }
        if (cull != cullState) {
            if (cull == CULL_NONE) {
                GL.glDisable(GL_CULL_FACE);
            } else {
                GL.glEnable(GL_CULL_FACE);
                GL.glCullFace(cull == CULL_BACK ? GL_BACK : GL_FRONT);
            }
            cullState = cull;
        }
        if (wireframe != wireframeState) {
            GL.glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_LINE : GL_FILL);
            wireframeState = wireframe;
        }
    }

    private void bindTextures(GLImage[] images, int[] imageSamplers) {
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            GLImage img = images[i];
            GL.glActiveTexture(GL_TEXTURE0 + FIRST_SAMPLER_BINDING + i);
            GL.glBindTexture(GL_TEXTURE_2D, img != null && !img.disposed ? img.texture : 0);
            GL.glBindSampler(FIRST_SAMPLER_BINDING + i, imageSamplers[i]);
        }
    }

    // ---------------------------------------------------------------------
    // Drawing

    void drawQuads(float[] coords, byte[] colors, int numVertices) {
        GLShader s = shader;
        if (s == null || target == null || numVertices < 4 || scissorEmpty(true)) {
            return;
        }
        int program = s.getProgram();
        if (program == 0) {
            return;
        }
        makeCurrent();
        bindTarget();
        useProgram(program);
        setBlend(compositeMode);
        setDrawState(true, CULL_NONE, false);
        bindUniforms(s);
        bindTextures(textures, textureSamplers);
        int numQuads = Math.min(numVertices / 4, MAX_QUADS);
        GL.glBindVertexArray(quadVertexArray);
        streamData(vertexBuffer, (long) numQuads * 4 * VERTEX_SIZE, MemorySegment.ofArray(coords));
        streamData(colorBuffer, numQuads * 4 * 4L, MemorySegment.ofArray(colors));
        GL.glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, 0);
        GL.glBindVertexArray(0);
        statDraws++;
    }

    /** The transform, and the uniform block of the shader if it has one. */
    private void bindUniforms(GLShader s) {
        GL.glBindBuffer(GL_UNIFORM_BUFFER, transformBuffer);
        GL.glBufferSubData(GL_UNIFORM_BUFFER, 0, 64, transform);
        GL.glBindBufferBase(GL_UNIFORM_BUFFER, TRANSFORM_BINDING, transformBuffer);
        int blockSize = s.getBlockSize();
        if (blockSize > 0) {
            GL.glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
            GL.glBufferData(GL_UNIFORM_BUFFER, blockSize, s.getUniforms(), GL_STREAM_DRAW);
            GL.glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BINDING, uniformBuffer);
        }
    }

    private static void streamData(int buffer, long size, MemorySegment data) {
        GL.glBindBuffer(GL_ARRAY_BUFFER, buffer);
        GL.glBufferSubData(GL_ARRAY_BUFFER, 0, size, data);
    }

    void clear(float r, float g, float b, float a, boolean clearDepth) {
        if (target == null || scissorEmpty(true)) {
            return;
        }
        makeCurrent();
        bindTarget();
        setScissor(true);
        GL.glClearColor(r, g, b, a);
        int mask = GL_COLOR_BUFFER_BIT;
        if (clearDepth && target.hasDepth()) {
            GL.glDepthMask((byte) 1);
            depthState = -1;
            GL.glClearDepth(1.0);
            mask |= GL_DEPTH_BUFFER_BIT;
        }
        GL.glClear(mask);
    }

    /**
     * Submits the rendering to the GPU.
     */
    void flush() {
        makeCurrent();
        GL.glFlush();
    }

    /**
     * Waits for the rendering.
     */
    void finish() {
        makeCurrent();
        GL.glFinish();
    }

    private int framebufferOf(GLImage img) {
        if (img.framebuffer == 0) {
            img.framebuffer = gen(GL::glGenFramebuffers);
            bindFramebuffer(img.framebuffer);
            GL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    img.samples > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D, img.texture, 0);
        }
        return img.framebuffer;
    }

    void blit(GLImage src, GLImage dst, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        if (src == null || dst == null || src == dst) {
            return;
        }
        makeCurrent();
        boolean exact = sx0 == dx0 && sy0 == dy0 && sx1 == dx1 && sy1 == dy1
                && sx1 > sx0 && sy1 > sy0 && dst.samples == 1;
        if (src.samples > 1 && !exact) {
            blitThroughResolved(src, dst, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            return;
        }
        GL.glBindFramebuffer(GL_READ_FRAMEBUFFER, framebufferOf(src));
        GL.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebufferOf(dst));
        boundFramebuffer = -1;
        // The scissor applies to blits
        GL.glDisable(GL_SCISSOR_TEST);
        scissorState = false;
        boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0) && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0);
        GL.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, GL_COLOR_BUFFER_BIT,
                sameSize || src.samples > 1 ? GL_NEAREST : GL_LINEAR);
    }

    /** Resolves a multisampled image into a temporary one, then scales or moves it. */
    private void blitThroughResolved(GLImage src, GLImage dst, int sx0, int sy0, int sx1, int sy1,
                                     int dx0, int dy0, int dx1, int dy1) {
        GLImage resolved = createImage(GLImage.FORMAT_BGRA8, src.width, src.height, 1, true, false);
        if (resolved == null) {
            return;
        }
        blit(src, resolved, 0, 0, src.width, src.height, 0, 0, src.width, src.height);
        blit(resolved, dst, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
        resolved.dispose();
    }

    /**
     * Copies a render target into an exported image and makes the frame
     * readable by the compositor: through a sync file attached to the
     * dma-buf, or by waiting for the GPU.
     */
    void copyToExported(GLImage src, GLImage dst, int width, int height) {
        int w = Math.min(Math.min(width, src.width), dst.width);
        int h = Math.min(Math.min(height, src.height), dst.height);
        blit(src, dst, 0, 0, w, h, 0, 0, w, h);
        if (!presentWithSyncFile(dst)) {
            GL.glFinish();
        } else {
            GL.glFlush();
        }
        if (stats) {
            System.err.printf("Prism GL frame: draws %d uploads %d%n", statDraws, statUploads);
            statDraws = statUploads = 0;
        }
    }

    private boolean presentWithSyncFile(GLImage exported) {
        if (!fenceSupported || exported.fd < 0) {
            return false;
        }
        int syncFd = createSyncFile();
        if (syncFd < 0) {
            fenceSupported = false;
            return false;
        }
        syncRequest.set(JAVA_INT, 0, DMA_BUF_SYNC_WRITE);
        syncRequest.set(JAVA_INT, 4, syncFd);
        int result = LibC.ioctl(exported.fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, syncRequest);
        LibC.close(syncFd);
        if (result != 0) {
            log("cannot attach the sync file to the dma-buf, waiting for the GPU");
            fenceSupported = false;
            return false;
        }
        return true;
    }

    /**
     * A sync file of the commands submitted so far, through a native fence
     * of EGL, or -1.
     */
    private int createSyncFile() {
        MemorySegment sync = EGLX.eglCreateSyncKHR(display, EGL_SYNC_NATIVE_FENCE_ANDROID, eglNone);
        if (sync.address() == 0) {
            return -1;
        }
        try {
            // The fence is created when the commands are flushed
            GL.glFlush();
            return EGLX.eglDupNativeFenceFDANDROID(display, sync);
        } finally {
            EGLX.eglDestroySyncKHR(display, sync);
        }
    }

    // ---------------------------------------------------------------------
    // Images

    /**
     * Creates an image, or returns null if it cannot be created. Exported
     * images are linear ARGB8888 buffers of the render node.
     */
    GLImage createImage(int texFormat, int width, int height, int samples, boolean renderTarget, boolean exportable) {
        makeCurrent();
        GLImage img = new GLImage(this, texFormat, width, height, samples, renderTarget, exportable);
        if (exportable && !exportSupported) {
            return null;
        }
        boolean created = exportable ? createExported(img) : createTexture(img);
        if (!created || ((renderTarget || exportable) && !hasCompleteFramebuffer(img))
                || GL.glGetError() != GL_NO_ERROR) {
            // Releases what was created: the texture, or the buffer, its fd and its EGL image
            destroyImage(img);
            return null;
        }
        return img;
    }

    /** The texture of an image, multisampled or with the format of the image. */
    private boolean createTexture(GLImage img) {
        img.texture = gen(GL::glGenTextures);
        if (img.samples > 1) {
            GL.glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, img.texture);
            GL.glTexStorage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, img.samples, GL_RGBA8, img.width, img.height,
                    (byte) 1);
            GL.glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, 0);
            return true;
        }
        GL.glBindTexture(GL_TEXTURE_2D, img.texture);
        GL.glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat(img.texFormat), img.width, img.height);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
        // One channel, read as alpha or as gray
        if (img.texFormat == GLImage.FORMAT_ALPHA8) {
            GL.glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_RGBA, swizzleAlpha);
        } else if (img.texFormat == GLImage.FORMAT_GRAY8) {
            GL.glTexParameteriv(GL_TEXTURE_2D, GL_TEXTURE_SWIZZLE_RGBA, swizzleGray);
        }
        GL.glBindTexture(GL_TEXTURE_2D, 0);
        return true;
    }

    private static int internalFormat(int texFormat) {
        return switch (texFormat) {
            case GLImage.FORMAT_ALPHA8, GLImage.FORMAT_GRAY8 -> GL_R8;
            case GLImage.FORMAT_RGBA32F -> GL_RGBA32F;
            default -> GL_RGBA8;
        };
    }

    private boolean hasCompleteFramebuffer(GLImage img) {
        int fb = framebufferOf(img);
        int status = GL.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            log("incomplete framebuffer 0x" + Integer.toHexString(status) + " for " + img.width + "x" + img.height);
            return false;
        }
        return fb != 0;
    }

    /**
     * A linear buffer of the render node, exported as a dma-buf and imported
     * back as an EGL image, which a texture is bound to.
     */
    private boolean createExported(GLImage img) {
        MemorySegment bo = GBM.gbm_bo_create(gbm, img.width, img.height, DRM_FORMAT_ARGB8888,
                GBM_BO_USE_RENDERING | GBM_BO_USE_LINEAR);
        if (bo.address() == 0) {
            log("cannot allocate an exported buffer of " + img.width + "x" + img.height);
            return false;
        }
        img.bo = bo;
        img.fd = GBM.gbm_bo_get_fd(bo);
        img.stride = GBM.gbm_bo_get_stride(bo);
        img.fourcc = DRM_FORMAT_ARGB8888;
        if (img.fd < 0) {
            return false;
        }
        img.eglImage = importDmaBuf(img);
        if (img.eglImage.address() == 0) {
            log("cannot import the exported buffer (EGL error 0x" + Integer.toHexString(EGL.eglGetError()) + ")");
            return false;
        }
        img.texture = gen(GL::glGenTextures);
        GL.glBindTexture(GL_TEXTURE_2D, img.texture);
        GL.glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, img.eglImage);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        GL.glBindTexture(GL_TEXTURE_2D, 0);
        return true;
    }

    private MemorySegment importDmaBuf(GLImage img) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment attribs = a.allocateFrom(JAVA_INT,
                    EGL_WIDTH, img.width, EGL_HEIGHT, img.height,
                    EGL_LINUX_DRM_FOURCC_EXT, DRM_FORMAT_ARGB8888,
                    EGL_DMA_BUF_PLANE0_FD_EXT, img.fd,
                    EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
                    EGL_DMA_BUF_PLANE0_PITCH_EXT, img.stride,
                    EGL_NONE);
            return EGLX.eglCreateImageKHR(display, MemorySegment.NULL, EGL_LINUX_DMA_BUF_EXT,
                    MemorySegment.NULL, attribs);
        }
    }

    private void createDepth(GLImage img) {
        img.depthRenderbuffer = gen(GL::glGenRenderbuffers);
        GL.glBindRenderbuffer(GL_RENDERBUFFER, img.depthRenderbuffer);
        GL.glRenderbufferStorageMultisample(GL_RENDERBUFFER, img.samples > 1 ? img.samples : 0,
                GL_DEPTH_COMPONENT24, img.width, img.height);
        GL.glBindRenderbuffer(GL_RENDERBUFFER, 0);
        bindFramebuffer(framebufferOf(img));
        GL.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, img.depthRenderbuffer);
        // The target was cleared before it had a depth buffer: clear the new one
        GL.glDisable(GL_SCISSOR_TEST);
        scissorState = false;
        GL.glDepthMask((byte) 1);
        depthState = -1;
        GL.glClearDepth(1.0);
        GL.glClear(GL_DEPTH_BUFFER_BIT);
    }

    void destroyImage(GLImage img) {
        if (context.address() == 0) {
            return;
        }
        makeCurrent();
        if (target == img) {
            target = null;
        }
        deleteGLObjects(img);
        releaseExport(img);
    }

    /** The framebuffer, the depth buffer and the texture of an image. */
    private void deleteGLObjects(GLImage img) {
        if (img.framebuffer != 0) {
            if (boundFramebuffer == img.framebuffer) {
                boundFramebuffer = -1;
            }
            delete(GL::glDeleteFramebuffers, img.framebuffer);
            img.framebuffer = 0;
        }
        delete(GL::glDeleteRenderbuffers, img.depthRenderbuffer);
        img.depthRenderbuffer = 0;
        delete(GL::glDeleteTextures, img.texture);
        img.texture = 0;
    }

    /** The EGL image, the dma-buf and the buffer of an exported image. */
    private void releaseExport(GLImage img) {
        if (img.eglImage.address() != 0) {
            EGLX.eglDestroyImageKHR(display, img.eglImage);
            img.eglImage = MemorySegment.NULL;
        }
        if (img.fd >= 0) {
            LibC.close(img.fd);
            img.fd = -1;
        }
        if (img.bo.address() != 0) {
            GBM.gbm_bo_destroy(img.bo);
            img.bo = MemorySegment.NULL;
        }
    }

    private int uploadFormat(GLImage img) {
        return switch (img.texFormat) {
            case GLImage.FORMAT_ALPHA8, GLImage.FORMAT_GRAY8 -> GL_RED;
            case GLImage.FORMAT_RGBA32F -> GL_RGBA;
            default -> GL_BGRA;
        };
    }

    void updateImage(GLImage img, MemorySegment src, int dstx, int dsty, int srcx, int srcy,
                     int width, int height, int stride) {
        if (width <= 0 || height <= 0 || img.samples > 1) {
            return;
        }
        makeCurrent();
        if (stride % img.bytesPerPixel != 0) {
            // Rows that are not a whole number of pixels: upload them packed
            int rowBytes = width * img.bytesPerPixel;
            long base = (long) srcy * stride + (long) srcx * img.bytesPerPixel;
            uploadRect(img, packRows(src, base, stride, rowBytes, height), dstx, dsty, 0, 0, width, height, width);
        } else {
            uploadRect(img, src, dstx, dsty, srcx, srcy, width, height, stride / img.bytesPerPixel);
        }
        statUploads++;
    }

    private static MemorySegment packRows(MemorySegment src, long base, int stride, int rowBytes, int height) {
        MemorySegment packed = MemorySegment.ofArray(new byte[rowBytes * height]);
        for (int y = 0; y < height; y++) {
            MemorySegment.copy(src, base + (long) y * stride, packed, (long) y * rowBytes, rowBytes);
        }
        return packed;
    }

    /** Uploads a rectangle of pixels whose rows are rowLength pixels apart. */
    private void uploadRect(GLImage img, MemorySegment pixels, int dstx, int dsty, int srcx, int srcy,
                            int width, int height, int rowLength) {
        GL.glBindTexture(GL_TEXTURE_2D, img.texture);
        GL.glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        GL.glPixelStorei(GL_UNPACK_ROW_LENGTH, rowLength);
        GL.glPixelStorei(GL_UNPACK_SKIP_PIXELS, srcx);
        GL.glPixelStorei(GL_UNPACK_SKIP_ROWS, srcy);
        GL.glTexSubImage2D(GL_TEXTURE_2D, 0, dstx, dsty, width, height, uploadFormat(img),
                img.texFormat == GLImage.FORMAT_RGBA32F ? GL_FLOAT : GL_UNSIGNED_BYTE, pixels);
        GL.glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        GL.glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
        GL.glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
        GL.glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Reads B, G, R, A bytes, which are little-endian INT_ARGB_PRE values.
     */
    boolean readImage(GLImage img, MemorySegment dst, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        makeCurrent();
        GLImage source = img;
        if (img.samples > 1) {
            source = createImage(GLImage.FORMAT_BGRA8, img.width, img.height, 1, true, false);
            if (source == null) {
                return false;
            }
            blit(img, source, 0, 0, img.width, img.height, 0, 0, img.width, img.height);
        }
        bindFramebuffer(framebufferOf(source));
        GL.glPixelStorei(GL_PACK_ALIGNMENT, 1);
        GL.glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        GL.glReadPixels(x, y, width, height, GL_BGRA, GL_UNSIGNED_BYTE, dst);
        boolean ok = GL.glGetError() == GL_NO_ERROR;
        if (source != img) {
            source.dispose();
        }
        return ok;
    }

    // ---------------------------------------------------------------------
    // 3D

    static final class MeshBuffers {
        int vertexArray;
        int vertexBuffer;
        int indexBuffer;
        int indexType;
        int indexCount;
    }

    MeshBuffers createMeshBuffers(float[] vertices, int vertexCount, Object indices, int indexCount) {
        if (vertexCount <= 0 || indexCount <= 0) {
            return null;
        }
        makeCurrent();
        MeshBuffers mesh = new MeshBuffers();
        mesh.vertexArray = gen(GL::glGenVertexArrays);
        GL.glBindVertexArray(mesh.vertexArray);
        mesh.vertexBuffer = gen(GL::glGenBuffers);
        GL.glBindBuffer(GL_ARRAY_BUFFER, mesh.vertexBuffer);
        GL.glBufferData(GL_ARRAY_BUFFER, vertexCount * 4L, MemorySegment.ofArray(vertices), GL_STATIC_DRAW);
        // Position, texture coordinates and the tangent frame as a quaternion
        int stride = MESH_VERTEX_FLOATS * 4;
        floatAttribute(0, 3, stride, 0);
        floatAttribute(1, 2, stride, 12);
        floatAttribute(2, 4, stride, 20);
        mesh.indexBuffer = gen(GL::glGenBuffers);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
        boolean shortIndices = indices instanceof short[];
        GL.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexCount * (shortIndices ? 2L : 4L), indexData(indices),
                GL_STATIC_DRAW);
        GL.glBindVertexArray(0);
        mesh.indexType = shortIndices ? GL_UNSIGNED_SHORT : GL_UNSIGNED_INT;
        mesh.indexCount = indexCount;
        if (GL.glGetError() != GL_NO_ERROR) {
            destroyMeshBuffers(mesh);
            return null;
        }
        return mesh;
    }

    private static MemorySegment indexData(Object indices) {
        return switch (indices) {
            case short[] s -> MemorySegment.ofArray(s);
            case int[] i -> MemorySegment.ofArray(i);
            default -> throw new IllegalArgumentException("Indices must be short[] or int[]");
        };
    }

    void destroyMeshBuffers(MeshBuffers mesh) {
        if (mesh == null || context.address() == 0) {
            return;
        }
        makeCurrent();
        delete(GL::glDeleteVertexArrays, mesh.vertexArray);
        delete(GL::glDeleteBuffers, mesh.vertexBuffer);
        delete(GL::glDeleteBuffers, mesh.indexBuffer);
        mesh.vertexArray = 0;
    }

    /** Loads the Phong shaders; returns whether 3D rendering is possible. */
    boolean initPhong(String vertexSource, String fragmentSource) {
        if (phongVertex != 0) {
            return true;
        }
        if (vertexSource == null || fragmentSource == null) {
            return false;
        }
        makeCurrent();
        phongVertex = compile(GL_VERTEX_SHADER, vertexSource, "Phong.vert");
        phongVertexSource = vertexSource;
        phongFragmentSource = fragmentSource;
        return phongVertex != 0;
    }

    private static final String[] PHONG_CONSTANTS = {"DIFFUSE_MODE", "SPECULAR_MODE", "SELF_ILLUM", "BUMP",
        "NUM_LIGHTS"};

    /**
     * The Phong program of a material and a number of lights, whose
     * constants are defined after the version of the shader.
     */
    private int phongProgram(int[] variant) {
        int key = 0;
        for (int v : variant) {
            key = key * 4 + v;
        }
        Integer program = phongPrograms.get(key);
        if (program == null) {
            StringBuilder defines = new StringBuilder();
            for (int i = 0; i < variant.length; i++) {
                boolean flag = i == 2 || i == 3;
                defines.append("#define ").append(PHONG_CONSTANTS[i]).append(' ')
                        .append(flag ? (variant[i] != 0 ? "true" : "false") : String.valueOf(variant[i])).append('\n');
            }
            // After the #version line, which follows the license comment
            int eol = phongFragmentSource.indexOf('\n', phongFragmentSource.indexOf("#version")) + 1;
            String source = phongFragmentSource.substring(0, eol) + defines + phongFragmentSource.substring(eol);
            int fragment = compile(GL_FRAGMENT_SHADER, source, "Phong.frag " + key);
            program = fragment == 0 ? 0 : link(phongVertex, fragment, "Phong " + key);
            if (fragment != 0) {
                GL.glDeleteShader(fragment);
            }
            phongPrograms.put(key, program);
        }
        return program;
    }

    /**
     * Draws a mesh with the Phong shader.
     *
     * @param variant the constants of the material and lights
     * @param uniforms the uniform block of the Phong shaders
     * @param maps the diffuse, specular, bump and self-illumination maps, or nulls
     * @param linear whether each map is filtered linearly
     */
    void drawMesh(MeshBuffers mesh, int[] variant, int cull, boolean wireframe, float[] uniforms,
                  GLImage[] maps, boolean[] linear) {
        if (mesh == null || mesh.vertexArray == 0 || target == null || phongVertex == 0) {
            return;
        }
        makeCurrent();
        int program = phongProgram(variant);
        if (program == 0) {
            return;
        }
        bindTarget();
        useProgram(program);
        setBlend(COMP_SRCOVER);
        setDrawState(false, cull, wireframe);
        GL.glBindBuffer(GL_UNIFORM_BUFFER, uniformBuffer);
        GL.glBufferData(GL_UNIFORM_BUFFER, uniforms.length * 4L, MemorySegment.ofArray(uniforms), GL_STREAM_DRAW);
        GL.glBindBufferBase(GL_UNIFORM_BUFFER, UNIFORM_BINDING, uniformBuffer);
        int[] imageSamplers = new int[MAX_TEXTURE_UNITS];
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            imageSamplers[i] = samplers[linear[i] ? 1 : 0][WRAP_REPEAT];
        }
        bindTextures(maps, imageSamplers);
        GL.glBindVertexArray(mesh.vertexArray);
        GL.glDrawElements(GL_TRIANGLES, mesh.indexCount, mesh.indexType, 0);
        GL.glBindVertexArray(0);
        checkError("drawMesh");
        statDraws++;
    }
}
