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

package com.sun.prism.vk;

import com.sun.glass.ui.Screen;
import com.sun.prism.MediaFrame;
import com.sun.prism.Mesh;
import com.sun.prism.MeshView;
import com.sun.prism.MultiTexture;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

class VKResourceFactory extends BaseShaderFactory {

    // Where the shaders and their layouts are, for Prism and Decora
    private static final String[] SHADER_PACKAGES = {
        "/com/sun/prism/vk/glsl/",
        "/com/sun/scenario/effect/impl/hw/vk/glsl/"
    };

    private final VKContext context;
    private final int maxTextureSize;

    VKResourceFactory(Screen screen, VKDevice device) {
        context = new VKContext(screen, this, device);
        context.initState();
        maxTextureSize = context.getMaximumTextureSize();
    }

    VKContext getContext() {
        return context;
    }

    private void checkTextureSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > maxTextureSize || height > maxTextureSize) {
            throw new RuntimeException("Illegal texture dimensions (" + width + "x" + height + ")");
        }
    }

    // ---------------------------------------------------------------------
    // Shaders

    @Override
    public Shader createShader(String pixelShaderName, InputStream pixelShaderCode,
                               Map<String, Integer> samplers, Map<String, Integer> params,
                               int maxTexCoordIndex, boolean isPixcoordUsed, boolean isPerVertexColorUsed) {
        try {
            if (pixelShaderCode != null) {
                pixelShaderCode.close();
            }
            InputStream code = findShaderResource(pixelShaderName, ".frag.spv");
            InputStream layout = findShaderResource(pixelShaderName, ".layout");
            if (code == null || layout == null) {
                throw new InternalError("Vulkan shader not found: " + pixelShaderName);
            }
            try (code; layout) {
                return VKShader.create(context, pixelShaderName, code, layout, isPixcoordUsed);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new UnsupportedOperationException("Failed to create the shader " + pixelShaderName);
        }
    }

    @Override
    public Shader createShader(String shaderName, Map<String, Integer> samplers, Map<String, Integer> params,
                               int maxTexCoordIndex, boolean isPixcoordUsed, boolean isPerVertexColorUsed) {
        return createShader(shaderName, null, samplers, params, maxTexCoordIndex, isPixcoordUsed,
                isPerVertexColorUsed);
    }

    private static InputStream findShaderResource(String name, String suffix) {
        for (String pkg : SHADER_PACKAGES) {
            InputStream in = VKResourceFactory.class.getResourceAsStream(pkg + name + suffix);
            if (in != null) {
                return in;
            }
        }
        return null;
    }

    @Override
    public Shader createStockShader(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Shader name must be non-null");
        }
        try {
            if (PrismSettings.verbose) {
                System.err.println("VKResourceFactory: Prism - createStockShader: " + name);
            }
            InputStream stream = findShaderResource(name, ".frag.spv");
            Class<?> klass = Class.forName("com.sun.prism.shader." + name + "_Loader");
            Method m = klass.getMethod("loadShader", ShaderFactory.class, String.class, InputStream.class);
            return (Shader) m.invoke(null, this, name, stream);
        } catch (Throwable e) {
            e.printStackTrace();
            throw new InternalError("Error loading stock shader " + name);
        }
    }

    // ---------------------------------------------------------------------
    // Textures

    @Override
    public TextureResourcePool<?> getTextureResourcePool() {
        return VKVramPool.getInstance();
    }

    @Override
    public boolean isFormatSupported(PixelFormat format) {
        return switch (format) {
            case BYTE_RGB, BYTE_GRAY, BYTE_ALPHA, BYTE_BGRA_PRE, INT_ARGB_PRE, FLOAT_XYZW,
                 MULTI_YCbCr_420 -> true;
            case BYTE_APPLE_422 -> false;
        };
    }

    @Override
    public int getMaximumTextureSize() {
        return maxTextureSize;
    }

    @Override
    public int getRTTWidth(int w, WrapMode wrapMode) {
        return w;
    }

    @Override
    public int getRTTHeight(int h, WrapMode wrapMode) {
        return h;
    }

    @Override
    public Texture createTexture(PixelFormat formatHint, Usage usageHint, WrapMode wrapMode, int w, int h) {
        return createTexture(formatHint, usageHint, wrapMode, w, h, false);
    }

    @Override
    public Texture createTexture(PixelFormat formatHint, Usage usageHint, WrapMode wrapMode,
                                 int w, int h, boolean useMipmap) {
        if (checkDisposed()) {
            return null;
        }
        if (!isFormatSupported(formatHint)) {
            throw new UnsupportedOperationException("Pixel format " + formatHint + " not supported");
        }
        if (formatHint == PixelFormat.MULTI_YCbCr_420) {
            throw new UnsupportedOperationException("MULTI_YCbCr_420 textures require a MediaFrame");
        }
        checkTextureSize(w, h);
        VKVramPool pool = VKVramPool.getInstance();
        long size = pool.estimateTextureSize(w, h, formatHint);
        if (!pool.prepareForAllocation(size)) {
            return null;
        }
        VKImage image = context.getDevice().createImage(VKTexture.nativeFormat(formatHint), w, h, 1, false, false);
        if (image == null) {
            return null;
        }
        VKTextureResource<VKTextureData> resource =
                new VKTextureResource<>(new VKTextureData(context, image, size));
        return new VKTexture<>(context, resource, formatHint, wrapMode, w, h, 0, 0, w, h, false);
    }

    @Override
    public Texture createTexture(MediaFrame frame) {
        frame.holdFrame();
        try {
            checkTextureSize(frame.getEncodedWidth(), frame.getEncodedHeight());
            if (frame.getPixelFormat() == PixelFormat.MULTI_YCbCr_420) {
                return createYCbCrTexture(frame);
            }
            return createTexture(frame.getPixelFormat(), Usage.DEFAULT, WrapMode.CLAMP_TO_EDGE,
                    frame.getEncodedWidth(), frame.getEncodedHeight());
        } finally {
            frame.releaseFrame();
        }
    }

    /**
     * The luma, chroma blue, chroma red and alpha planes of a YCbCr frame,
     * as alpha textures; the chroma planes are half the size. A frame of
     * three planes gets an opaque alpha plane, which the shader expects.
     */
    private Texture createYCbCrTexture(MediaFrame frame) {
        int texWidth = frame.getEncodedWidth();
        int texHeight = frame.getEncodedHeight();
        MultiTexture tex = new MultiTexture(PixelFormat.MULTI_YCbCr_420, WrapMode.CLAMP_TO_EDGE,
                frame.getWidth(), frame.getHeight());
        for (int index = 0; index < frame.planeCount(); index++) {
            boolean chroma = index == PixelFormat.YCBCR_PLANE_CHROMABLUE || index == PixelFormat.YCBCR_PLANE_CHROMARED;
            Texture plane = alphaTexture(chroma ? texWidth / 2 : texWidth, chroma ? texHeight / 2 : texHeight);
            if (plane == null) {
                tex.dispose();
                return null;
            }
            tex.setTexture(plane, index);
        }
        if (frame.planeCount() == 3) {
            Texture alpha = opaqueAlphaTexture(texWidth, texHeight);
            if (alpha == null) {
                tex.dispose();
                return null;
            }
            tex.setTexture(alpha, 3);
        }
        return tex;
    }

    private Texture alphaTexture(int width, int height) {
        return createTexture(PixelFormat.BYTE_ALPHA, Usage.DYNAMIC, WrapMode.CLAMP_TO_EDGE, width, height);
    }

    private Texture opaqueAlphaTexture(int width, int height) {
        Texture alpha = alphaTexture(width, height);
        if (alpha != null) {
            byte[] opaque = new byte[width * height];
            Arrays.fill(opaque, (byte) 0xff);
            alpha.update(ByteBuffer.wrap(opaque), PixelFormat.BYTE_ALPHA, 0, 0, 0, 0, width, height, width, true);
        }
        return alpha;
    }

    @Override
    public RTTexture createRTTexture(int width, int height, WrapMode wrapMode) {
        return createRTTexture(width, height, wrapMode, false);
    }

    @Override
    public RTTexture createRTTexture(int width, int height, WrapMode wrapMode, boolean msaa) {
        if (checkDisposed()) {
            return null;
        }
        checkTextureSize(width, height);
        VKVramPool pool = VKVramPool.getInstance();
        boolean useMSAA = msaa && context.getDevice().getMSAASamples() > 1;
        long size = pool.estimateRTTextureSize(width, height, false)
                * (useMSAA ? context.getDevice().getMSAASamples() : 1);
        if (!pool.prepareForAllocation(size)) {
            return null;
        }
        return VKRTTexture.create(context, width, height, width, height, wrapMode, useMSAA, size);
    }

    @Override
    public boolean isCompatibleTexture(Texture tex) {
        return tex instanceof VKTexture;
    }

    @Override
    public Presentable createPresentable(PresentableState pState) {
        if (checkDisposed()) {
            return null;
        }
        return new VKSwapChain(context, pState);
    }

    // ---------------------------------------------------------------------
    // 3D

    private void ensurePhong() {
        if (!context.getDevice().initPhong(VKContext.readShader("Phong.vert.spv"),
                VKContext.readShader("Phong.frag.spv"))) {
            throw new UnsupportedOperationException("3D is not supported by this Vulkan device");
        }
    }

    @Override
    public PhongMaterial createPhongMaterial() {
        if (checkDisposed()) {
            return null;
        }
        ensurePhong();
        return VKPhongMaterial.create(context);
    }

    @Override
    public MeshView createMeshView(Mesh mesh) {
        if (checkDisposed()) {
            return null;
        }
        ensurePhong();
        return VKMeshView.create(context, (VKMesh) mesh);
    }

    @Override
    public Mesh createMesh() {
        if (checkDisposed()) {
            return null;
        }
        ensurePhong();
        return VKMesh.create(context);
    }

    @Override
    public void dispose() {
        context.dispose();
        super.dispose();
    }
}
