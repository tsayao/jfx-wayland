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

import com.sun.prism.MeshView;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.es2egl.ES2Native.GL;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.paint.Color;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * The OpenGL context of the ES2 pipeline: the GLContext of the ES2 pipeline,
 * with its native part, GLContext.c, written in Java over the foreign
 * function and memory API.
 * <p>
 * The constants are the values of OpenGL, which the native code translated
 * them to. The quads are drawn from two stream buffers, where the native
 * code pointed OpenGL at the Java arrays. Meshes are objects of their own,
 * and the state that the native structures of the materials and the mesh
 * views kept and nothing read is gone. Called on the render thread only.
 */
final class GLContext {

    // glBlendFunc
    static final int GL_ZERO = 0;
    static final int GL_ONE = 1;
    static final int GL_SRC_COLOR = 0x0300;
    static final int GL_ONE_MINUS_SRC_COLOR = 0x0301;
    static final int GL_SRC_ALPHA = 0x0302;
    static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    static final int GL_DST_ALPHA = 0x0304;
    static final int GL_ONE_MINUS_DST_ALPHA = 0x0305;
    static final int GL_DST_COLOR = 0x0306;
    static final int GL_ONE_MINUS_DST_COLOR = 0x0307;

    // Texture types
    static final int GL_FLOAT = 0x1406;
    static final int GL_UNSIGNED_BYTE = 0x1401;
    static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;
    static final int GL_UNSIGNED_INT_8_8_8_8 = 0x8035;
    static final int GL_UNSIGNED_SHORT_8_8_APPLE = 0x85BA;

    // Pixel formats
    static final int GL_RGBA = 0x1908;
    static final int GL_BGRA = 0x80E1;
    static final int GL_RGB = 0x1907;
    static final int GL_LUMINANCE = 0x1909;
    static final int GL_ALPHA = 0x1906;
    static final int GL_RGBA32F = 0x8814;
    static final int GL_YCBCR_422_APPLE = 0x85B9;

    // Textures
    static final int GL_TEXTURE_2D = 0x0DE1;
    static final int GL_NEAREST = 0x2600;
    static final int GL_LINEAR = 0x2601;
    static final int GL_NEAREST_MIPMAP_NEAREST = 0x2700;
    static final int GL_LINEAR_MIPMAP_LINEAR = 0x2703;

    // glPixelStorei
    static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;

    // Limits of the implementation
    static final int GL_MAX_FRAGMENT_UNIFORM_COMPONENTS = 0x8B49;
    static final int GL_MAX_FRAGMENT_UNIFORM_VECTORS = 0x8DFD;
    static final int GL_MAX_TEXTURE_IMAGE_UNITS = 0x8872;
    static final int GL_MAX_TEXTURE_SIZE = 0x0D33;
    static final int GL_MAX_VERTEX_ATTRIBS = 0x8869;
    static final int GL_MAX_VARYING_COMPONENTS = 0x8B4B;
    static final int GL_MAX_VARYING_VECTORS = 0x8DFC;
    static final int GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS = 0x8B4C;
    static final int GL_MAX_VERTEX_UNIFORM_COMPONENTS = 0x8B4A;
    static final int GL_MAX_VERTEX_UNIFORM_VECTORS = 0x8DFB;

    static final int NUM_MATRIX_ELEMENTS = 16;

    private static final int GL_NO_ERROR = 0;
    private static final int GL_TRUE = 1;
    private static final int GL_FALSE = 0;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_MULTISAMPLE = 0x809D;
    private static final int GL_LEQUAL = 0x0203;
    private static final int GL_FRONT = 0x0404;
    private static final int GL_BACK = 0x0405;
    private static final int GL_FRONT_AND_BACK = 0x0408;
    private static final int GL_CW = 0x0900;
    private static final int GL_LINE = 0x1B01;
    private static final int GL_FILL = 0x1B02;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_DEPTH_BUFFER_BIT = 0x0100;
    private static final int GL_TEXTURE0 = 0x84C0;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_GENERATE_MIPMAP = 0x8191;
    private static final int GL_REPEAT = 0x2901;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int GL_CLAMP_TO_BORDER = 0x812D;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;
    private static final int GL_RENDERBUFFER = 0x8D41;
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_MAX_SAMPLES = 0x8D57;
    private static final int GL_VERTEX_SHADER = 0x8B31;
    private static final int GL_FRAGMENT_SHADER = 0x8B30;
    private static final int GL_COMPILE_STATUS = 0x8B81;
    private static final int GL_LINK_STATUS = 0x8B82;
    private static final int GL_INFO_LOG_LENGTH = 0x8B84;
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;
    private static final int GL_STATIC_DRAW = 0x88E4;
    private static final int GL_STREAM_DRAW = 0x88E0;
    private static final int GL_TRIANGLES = 0x0004;
    private static final int GL_UNSIGNED_SHORT = 0x1403;
    private static final int GL_UNSIGNED_INT = 0x1405;

