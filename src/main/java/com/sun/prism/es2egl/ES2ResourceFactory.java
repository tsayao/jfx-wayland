/*
 * Copyright (c) 2009, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.es2egl;

import java.io.InputStream;
import java.util.Map;

import com.sun.glass.ui.Screen;
import com.sun.prism.Image;
import com.sun.prism.MediaFrame;
import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.PhongMaterial;
import com.sun.prism.PixelFormat;
import com.sun.prism.Presentable;
import com.sun.prism.PresentableState;
import com.sun.prism.RTTexture;
import com.sun.prism.Texture;
import com.sun.prism.Texture.Usage;
import com.sun.prism.Texture.WrapMode;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.TextureResourcePool;
import com.sun.prism.impl.ps.BaseShaderFactory;
import com.sun.prism.ps.Shader;
import com.sun.prism.ps.ShaderFactory;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.WeakHashMap;

public class ES2ResourceFactory extends BaseShaderFactory {
    private static final Map<Image,Texture> clampTexCache = new WeakHashMap<>();
    private static final Map<Image,Texture> repeatTexCache = new WeakHashMap<>();
    private static final Map<Image,Texture> mipmapTexCache = new WeakHashMap<>();

    private ES2Context context;
    // Maximum size of the texture
    private final int maxTextureSize;

    ES2ResourceFactory(Screen screen, GLContext glContext) {
        super(clampTexCache, repeatTexCache, mipmapTexCache);
        context = new ES2Context(screen, this, glContext);
        maxTextureSize = computeMaxTextureSize();

        if (PrismSettings.verbose) {
            System.out.println("Non power of two texture support = "
                    + context.getGLContext().canCreateNonPowTwoTextures());
            System.out.println("Maximum number of vertex attributes = "
                    + context.getGLContext().getIntParam(GLContext.GL_MAX_VERTEX_ATTRIBS));
            int maxVUC = context.getGLContext().getIntParam(GLContext.GL_MAX_VERTEX_UNIFORM_COMPONENTS);
            int maxFUC = context.getGLContext().getIntParam(GLContext.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS);
            int maxVC = context.getGLContext().getIntParam(GLContext.GL_MAX_VARYING_COMPONENTS);
            System.out.println("Maximum number of uniform vertex components = " + maxVUC);
            System.out.println("Maximum number of uniform fragment components = " + maxFUC);
            System.out.println("Maximum number of varying components = " + maxVC);
            System.out.println("Maximum number of texture units usable in a vertex shader = "
                    + context.getGLContext().getIntParam(GLContext.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
            System.out.println("Maximum number of texture units usable in a fragment shader = "
                    + context.getGLContext().getIntParam(GLContext.GL_MAX_TEXTURE_IMAGE_UNITS));
        }
    }

    @Override
    public TextureResourcePool getTextureResourcePool() {
        return ES2VramPool.instance;
    }

    @Override
    public Presentable createPresentable(PresentableState pState) {
        return new ES2SwapChain(context, pState);
    }

    @Override
    public boolean isCompatibleTexture(Texture tex) {
        return tex instanceof ES2Texture;
    }

    @Override
    protected boolean canClampToZero() {
        return context.getGLContext().canClampToZero();
    }

    @Override
    protected boolean canRepeat() {
        // Actually, this depends on the size.  It works for pow2 textures...
        return context.getGLContext().canCreateNonPowTwoTextures();
    }

    @Override
    protected boolean canClampToEdge() {
        // Actually, this depends on the size.  It works for pow2 textures...
        return context.getGLContext().canCreateNonPowTwoTextures();
    }

    @Override
    public Texture createTexture(PixelFormat formatHint,
                                 Usage usageHint,
                                 WrapMode wrapMode,
                                 int w, int h)
    {
        return createTexture(formatHint, usageHint, wrapMode, w, h, false);
    }

    @Override
    public Texture createTexture(PixelFormat formatHint, Usage usageHint,
            WrapMode wrapMode, int w, int h, boolean useMipmap) {
        return ES2Texture.create(context, formatHint, wrapMode, w, h, useMipmap);
    }

    @Override
    public Texture createTexture(MediaFrame frame) {
        return ES2Texture.create(context, frame);
    }

    @Override
    public int getRTTWidth(int w, WrapMode wrapMode) {
        return ES2RTTexture.getCompatibleDimension(context, w, wrapMode);
    }

    @Override
    public int getRTTHeight(int h, WrapMode wrapMode) {
        return ES2RTTexture.getCompatibleDimension(context, h, wrapMode);
    }

    @Override
    public RTTexture createRTTexture(int width, int height, WrapMode wrapMode) {
        return createRTTexture(width, height, wrapMode, false);
    }

    @Override
    public RTTexture createRTTexture(int width, int height, WrapMode wrapMode, boolean msaa) {
        return ES2RTTexture.create(context, width, height, wrapMode, msaa);
    }

    @Override
    public boolean isFormatSupported(PixelFormat format) {
        return switch (format) {
            // Desktop OpenGL has BGRA and float textures
            case BYTE_RGB, BYTE_GRAY, BYTE_ALPHA, MULTI_YCbCr_420, BYTE_BGRA_PRE, INT_ARGB_PRE, FLOAT_XYZW -> true;
            case BYTE_APPLE_422 -> context.getGLContext().getFactory().isGLExtensionSupported("GL_APPLE_ycbcr_422");
            default -> false;
        };
    }

    private int computeMaxTextureSize() {
        int size = context.getGLContext().getMaxTextureSize();
        if (PrismSettings.verbose) {
            System.out.println("Maximum supported texture size: " + size);
        }
        if (size > PrismSettings.maxTextureSize) {
            size = PrismSettings.maxTextureSize;
            if (PrismSettings.verbose) {
                System.out.println("Maximum texture size clamped to " + size);
            }
        }
        return size;
    }

    @Override
    public int getMaximumTextureSize() {
        return maxTextureSize;
    }

    @Override
    public Shader createShader(String pixelShaderName,
            InputStream pixelShaderCode,
            Map<String, Integer> samplers,
            Map<String, Integer> params,
            int maxTexCoordIndex,
            boolean isPixcoordUsed,
            boolean isPerVertexColorUsed) {
        // figure out the appropriate vertex shader and minimal set of
        // vertex attributes to enable based on the given parameters
        Map<String, Integer> attributes =
                getVertexAttributes(isPerVertexColorUsed, maxTexCoordIndex);

        // create the combined shader program
        ES2Shader shader;
        String vertexShaderCode =
                createVertexShaderCode(isPerVertexColorUsed, maxTexCoordIndex);
        shader = ES2Shader.createFromSource(context, vertexShaderCode,
                pixelShaderCode, samplers, attributes,
                maxTexCoordIndex, isPixcoordUsed);

        return shader;
    }

    @Override
    public Shader createShader(String shaderName,
            Map<String, Integer> samplers,
            Map<String, Integer> params,
            int maxTexCoordIndex,
            boolean isPixcoordUsed,
            boolean isPerVertexColorUsed) {
        throw new UnsupportedOperationException("Not supported for ES2 pipeline");
    }

    /**
     * The vertex shader of the 2D shaders: the position, and the color and
     * the texture coordinates that the fragment shader uses.
     */
    private static String createVertexShaderCode(boolean includePerVertexColor, int maxTexCoordIndex) {
        StringBuilder attributes = new StringBuilder("attribute vec2 positionAttr;\n");
        StringBuilder varyings = new StringBuilder();
        StringBuilder main = new StringBuilder("""
                void main() {
                    vec4 tmp = vec4(positionAttr, 0, 1);
                    gl_Position = mvpMatrix * tmp;
                """);
        if (includePerVertexColor) {
            passThrough(attributes, varyings, main, "vec4", "colorAttr", "lowp vec4", "perVertexColor");
        }
        for (int i = 0; i <= Math.min(maxTexCoordIndex, 1); i++) {
            passThrough(attributes, varyings, main, "vec2", "texCoord" + i + "Attr", "vec2", "texCoord" + i);
        }
        main.append("}\n");
        return """
                #ifdef GL_ES
                #else
                #define lowp
                #endif
                uniform mat4 mvpMatrix;
                """ + attributes + varyings + main;
    }

    /** An attribute that the vertex shader passes to the fragment shader as a varying. */
    private static void passThrough(StringBuilder attributes, StringBuilder varyings, StringBuilder main,
                                    String attributeType, String attribute, String varyingType, String varying) {
        attributes.append("attribute ").append(attributeType).append(' ').append(attribute).append(";\n");
        varyings.append("varying ").append(varyingType).append(' ').append(varying).append(";\n");
        main.append("    ").append(varying).append(" = ").append(attribute).append(";\n");
    }

    /** The attributes of the vertex shader, at the indices where GLContext streams the quads. */
    private Map<String, Integer> getVertexAttributes(boolean includePerVertexColor, int maxTexCoordIndex) {
        Map<String, Integer> attributes = new HashMap<>();
        attributes.put("positionAttr", 0);
        if (includePerVertexColor) {
            attributes.put("colorAttr", 1);
        }
        if (maxTexCoordIndex >= 0) {
            attributes.put("texCoord0Attr", 2);
        }
        if (maxTexCoordIndex >= 1) {
            attributes.put("texCoord1Attr", 3);
        }
        return attributes;
    }

    @Override
    public Shader createStockShader(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Shader name must be non-null");
        }
        try {
            InputStream stream =
                    ES2ResourceFactory.class.getResourceAsStream(
                    "glsl/" + name + ".frag");
            if (stream == null) {
                throw new InternalError("ES2 shader not found: " + name);
            }
            Class klass =
                    Class.forName("com.sun.prism.shader." + name + "_Loader");
            if (PrismSettings.verbose) {
                System.out.println("ES2ResourceFactory: Prism - createStockShader: " + name + ".frag");
            }
            Method m =
                    klass.getMethod("loadShader", new Class[]{ShaderFactory.class,
                        String.class, InputStream.class});
            return (Shader) m.invoke(null, new Object[]{this, name, stream});
        } catch (Throwable e) {
            e.printStackTrace();
            throw new InternalError("Error loading stock shader " + name);
        }
    }

    @Override
    public void dispose() {
        context.getGLContext().dispose();
    }

    @Override
    public PhongMaterial createPhongMaterial() {
        return ES2PhongMaterial.create(context);
}

    @Override
    public MeshView createMeshView(Mesh mesh) {
        return ES2MeshView.create(context, (ES2Mesh) mesh);
    }

    @Override
    public Mesh createMesh() {
        return ES2Mesh.create(context);
    }
}
