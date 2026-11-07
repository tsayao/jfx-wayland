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

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Vec3d;
import com.sun.javafx.geom.transform.Affine3D;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.geom.transform.GeneralTransform3D;
import com.sun.javafx.sg.prism.NGCamera;
import com.sun.javafx.sg.prism.NGDefaultCamera;
import com.sun.prism.CompositeMode;
import com.sun.prism.Graphics;
import com.sun.prism.RTTexture;
import com.sun.prism.RenderTarget;
import com.sun.prism.Texture;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.ps.BaseShaderContext;
import com.sun.prism.ps.Shader;

import java.util.Arrays;
import java.io.IOException;
import java.io.InputStream;

/**
 * The Prism context of the OpenGL pipeline. Its transforms, clips and
 * targets are those of the Vulkan pipeline, as the device renders with the
 * same upper-left origin and depth range. The rendering is submitted when the
 * context is flushed or a frame is presented.
 */
class GLContext extends BaseShaderContext {

    static final int NUM_QUADS = PrismSettings.superShader ? 4096 : 256;

    private final GLDevice device;
    private final GLResourceFactory factory;
    private State state;
    private RenderTarget currentTarget;

    private GeneralTransform3D projViewTx = new GeneralTransform3D();
    private GeneralTransform3D scratchTx = new GeneralTransform3D();
    private final GeneralTransform3D worldTx = new GeneralTransform3D();
    private final Affine3D scratchAffine3DTx = new Affine3D();
    private Vec3d cameraPos = new Vec3d();
    private final double[] tempMatrix = new double[16];
    private final float[] rawMatrix = new float[16];
    private final float[] phongUniforms = new float[GLDevice.PHONG_UNIFORM_FLOATS];

    GLContext(Screen screen, GLResourceFactory factory, GLDevice device) {
        super(screen, factory, NUM_QUADS);
        this.factory = factory;
        this.device = device;
    }