    // The vertices of the quads: x, y, z, then two pairs of texture coordinates
    private static final int QUAD_FLOATS_PER_VERTEX = 7;
    private static final int QUAD_COORD_STRIDE = QUAD_FLOATS_PER_VERTEX * Float.BYTES;
    private static final int QUAD_COLOR_STRIDE = 4;

    // The vertices of the meshes: position, texture coordinates and normal (a quaternion)
    private static final int MESH_POSITION = 0;
    private static final int MESH_TEXCOORD = 1;
    private static final int MESH_NORMAL = 2;
    private static final int MESH_STRIDE = (3 + 2 + 4) * Float.BYTES;

    private static final int FBO_ID_UNSET = -1;

    /** The buffers of a mesh, in place of the MeshInfo of the native code. */
    static final class Mesh {
        private int vertexBuffer;
        private int indexBuffer;
        private int indexCount;
        private int indexType;
    }

    private final GLFactory factory;
    // Small values passed to OpenGL and read back, on the render thread
    private final Arena arena = Arena.ofAuto();
    private final MemorySegment ints = arena.allocate(JAVA_INT, 4);

    private int maxTextureSize = -1;
    private int maxSampleSize = -1;
    private Boolean nonPowTwoAvailable;
    private Boolean clampToZeroAvailable;

    // The state, to avoid the redundant changes
    private int activeTexUnit;
    private final int[] boundTextures = new int[4];
    private int viewportX;
    private int viewportY;
    private int viewportWidth;
    private int viewportHeight;
    private boolean depthTest;
    private boolean depthWritesEnabled;
    private boolean msaa;
    private boolean scissorEnabled;
    private final float[] clearColor = new float[4];
    private int nativeFBOID = FBO_ID_UNSET;
    private int boundFBO;
    private boolean cullEnabled;
    private int cullFace = GL_BACK;
    private int fillMode = GL_FILL;

    // The quads
    private int quadCoordBuffer;
    private int quadColorBuffer;
    private long quadCoordCapacity;
    private long quadColorCapacity;
    private boolean quadPointersSet;

    GLContext(GLFactory factory) {
        this.factory = factory;
        initState();
    }

    GLFactory getFactory() {
        return factory;
    }

    /** Makes the context current on the calling thread. */
    void makeCurrent() {
        factory.makeCurrent();
    }

    private void initState() {
        GL.glEnable(GL_BLEND);
        GL.glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        depthWritesEnabled = false;
        GL.glDepthMask((byte) GL_FALSE);
        GL.glDisable(GL_DEPTH_TEST);
        GL.glDisable(GL_SCISSOR_TEST);
        scissorEnabled = false;
        GL.glClearColor(0f, 0f, 0f, 0f);
        quadCoordBuffer = genBuffer();
        quadColorBuffer = genBuffer();
    }

    // ---------------------------------------------------------------------
    // Names

    @FunctionalInterface
    private interface Generator {
        void generate(int n, MemorySegment names);
    }

    private int gen(Generator generator) {
        ints.set(JAVA_INT, 0, 0);
        generator.generate(1, ints);
        return ints.get(JAVA_INT, 0);
    }

    private void delete(Generator deleter, int name) {
        if (name != 0) {
            ints.set(JAVA_INT, 0, name);
            deleter.generate(1, ints);
        }
    }

    private int genBuffer() {
        return gen(GL::glGenBuffers);
    }

    private int getInteger(int name) {
        ints.set(JAVA_INT, 0, 0);
        GL.glGetIntegerv(name, ints);
        return ints.get(JAVA_INT, 0);
    }

    // ---------------------------------------------------------------------
    // Textures and framebuffers

    void activeTexture(int texUnit) {
        GL.glActiveTexture(GL_TEXTURE0 + texUnit);
    }

    void bindFBO(int fboID) {
        if (nativeFBOID != fboID) {
            GL.glBindFramebuffer(GL_FRAMEBUFFER, fboID);
            boundFBO = fboID;
            nativeFBOID = fboID;
        }
    }

    void bindTexture(int texID) {
        GL.glBindTexture(GL_TEXTURE_2D, texID);
    }

    void blendFunc(int sFactor, int dFactor) {
        GL.glBlendFunc(sFactor, dFactor);
    }

    boolean canCreateNonPowTwoTextures() {
        if (nonPowTwoAvailable == null) {
            nonPowTwoAvailable = !PrismSettings.forcePow2 && factory.isNPOTSupported();
        }
        return nonPowTwoAvailable;
    }