    static String readShader(String name) {
        try (InputStream in = GLContext.class.getResourceAsStream("glsl/" + name)) {
            return in != null ? new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Creates the device, or returns null if OpenGL is not usable.
     */
    static GLDevice createDevice() {
        String vertexShader = readShader("PassThrough.vert");
        if (vertexShader == null) {
            if (PrismSettings.verbose) {
                System.err.println("GLContext: vertex shader not found");
            }
            return null;
        }
        return GLDevice.create(vertexShader, PrismSettings.verbose);
    }

    void initState() {
        super.init();
        state = new State();
        device.setCompositeMode(GLDevice.COMP_SRCOVER);
    }

    GLDevice getDevice() {
        return device;
    }

    String getDeviceName() {
        return device.getDeviceName();
    }

    int getMaximumTextureSize() {
        return device.getMaxTextureSize();
    }

    boolean isExportSupported() {
        return device.isExportSupported();
    }

    @Override
    public GLResourceFactory getResourceFactory() {
        return factory;
    }

    RenderTarget getCurrentTarget() {
        return currentTarget;
    }

    /**
     * Maps the [-1, 1] depth range of the projection to the [0, 1] range of OpenGL.
     */
    private GeneralTransform3D adjustClipSpace(GeneralTransform3D tx) {
        double[] m = tx.get(tempMatrix);
        m[8] = (m[8] + m[12]) / 2;
        m[9] = (m[9] + m[13]) / 2;
        m[10] = (m[10] + m[14]) / 2;
        m[11] = (m[11] + m[15]) / 2;
        tx.set(m);
        return tx;
    }

    @Override
    protected State updateRenderTarget(RenderTarget target, NGCamera camera, boolean depthTest) {
        GLRenderTarget glTarget = (GLRenderTarget) target;
        device.setRenderTarget(glTarget.getTargetImage(), depthTest);
        currentTarget = target;
        resetLastClip(state);

        int width = target.getContentWidth();
        int height = target.getContentHeight();
        if (camera instanceof NGDefaultCamera defaultCamera) {
            defaultCamera.validate(width, height);
            projViewTx = adjustClipSpace(camera.getProjViewTx(projViewTx));
        } else {
            projViewTx = adjustClipSpace(camera.getProjViewTx(projViewTx));
            double vw = camera.getViewWidth();
            double vh = camera.getViewHeight();
            if (width != vw || height != vh) {
                projViewTx.scale(vw / width, vh / height, 1.0);
            }
        }
        cameraPos = camera.getPositionInWorld(cameraPos);
        return state;
    }

    @Override
    protected void updateTexture(int texUnit, Texture tex) {
        if (tex == null) {
            device.setTexture(texUnit, null, false, GLDevice.WRAP_CLAMP_TO_EDGE);
            return;
        }
        device.setTexture(texUnit, ((GLTexture<?>) tex).getImage(), tex.getLinearFiltering(), wrapOf(tex));
    }

    private static int wrapOf(Texture tex) {
        return switch (tex.getWrapMode()) {
            case REPEAT, REPEAT_SIMULATED -> GLDevice.WRAP_REPEAT;
            case CLAMP_TO_ZERO -> GLDevice.WRAP_CLAMP_TO_ZERO;
            default -> GLDevice.WRAP_CLAMP_TO_EDGE;
        };
    }

    @Override
    protected void updateShaderTransform(Shader shader, BaseTransform xform) {
        if (xform == null) {
            xform = BaseTransform.IDENTITY_TRANSFORM;
        }
        scratchTx.set(projViewTx);
        GeneralTransform3D perspective = getPerspectiveTransformNoClone();
        if (perspective.isIdentity()) {
            scratchTx = scratchTx.mul(xform);
        } else {
            scratchTx = scratchTx.mul(xform).mul(perspective);
        }
        // Row by row; the vertex shader computes v * m
        for (int i = 0; i < 16; i++) {
            rawMatrix[i] = (float) scratchTx.get(i);
        }
        device.setTransform(rawMatrix);

        GLShader glShader = (GLShader) shader;
        if (glShader.isPixcoordUsed() && currentTarget != null) {
            // gl_FragCoord is relative to the top-left of the target
            shader.setConstant("jsl_pixCoordOffset",
                    (float) currentTarget.getContentX(), (float) currentTarget.getContentY(), 0f, 0f);
        }
    }

    @Override
    protected void updateWorldTransform(BaseTransform xform) {
        worldTx.setIdentity();
        if (xform != null && !xform.isIdentity()) {
            worldTx.mul(xform);
        }
    }

    @Override
    protected void updateClipRect(Rectangle clipRect) {
        if (clipRect == null || clipRect.isEmpty()) {
            device.resetClipRect();
        } else {
            device.setClipRect(clipRect.x, clipRect.y, clipRect.width, clipRect.height);
        }
    }

    @Override
    protected void updateCompositeMode(CompositeMode mode) {
        device.setCompositeMode(switch (mode) {
            case CLEAR -> GLDevice.COMP_CLEAR;
            case SRC -> GLDevice.COMP_SRC;
            case SRC_OVER -> GLDevice.COMP_SRCOVER;
            case DST_OUT -> GLDevice.COMP_DSTOUT;
            case ADD -> GLDevice.COMP_ADD;
        });
    }

    @Override
    public void blit(RTTexture srcRTT, RTTexture dstRTT,
                     int srcX0, int srcY0, int srcX1, int srcY1,
                     int dstX0, int dstY0, int dstX1, int dstY1) {
        GLImage src = srcRTT instanceof GLTexture<?> t ? t.getImage() : null;
        GLImage dst = dstRTT instanceof GLTexture<?> t ? t.getImage()
                : currentTarget instanceof GLRenderTarget rt ? rt.getTargetImage() : null;
        flushVertexBuffer();
        device.blit(src, dst, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1);
    }

    @Override
    protected void renderQuads(float[] coordArray, byte[] colorArray, int numVertices) {
        device.drawQuads(coordArray, colorArray, numVertices);
    }

    void setShader(GLShader shader) {
        device.setShader(shader);
    }

    void clear(float r, float g, float b, float a, boolean clearDepth) {
        device.clear(r, g, b, a, clearDepth);
    }

    /**
     * Submits the recorded rendering and waits for it.
     */
    void flush() {
        flushVertexBuffer();
        device.flush();
    }

    /**
     * Copies a render target into an exported image and presents it.
     */
    void copyToExported(GLTexture<?> src, GLImage exported, int width, int height) {
        flushVertexBuffer();
        device.copyToExported(src.getImage(), exported, width, height);
    }

    @Override
    protected void setDeviceParametersFor2D() {
    }

    @Override
    protected void setDeviceParametersFor3D() {
    }

    // ---------------------------------------------------------------------
    // 3D

    /**
     * Copies a matrix in the column-major order of GLSL matrices.
     */
    private static void putMatrix(float[] dst, int offset, GeneralTransform3D src) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                dst[offset + col * 4 + row] = (float) src.get(row * 4 + col);
            }
        }
    }

    private static void put4(float[] dst, int offset, float x, float y, float z, float w) {
        dst[offset] = x;
        dst[offset + 1] = y;
        dst[offset + 2] = z;
        dst[offset + 3] = w;
    }

    void renderMeshView(GLMeshView meshView, Graphics g) {
        GLPhongMaterial material = meshView.getMaterial();
        GLMesh mesh = meshView.getMesh();
        if (material == null || mesh == null || mesh.getBuffers() == null) {
            return;
        }
        float[] u = phongUniforms;
        putTransforms(u, g);
        put4(u, 32, (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z, 0f);
        putColors(u, meshView, material);
        int numLights = putLights(u, meshView.getLights());
        MeshMaps maps = MeshMaps.of(material.getTextures());
        int[] variant = {
            maps.has(GLPhongMaterial.DIFFUSE) ? 2 : 1,
            material.getSpecularMode(maps.has(GLPhongMaterial.SPECULAR)),
            maps.has(GLPhongMaterial.SELF_ILLUM) ? 1 : 0,
            maps.has(GLPhongMaterial.BUMP) ? 1 : 0,
            numLights
        };
        device.drawMesh(mesh.getBuffers(), variant, meshView.getCullingMode(), meshView.isWireframe(), u,
                maps.images(), maps.linear());
    }

    /**
     * The projection, scaled for HiDPI as the transform holds the scale of
     * the target, and the world transform without that scale.
     */
    private void putTransforms(float[] u, Graphics g) {
        float scaleX = g.getPixelScaleFactorX();
        float scaleY = g.getPixelScaleFactorY();
        boolean scaled = scaleX != 1.0 || scaleY != 1.0;
        scratchTx = scratchTx.set(projViewTx);
        if (scaled) {
            scratchTx.scale(scaleX, scaleY, 1.0);
        }
        putMatrix(u, 0, scratchTx);
        BaseTransform xform = g.getTransformNoClone();
        if (scaled) {
            scratchAffine3DTx.setToIdentity();
            scratchAffine3DTx.scale(1.0 / scaleX, 1.0 / scaleY);
            scratchAffine3DTx.concatenate(xform);
            xform = scratchAffine3DTx;
        }
        updateWorldTransform(xform);
        putMatrix(u, 16, worldTx);
    }

    /** The ambient light and the diffuse and specular colors of the material. */
    private static void putColors(float[] u, GLMeshView meshView, GLPhongMaterial material) {
        put4(u, 36, meshView.getAmbientLightRed(), meshView.getAmbientLightGreen(),
                meshView.getAmbientLightBlue(), 0f);
        var diffuse = material.diffuseColor;
        put4(u, 40, diffuse.getRed(), diffuse.getGreen(), diffuse.getBlue(), diffuse.getAlpha());
        var specular = material.specularColor;
        put4(u, 44, specular.getRed(), specular.getGreen(), specular.getBlue(), specular.getAlpha());
    }

    private static final int LIGHTS = 3;
    private static final int LIGHT_FLOATS = 20;

    /**
     * The three lights, 20 floats each from float 48, zeros for those that
     * are off.
     *
     * @return the number of lights that are on
     */
    private static int putLights(float[] u, GLLight[] lights) {
        int numLights = 0;
        for (int i = 0; i < LIGHTS; i++) {
            int base = 48 + i * LIGHT_FLOATS;
            GLLight light = lights[i];
            if (light == null || light.w <= 0) {
                Arrays.fill(u, base, base + LIGHT_FLOATS, 0f);
            } else {
                putLight(u, base, light);
                numLights++;
            }
        }
        return numLights;
    }

    /** A light: position, color, attenuation, direction and range or spot cone. */
    private static void putLight(float[] u, int base, GLLight light) {
        put4(u, base, light.x, light.y, light.z, light.w);
        put4(u, base + 4, light.r, light.g, light.b, 0f);
        put4(u, base + 8, light.ca, light.la, light.qa, light.isAttenuated);
        if (light.isPointLight()) {
            put4(u, base + 12, 0f, 0f, 1f, 0f);
        } else {
            float length = (float) Math.sqrt(light.dirX * light.dirX + light.dirY * light.dirY
                    + light.dirZ * light.dirZ);
            put4(u, base + 12, light.dirX / length, light.dirY / length, light.dirZ / length, 0f);
        }
        if (light.isPointLight() || light.isDirectionalLight()) {
            put4(u, base + 16, light.maxRange, -1f, 2f, 0f);
        } else {
            // I = pow((cosAngle - cosOuter) / (cosInner - cosOuter), falloff)
            float cosOuter = (float) Math.cos(Math.toRadians(light.outerAngle));
            float cosInner = (float) Math.cos(Math.toRadians(light.innerAngle));
            put4(u, base + 16, light.maxRange, cosOuter, cosInner - cosOuter, light.falloff);
        }
    }

    /** The maps of a material and whether each is filtered linearly. */
    private record MeshMaps(GLImage[] images, boolean[] linear) {
        static MeshMaps of(Texture[] maps) {
            GLImage[] images = new GLImage[GLDevice.MAX_TEXTURE_UNITS];
            boolean[] linear = new boolean[GLDevice.MAX_TEXTURE_UNITS];
            for (int i = 0; i < images.length; i++) {
                if (maps[i] instanceof GLTexture<?> t) {
                    images[i] = t.getImage();
                    linear[i] = t.getLinearFiltering();
                } else {
                    linear[i] = true;
                }
            }
            return new MeshMaps(images, linear);
        }

        boolean has(int map) {
            return images[map] != null;
        }
    }

    @Override
    public void dispose() {
        disposeLCDBuffer();
        super.dispose();
        device.destroy();
    }
}