    boolean canClampToZero() {
        if (clampToZeroAvailable == null) {
            // Desktop OpenGL has GL_CLAMP_TO_BORDER
            clampToZeroAvailable = !PrismSettings.noClampToZero;
        }
        return clampToZeroAvailable;
    }

    void clearBuffers(Color color, boolean clearColor, boolean clearDepth, boolean ignoreScissor) {
        clearBuffers(color.getRedPremult(), color.getGreenPremult(), color.getBluePremult(), color.getAlpha(),
                clearColor, clearDepth, ignoreScissor);
    }

    private void clearBuffers(float r, float g, float b, float a,
                              boolean color, boolean depth, boolean ignoreScissor) {
        boolean suspendScissor = ignoreScissor && scissorEnabled;
        if (suspendScissor) {
            GL.glDisable(GL_SCISSOR_TEST);
        }
        int mask = 0;
        if (color) {
            mask = GL_COLOR_BUFFER_BIT;
            updateClearColor(r, g, b, a);
        }
        if (depth) {
            mask |= GL_DEPTH_BUFFER_BIT;
            clearWithDepthWrites(mask);
        } else {
            GL.glClear(mask);
        }
        if (suspendScissor) {
            GL.glEnable(GL_SCISSOR_TEST);
        }
    }

    private void updateClearColor(float r, float g, float b, float a) {
        if (clearColor[0] != r || clearColor[1] != g || clearColor[2] != b || clearColor[3] != a) {
            GL.glClearColor(r, g, b, a);
            clearColor[0] = r;
            clearColor[1] = g;
            clearColor[2] = b;
            clearColor[3] = a;
        }
    }

    /** Clears the depth buffer, which needs the depth writes. */
    private void clearWithDepthWrites(int mask) {
        if (!depthWritesEnabled) {
            GL.glDepthMask((byte) GL_TRUE);
        }
        GL.glClear(mask);
        if (!depthWritesEnabled) {
            GL.glDepthMask((byte) GL_FALSE);
        }
    }

    int createDepthBuffer(int width, int height, int msaaSamples) {
        return createAndAttachRenderBuffer(width, height, msaaSamples, GL_DEPTH_ATTACHMENT);
    }

    int createRenderBuffer(int width, int height, int msaaSamples) {
        return createAndAttachRenderBuffer(width, height, msaaSamples, GL_COLOR_ATTACHMENT0);
    }

    /** A renderbuffer attached to the bound framebuffer, or 0 if the framebuffer is not complete. */
    private int createAndAttachRenderBuffer(int width, int height, int samples, int attachment) {
        int internalFormat = attachment == GL_DEPTH_ATTACHMENT ? GL_DEPTH_COMPONENT24 : GL_RGBA8;
        int rb = gen(GL::glGenRenderbuffers);
        GL.glBindRenderbuffer(GL_RENDERBUFFER, rb);
        if (samples > 0) {
            GL.glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, internalFormat, width, height);
        } else {
            GL.glRenderbufferStorage(GL_RENDERBUFFER, internalFormat, width, height);
        }
        GL.glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, rb);
        GL.glBindRenderbuffer(GL_RENDERBUFFER, 0);
        if (!isFramebufferComplete()) {
            delete(GL::glDeleteRenderbuffers, rb);
            System.err.println("Error creating render buffer object");
            return 0;
        }
        clearBuffers(0, 0, 0, 0, false, true, true);
        return rb;
    }

    private boolean isFramebufferComplete() {
        int status = GL.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("Framebuffer object is not complete: status 0x" + Integer.toHexString(status));
            return false;
        }
        return true;
    }

    /**
     * Creates a framebuffer and binds it, with the texture as its color
     * attachment when texID is not 0. The framebuffer bound before is not
     * restored.
     *
     * @return the framebuffer, or 0 if it is not complete
     */
    int createFBO(int texID) {
        int fbo = gen(GL::glGenFramebuffers);
        // The binding is not cached: the framebuffer is new
        GL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        boundFBO = fbo;
        nativeFBOID = FBO_ID_UNSET;
        if (texID != 0) {
            GL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texID, 0);
            if (!isFramebufferComplete()) {
                delete(GL::glDeleteFramebuffers, fbo);
                System.err.println("Error creating framebuffer object with TexID " + texID);
                return 0;
            }
            clearBuffers(0, 0, 0, 0, true, false, true);
        }
        return fbo;
    }

    int createTexture(int width, int height) {
        int texID = gen(GL::glGenTextures);
        if (texID == 0) {
            return 0;
        }
        GL.glBindTexture(GL_TEXTURE_2D, texID);
        GL.glGetError();
        GL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                MemorySegment.NULL);
        if (GL.glGetError() != GL_NO_ERROR) {
            delete(GL::glDeleteTextures, texID);
            return 0;
        }
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        return texID;
    }

    void deleteRenderBuffer(int rbID) {
        delete(GL::glDeleteRenderbuffers, rbID);
    }

    void deleteFBO(int fboID) {
        if (fboID != 0 && fboID == nativeFBOID) {
            nativeFBOID = FBO_ID_UNSET;
        }
        delete(GL::glDeleteFramebuffers, fboID);
    }

    void deleteTexture(int texID) {
        delete(GL::glDeleteTextures, texID);
    }

    /**
     * Copies between framebuffers; a destination of 0 is the framebuffer of
     * the current render target. The scissor does not apply.
     */
    void blitFBO(int srcFboID, int dstFboID, int srcX0, int srcY0, int srcX1, int srcY1,
                 int dstX0, int dstY0, int dstX1, int dstY1) {
        if (scissorEnabled) {
            GL.glDisable(GL_SCISSOR_TEST);
        }
        GL.glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFboID);
        GL.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dstFboID == 0 ? boundFBO : dstFboID);
        GL.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1,
                GL_COLOR_BUFFER_BIT, GL_LINEAR);
        GL.glBindFramebuffer(GL_FRAMEBUFFER, boundFBO);
        if (scissorEnabled) {
            GL.glEnable(GL_SCISSOR_TEST);
        }
    }

    int genAndBindTexture() {
        int texID = gen(GL::glGenTextures);
        GL.glBindTexture(GL_TEXTURE_2D, texID);
        boundTextures[activeTexUnit] = texID;
        return texID;
    }

    int getBoundFBO() {
        if (nativeFBOID == FBO_ID_UNSET) {
            nativeFBOID = getInteger(GL_FRAMEBUFFER_BINDING);
        }
        return nativeFBOID;
    }

    int getActiveTextureUnit() {
        return activeTexUnit;
    }

    /** Sets the active texture unit, even when it is already active. */
    void setActiveTextureUnit(int unit) {
        activeTexture(unit);
        activeTexUnit = unit;
    }

    void updateActiveTextureUnit(int unit) {
        if (unit != activeTexUnit) {
            setActiveTextureUnit(unit);
        }
    }

    int getBoundTexture() {
        return boundTextures[activeTexUnit];
    }

    int getBoundTexture(int unit) {
        return boundTextures[unit];
    }

    int getNumBoundTexture() {
        return boundTextures.length;
    }

    /** Binds the texture to the active unit, even when it is already bound. */
    void setBoundTexture(int texID) {
        bindTexture(texID);
        boundTextures[activeTexUnit] = texID;
    }

    void updateBoundTexture(int texID) {
        if (texID != getBoundTexture()) {
            setBoundTexture(texID);
        }
    }

    int getIntParam(int name) {
        return getInteger(name);
    }

    int getSampleSize() {
        int maxSamples = getMaxSampleSize();
        return maxSamples < 2 ? 0 : (maxSamples < 4 ? 2 : 4);
    }

    int getMaxSampleSize() {
        if (maxSampleSize < 0) {
            maxSampleSize = factory.isMultisampleSupported() ? getInteger(GL_MAX_SAMPLES) : 0;
        }
        return maxSampleSize;
    }

    int getMaxTextureSize() {
        if (maxTextureSize < 0) {
            maxTextureSize = getInteger(GL_MAX_TEXTURE_SIZE);
        }
        return maxTextureSize;
    }

    void pixelStorei(int name, int param) {
        GL.glPixelStorei(name, param);
    }

    /**
     * Reads the pixels of the bound framebuffer as BGRA, from the start of
     * the buffer, whatever its position.
     */
    boolean readPixels(Buffer buffer, int x, int y, int w, int h) {
        MemorySegment pixels = switch (buffer) {
            case ByteBuffer b -> MemorySegment.ofBuffer(b.duplicate().clear());
            case IntBuffer b -> MemorySegment.ofBuffer(b.duplicate().clear());
            default -> throw new IllegalArgumentException("readPixels: the pixel buffer type is not supported: "
                    + buffer);
        };
        if (w <= 0 || h <= 0 || pixels.byteSize() / 4 / w < h) {
            System.err.println("readPixels: the pixel buffer is too small for " + w + "x" + h);
            return false;
        }
        GL.glReadPixels(x, y, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
        return true;
    }

    void scissorTest(boolean enable, int x, int y, int w, int h) {
        if (enable) {
            if (!scissorEnabled) {
                GL.glEnable(GL_SCISSOR_TEST);
                scissorEnabled = true;
            }
            GL.glScissor(x, y, w, h);
        } else if (scissorEnabled) {
            GL.glDisable(GL_SCISSOR_TEST);
            scissorEnabled = false;
        }
    }

    void texParamsMinMax(int filter, boolean useMipmap) {
        int min = filter;
        if (useMipmap) {
            min = filter == GL_LINEAR ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST;
        }
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, min);
    }

    /** The pixels of a buffer, from its position, or NULL. */
    private static MemorySegment pixelsOf(Buffer pixels) {
        return pixels == null ? MemorySegment.NULL : MemorySegment.ofBuffer(pixels);
    }

    boolean texImage2D(int target, int level, int internalFormat, int width, int height, int border,
                       int format, int type, Buffer pixels, boolean useMipmap) {
        GL.glGetError();
        if (useMipmap) {
            GL.glTexParameteri(GL_TEXTURE_2D, GL_GENERATE_MIPMAP, GL_TRUE);
        }
        GL.glTexImage2D(target, level, internalFormat, width, height, border, format, type, pixelsOf(pixels));
        return GL.glGetError() == GL_NO_ERROR;
    }

    void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
                       int format, int type, Buffer pixels) {
        GL.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixelsOf(pixels));
    }

    void updateViewportAndDepthTest(int x, int y, int w, int h, boolean depthTest) {
        if (viewportX != x || viewportY != y || viewportWidth != w || viewportHeight != h) {
            viewportX = x;
            viewportY = y;
            viewportWidth = w;
            viewportHeight = h;
            GL.glViewport(x, y, w, h);
        }
        if (this.depthTest != depthTest) {
            setDepthTest(depthTest);
            this.depthTest = depthTest;
        }
    }

    private void setDepthTest(boolean enable) {
        if (enable) {
            GL.glEnable(GL_DEPTH_TEST);
            GL.glDepthFunc(GL_LEQUAL);
        } else {
            GL.glDisable(GL_DEPTH_TEST);
        }
        GL.glDepthMask((byte) (enable ? GL_TRUE : GL_FALSE));
        depthWritesEnabled = enable;
    }

    void updateMSAAState(boolean msaa) {
        if (this.msaa != msaa) {
            if (msaa) {
                GL.glEnable(GL_MULTISAMPLE);
            } else {
                GL.glDisable(GL_MULTISAMPLE);
            }
            this.msaa = msaa;
        }
    }

    /** The filter of the bound texture. */
    void updateFilterState(int texID, boolean linearFilter) {
        int filter = linearFilter ? GL_LINEAR : GL_NEAREST;
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    }

    /** The wrap mode of the bound texture. */
    void updateWrapState(int texID, WrapMode wrapMode) {
        int mode = switch (wrapMode) {
            // The mode does not matter when the repeat is simulated
            case REPEAT_SIMULATED, REPEAT -> GL_REPEAT;
            // Clamped to the edge for the top and left edges
            case CLAMP_TO_ZERO_SIMULATED, CLAMP_TO_EDGE_SIMULATED, CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE;
            case CLAMP_TO_ZERO -> GL_CLAMP_TO_BORDER;
            case CLAMP_NOT_NEEDED -> 0;
        };
        if (mode != 0) {
            GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, mode);
            GL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, mode);
        }
    }

    void finish() {
        GL.glFinish();
    }

    void flush() {
        GL.glFlush();
    }

    // ---------------------------------------------------------------------
    // Shaders

    /**
     * Compiles a shader.
     *
     * @return the shader, or 0 if it does not compile, after printing the log
     */
    int compileShader(String source, boolean vertex) {
        int shader = GL.glCreateShader(vertex ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER);
        try (Arena a = Arena.ofConfined()) {
            GL.glShaderSource(shader, 1, a.allocateFrom(ADDRESS, a.allocateFrom(source)), MemorySegment.NULL);
        }
        GL.glCompileShader(shader);
        if (shaderParameter(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            System.err.println("Shader compile log: " + infoLog(shaderParameter(shader, GL_INFO_LOG_LENGTH),
                    (size, length, log) -> GL.glGetShaderInfoLog(shader, size, length, log)));
            GL.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private int shaderParameter(int shader, int name) {
        ints.set(JAVA_INT, 0, 0);
        GL.glGetShaderiv(shader, name, ints);
        return ints.get(JAVA_INT, 0);
    }

    private int programParameter(int program, int name) {
        ints.set(JAVA_INT, 0, 0);
        GL.glGetProgramiv(program, name, ints);
        return ints.get(JAVA_INT, 0);
    }

    @FunctionalInterface
    private interface InfoLogReader {
        void read(int size, MemorySegment length, MemorySegment log);
    }

    private static String infoLog(int size, InfoLogReader reader) {
        if (size <= 0) {
            return "(empty)";
        }
        try (Arena a = Arena.ofConfined()) {
            MemorySegment log = a.allocate(size);
            reader.read(size, MemorySegment.NULL, log);
            return log.getString(0);
        }
    }

    /**
     * Links a program of a vertex shader and fragment shaders, with the
     * attributes at the given indices.
     *
     * @return the program, or 0 if it does not link, after deleting the shaders
     */
    int createProgram(int vertexShader, int[] fragmentShaders, String[] attrs, int[] indices) {
        if (fragmentShaders == null) {
            System.err.println("Error: fragmentShaderIDArr is null");
            return 0;
        }
        checkProgramArguments(vertexShader, fragmentShaders, attrs, indices);
        int program = GL.glCreateProgram();
        GL.glAttachShader(program, vertexShader);
        for (int fragmentShader : fragmentShaders) {
            GL.glAttachShader(program, fragmentShader);
        }
        bindAttributes(program, attrs, indices);
        GL.glLinkProgram(program);
        if (programParameter(program, GL_LINK_STATUS) == GL_FALSE) {
            System.err.println("Program link log: " + infoLog(programParameter(program, GL_INFO_LOG_LENGTH),
                    (size, length, log) -> GL.glGetProgramInfoLog(program, size, length, log)));
            disposeShaders(program, vertexShader, fragmentShaders);
            return 0;
        }
        return program;
    }

    private static void checkProgramArguments(int vertexShader, int[] fragmentShaders, String[] attrs,
                                              int[] indices) {
        boolean fragmentShadersSpecified = fragmentShaders.length > 0;
        for (int fragmentShader : fragmentShaders) {
            fragmentShadersSpecified &= fragmentShader != 0;
        }
        if (vertexShader == 0 || !fragmentShadersSpecified) {
            System.err.println("Both vertexShader and fragmentShader(s) must be specified");
        }
        if (attrs.length != indices.length) {
            System.err.println("attrs.length must be equal to index.length");
        }
    }

    private static void bindAttributes(int program, String[] attrs, int[] indices) {
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < Math.min(attrs.length, indices.length); i++) {
                GL.glBindAttribLocation(program, indices[i], a.allocateFrom(attrs[i]));
            }
        }
    }

    void deleteShader(int shader) {
        if (shader != 0) {
            GL.glDeleteShader(shader);
        }
    }

    /** Detaches and deletes the shaders of a program, then the program. */
    void disposeShaders(int program, int vertexShader, int[] fragmentShaders) {
        if (vertexShader != 0) {
            GL.glDetachShader(program, vertexShader);
            GL.glDeleteShader(vertexShader);
        }
        if (fragmentShaders == null) {
            return;
        }
        for (int fragmentShader : fragmentShaders) {
            if (fragmentShader != 0) {
                GL.glDetachShader(program, fragmentShader);
                GL.glDeleteShader(fragmentShader);
            }
        }
        GL.glDeleteProgram(program);
    }

    int getUniformLocation(int program, String name) {
        try (Arena a = Arena.ofConfined()) {
            return GL.glGetUniformLocation(program, a.allocateFrom(name));
        }
    }

    boolean isShaderCompilerSupported() {
        return true;
    }

    void setShaderProgram(int program) {
        GL.glUseProgram(program);
    }

    void uniform1f(int location, float v0) {
        GL.glUniform1f(location, v0);
    }

    void uniform2f(int location, float v0, float v1) {
        GL.glUniform2f(location, v0, v1);
    }

    void uniform3f(int location, float v0, float v1, float v2) {
        GL.glUniform3f(location, v0, v1, v2);
    }

    void uniform4f(int location, float v0, float v1, float v2, float v3) {
        GL.glUniform4f(location, v0, v1, v2, v3);
    }

    void uniform4fv(int location, int count, FloatBuffer value) {
        GL.glUniform4fv(location, count, pixelsOf(value));
    }

    void uniform1i(int location, int v0) {
        GL.glUniform1i(location, v0);
    }

    void uniform2i(int location, int v0, int v1) {
        GL.glUniform2i(location, v0, v1);
    }

    void uniform3i(int location, int v0, int v1, int v2) {
        GL.glUniform3i(location, v0, v1, v2);
    }

    void uniform4i(int location, int v0, int v1, int v2, int v3) {
        GL.glUniform4i(location, v0, v1, v2, v3);
    }

    void uniform4iv(int location, int count, IntBuffer value) {
        GL.glUniform4iv(location, count, pixelsOf(value));
    }

    void uniformMatrix4fv(int location, boolean transpose, float[] values) {
        GL.glUniformMatrix4fv(location, 1, (byte) (transpose ? GL_TRUE : GL_FALSE),
                values == null ? MemorySegment.NULL : MemorySegment.ofArray(values));
    }

    // ---------------------------------------------------------------------
    // Quads

    void enableVertexAttributes() {
        for (int i = 0; i < 4; i++) {
            GL.glEnableVertexAttribArray(i);
        }
    }

    void disableVertexAttributes() {
        for (int i = 0; i < 4; i++) {
            GL.glDisableVertexAttribArray(i);
        }
    }

    /**
     * Draws quads of four vertices, with the bound index buffer. The vertices
     * and their colors are streamed into two buffers.
     */
    void drawIndexedQuads(float[] coords, byte[] colors, int numVertices) {
        int numQuads = numVertices / 4;
        if (numQuads == 0) {
            return;
        }
        quadCoordCapacity = stream(quadCoordBuffer, quadCoordCapacity,
                MemorySegment.ofArray(coords), (long) numVertices * QUAD_COORD_STRIDE);
        quadColorCapacity = stream(quadColorBuffer, quadColorCapacity,
                MemorySegment.ofArray(colors), (long) numVertices * QUAD_COLOR_STRIDE);
        if (!quadPointersSet) {
            setQuadPointers();
        }
        GL.glDrawElements(GL_TRIANGLES, numQuads * 6, GL_UNSIGNED_SHORT, 0);
    }

    /**
     * Uploads the first bytes of the data into a stream buffer, whose storage
     * is renewed each time so that the draws in flight keep theirs.
     *
     * @return the capacity of the buffer
     */
    private static long stream(int buffer, long capacity, MemorySegment data, long size) {
        long newCapacity = Math.max(capacity, data.byteSize());
        GL.glBindBuffer(GL_ARRAY_BUFFER, buffer);
        GL.glBufferData(GL_ARRAY_BUFFER, newCapacity, MemorySegment.NULL, GL_STREAM_DRAW);
        GL.glBufferSubData(GL_ARRAY_BUFFER, 0, Math.min(size, data.byteSize()), data);
        return newCapacity;
    }

    /**
     * Points the attributes of the quads at the stream buffers: the position
     * (0), the color (1) and the two texture coordinates (2 and 3).
     */
    private void setQuadPointers() {
        GL.glBindBuffer(GL_ARRAY_BUFFER, quadCoordBuffer);
        GL.glVertexAttribPointer(0, 3, GL_FLOAT, (byte) GL_FALSE, QUAD_COORD_STRIDE, 0);
        GL.glVertexAttribPointer(2, 2, GL_FLOAT, (byte) GL_FALSE, QUAD_COORD_STRIDE, 3 * Float.BYTES);
        GL.glVertexAttribPointer(3, 2, GL_FLOAT, (byte) GL_FALSE, QUAD_COORD_STRIDE, 5 * Float.BYTES);
        GL.glBindBuffer(GL_ARRAY_BUFFER, quadColorBuffer);
        GL.glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, (byte) GL_TRUE, QUAD_COLOR_STRIDE, 0);
        quadPointersSet = true;
    }

    int createIndexBuffer16(short[] data) {
        int buffer = genBuffer();
        if (buffer != 0) {
            GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
            GL.glBufferData(GL_ELEMENT_ARRAY_BUFFER, (long) data.length * Short.BYTES,
                    MemorySegment.ofArray(data), GL_STATIC_DRAW);
        }
        return buffer;
    }

    void setIndexBuffer(int buffer) {
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, buffer);
    }

    // ---------------------------------------------------------------------
    // 2D and 3D

    void setDeviceParametersFor2D() {
        GL.glBindBuffer(GL_ARRAY_BUFFER, 0);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        GL.glDisableVertexAttribArray(MESH_POSITION);
        GL.glDisableVertexAttribArray(MESH_NORMAL);
        GL.glDisableVertexAttribArray(MESH_TEXCOORD);
        // The meshes pointed the attributes at their buffers
        quadPointersSet = false;
        GL.glEnable(GL_BLEND);
        GL.glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        disableScissor();
        GL.glCullFace(GL_BACK);
        cullFace = GL_BACK;
        GL.glDisable(GL_CULL_FACE);
        cullEnabled = false;
        GL.glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        fillMode = GL_FILL;
    }

    void setDeviceParametersFor3D() {
        GL.glEnable(GL_BLEND);
        GL.glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        disableScissor();
        GL.glEnable(GL_CULL_FACE);
        cullEnabled = true;
        GL.glCullFace(GL_BACK);
        cullFace = GL_BACK;
        // Clockwise faces are the front faces
        GL.glFrontFace(GL_CW);
        GL.glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);
        fillMode = GL_FILL;
    }

    private void disableScissor() {
        if (scissorEnabled) {
            scissorEnabled = false;
            GL.glDisable(GL_SCISSOR_TEST);
        }
    }

    // ---------------------------------------------------------------------
    // Meshes

    Mesh createMesh() {
        Mesh mesh = new Mesh();
        mesh.vertexBuffer = genBuffer();
        mesh.indexBuffer = genBuffer();
        return mesh;
    }

    void releaseMesh(Mesh mesh) {
        delete(GL::glDeleteBuffers, mesh.vertexBuffer);
        delete(GL::glDeleteBuffers, mesh.indexBuffer);
        mesh.vertexBuffer = mesh.indexBuffer = 0;
    }

    boolean buildNativeGeometry(Mesh mesh, float[] vertices, int vertexCount, short[] indices, int indexCount) {
        return indices != null && indexCount <= indices.length
                && buildGeometry(mesh, vertices, vertexCount, MemorySegment.ofArray(indices),
                        (long) indexCount * Short.BYTES, indexCount, GL_UNSIGNED_SHORT);
    }

    boolean buildNativeGeometry(Mesh mesh, float[] vertices, int vertexCount, int[] indices, int indexCount) {
        return indices != null && indexCount <= indices.length
                && buildGeometry(mesh, vertices, vertexCount, MemorySegment.ofArray(indices),
                        (long) indexCount * Integer.BYTES, indexCount, GL_UNSIGNED_INT);
    }

    /** Uploads the vertices (as many floats as vertexCount) and the indices of a mesh. */
    private boolean buildGeometry(Mesh mesh, float[] vertices, int vertexCount, MemorySegment indices,
                                  long indexBytes, int indexCount, int indexType) {
        if (mesh.vertexBuffer == 0 || mesh.indexBuffer == 0 || vertices == null
                || vertexCount < 0 || indexCount < 0 || vertexCount > vertices.length) {
            return false;
        }
        GL.glBindBuffer(GL_ARRAY_BUFFER, mesh.vertexBuffer);
        GL.glBufferData(GL_ARRAY_BUFFER, (long) vertexCount * Float.BYTES, MemorySegment.ofArray(vertices),
                GL_STATIC_DRAW);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
        GL.glBufferData(GL_ELEMENT_ARRAY_BUFFER, indexBytes, indices, GL_STATIC_DRAW);
        mesh.indexCount = indexCount;
        mesh.indexType = indexType;
        GL.glBindBuffer(GL_ARRAY_BUFFER, 0);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        return true;
    }

    /**
     * Draws a mesh with the program in use.
     *
     * @param cullingMode a culling mode of MeshView
     */
    void renderMesh(Mesh mesh, int cullingMode, boolean wireframe) {
        setCulling(cullingMode);
        setFillMode(wireframe ? GL_LINE : GL_FILL);
        GL.glBindBuffer(GL_ARRAY_BUFFER, mesh.vertexBuffer);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, mesh.indexBuffer);
        GL.glEnableVertexAttribArray(MESH_POSITION);
        GL.glEnableVertexAttribArray(MESH_TEXCOORD);
        GL.glEnableVertexAttribArray(MESH_NORMAL);
        GL.glVertexAttribPointer(MESH_POSITION, 3, GL_FLOAT, (byte) GL_FALSE, MESH_STRIDE, 0);
        GL.glVertexAttribPointer(MESH_TEXCOORD, 2, GL_FLOAT, (byte) GL_FALSE, MESH_STRIDE, 3 * Float.BYTES);
        GL.glVertexAttribPointer(MESH_NORMAL, 4, GL_FLOAT, (byte) GL_FALSE, MESH_STRIDE, 5 * Float.BYTES);
        quadPointersSet = false;
        GL.glDrawElements(GL_TRIANGLES, mesh.indexCount, mesh.indexType, 0);
        GL.glDisableVertexAttribArray(MESH_POSITION);
        GL.glDisableVertexAttribArray(MESH_NORMAL);
        GL.glDisableVertexAttribArray(MESH_TEXCOORD);
        GL.glBindBuffer(GL_ARRAY_BUFFER, 0);
        GL.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    private void setCulling(int cullingMode) {
        boolean enable = cullingMode != MeshView.CULL_NONE;
        int face = cullingMode == MeshView.CULL_FRONT ? GL_FRONT : GL_BACK;
        if (enable != cullEnabled) {
            if (enable) {
                GL.glEnable(GL_CULL_FACE);
            } else {
                GL.glDisable(GL_CULL_FACE);
            }
            cullEnabled = enable;
        }
        if (face != cullFace) {
            GL.glCullFace(face);
            cullFace = face;
        }
    }

    private void setFillMode(int mode) {
        if (mode != fillMode) {
            GL.glPolygonMode(GL_FRONT_AND_BACK, mode);
            fillMode = mode;
        }
    }

    void dispose() {
        delete(GL::glDeleteBuffers, quadCoordBuffer);
        delete(GL::glDeleteBuffers, quadColorBuffer);
        quadCoordBuffer = quadColorBuffer = 0;
    }
}
