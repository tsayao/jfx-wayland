/*
 * Copyright (c) 2009, 2022, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.Vec3d;
import com.sun.javafx.geom.transform.Affine2D;
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
import com.sun.prism.ps.ShaderFactory;

class ES2Context extends BaseShaderContext {

    // Temporary variables
    private static GeneralTransform3D scratchTx = new GeneralTransform3D();
    private static final GeneralTransform3D flipTx = new GeneralTransform3D();
    private static final Affine3D scratchAffine3DTx = new Affine3D();
    // contains the combined projection/modelview matrix (elements 0-15)
    private static float rawMatrix[] = new float[GLContext.NUM_MATRIX_ELEMENTS];

    private GeneralTransform3D projViewTx = new GeneralTransform3D();
    private GeneralTransform3D worldTx = new GeneralTransform3D();
    private Vec3d cameraPos = new Vec3d();

    private RenderTarget currentTarget;
    private final GLContext glContext;
    private State state;
    private int quadIndices;
    private int indexBuffer = 0;
    private int shaderProgram;

    public static final int NUM_QUADS = PrismSettings.superShader ? 4096 : 256;

    ES2Context(Screen screen, ShaderFactory factory, GLContext glContext) {
        super(screen, factory, NUM_QUADS);
        this.glContext = glContext;
        makeCurrent();
        glContext.enableVertexAttributes();
        quadIndices = genQuadsIndexBuffer(NUM_QUADS);
        setIndexBuffer(quadIndices);
        state = new State();
    }

    static short [] getQuadIndices16bit(int numQuads) {
        short data[] = new short[numQuads * 6];

        for (int i = 0; i != numQuads; ++i) {
            int vtx = i * 4;
            int idx = i * 6;
            data[idx+0] = (short) (vtx+0);
            data[idx+1] = (short) (vtx+1);
            data[idx+2] = (short) (vtx+2);

            data[idx+3] = (short) (vtx+2);
            data[idx+4] = (short) (vtx+1);
            data[idx+5] = (short) (vtx+3);
        }

        return data;
    }

    int genQuadsIndexBuffer(int numQuads) {
        if (numQuads * 6 > 0x10000)
            throw new IllegalArgumentException("vertex indices overflow");

        return glContext.createIndexBuffer16(getQuadIndices16bit(numQuads));
    }

    final void setIndexBuffer(int ib) {
        if (indexBuffer != ib) {
            glContext.setIndexBuffer(indexBuffer = ib);
        }
    }

    GLContext getGLContext() {
        return glContext;
    }

    /** Makes the context current on the calling thread, the render thread. */
    void makeCurrent() {
        glContext.makeCurrent();
    }

    ES2Shader getPhongShader(ES2MeshView meshView) {
        return ES2PhongShader.getShader(meshView, this);
    }

    /**
     * Called from ES2Graphics.updateRenderTarget() in response to a window
     * resize event.  This method ensures that the context is made current
     * after the resize event, which is required on Mac OS X in order to
     * force a call to [NSOpenGLContext update].
     */
    void forceRenderTarget(ES2Graphics g) {
        updateRenderTarget(g.getRenderTarget(), g.getCameraNoClone(),
                g.isDepthTest() && g.isDepthBuffer());
    }

    int getShaderProgram() {
        return shaderProgram;
    }

    // Forcibly sets the current shader program to the given object.
    void setShaderProgram(int progid) {
        shaderProgram = progid;
        glContext.setShaderProgram(progid);
    }

    // Sets the current shader program to the given object only if it was
    // not already the current program.
    void updateShaderProgram(int progid) {
        if (progid != shaderProgram) {
            setShaderProgram(progid);
        }
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    protected void releaseRenderTarget() {
        currentTarget = null;
        super.releaseRenderTarget();
    }

    @Override
    protected State updateRenderTarget(RenderTarget target, NGCamera camera,
            boolean depthTest) {
        glContext.bindFBO(((ES2RenderTarget) target).getFboID());
        boolean msaa = false;
        if (target instanceof ES2RTTexture rtTarget) {
            msaa = rtTarget.isMSAA();
            if (depthTest) {
                // Attached to the bound framebuffer
                rtTarget.attachDepthBuffer(this);
            }
        }
        int w = target.getContentWidth();
        int h = target.getContentHeight();
        glContext.updateViewportAndDepthTest(target.getContentX(), target.getContentY(), w, h, depthTest);
        glContext.updateMSAAState(msaa);
        updateProjection(target, camera, w, h);
        // Uploaded to the shader when the 3D state is set
        cameraPos = camera.getPositionInWorld(cameraPos);
        currentTarget = target;
        return state;
    }

    /**
     * The projection of the camera, which updateShaderTransform() uploads to
     * the shader with the modelview, flipped for the RT textures.
     */
    private void updateProjection(RenderTarget target, NGCamera camera, int w, int h) {
        if (camera instanceof NGDefaultCamera defaultCamera) {
            defaultCamera.validate(w, h);
        }
        scratchTx = camera.getProjViewTx(scratchTx);
        if (!(camera instanceof NGDefaultCamera)) {
            // TODO: verify that this is the right solution. There may be
            // other use-cases where rendering needs different viewport size.
            double vw = camera.getViewWidth();
            double vh = camera.getViewHeight();
            if (w != vw || h != vh) {
                scratchTx.scale(vw / w, vh / h, 1.0);
            }
        }
        if (target instanceof ES2RTTexture) {
            projViewTx.set(flipTx);
            projViewTx.mul(scratchTx);
        } else {
            projViewTx.set(scratchTx);
        }
    }

    @Override
    protected void updateTexture(int texUnit, Texture tex) {
        glContext.updateActiveTextureUnit(texUnit);

        if (tex == null) {
            glContext.updateBoundTexture(0);
        } else {
            ES2Texture es2Tex = (ES2Texture)tex;
            glContext.updateBoundTexture(es2Tex.getNativeSourceHandle());
            es2Tex.updateWrapState();
            es2Tex.updateFilterState();
        }
    }

    @Override
    protected void updateShaderTransform(Shader shader, BaseTransform xform) {
        if (xform == null) {
            xform = BaseTransform.IDENTITY_TRANSFORM;
        }
        scratchTx.set(projViewTx);
        GeneralTransform3D perspectiveTransform = getPerspectiveTransformNoClone();
        if (perspectiveTransform.isIdentity()) {
            updateRawMatrix(scratchTx.mul(xform));
        } else {
            updateRawMatrix(scratchTx.mul(xform).mul(perspectiveTransform));
        }
        ES2Shader es2shader = (ES2Shader) shader;
        es2shader.setMatrix("mvpMatrix", rawMatrix);
        if (es2shader.isPixcoordUsed()) {
            updatePixcoordOffset(shader);
        }
    }

    /**
     * The gl_FragCoord variable is in window coordinates, which do not take
     * the viewport origin into account, nor the y-flip of the projection of
     * the RT textures: jsl_pixCoordOffset lets the shader treat pixcoord as
     * ranging from [0,0] to [contentWidth,contentHeight] of the target.
     */
    private void updatePixcoordOffset(Shader shader) {
        float xoff = currentTarget.getContentX();
        float yoff = currentTarget.getContentY();
        boolean flipped = !(currentTarget instanceof ES2RTTexture);
        float yinv = flipped ? currentTarget.getPhysicalHeight() : 0f;
        float yflip = flipped ? 1f : -1f;
        shader.setConstant("jsl_pixCoordOffset", xoff, yoff, yinv, yflip);
    }

    @Override
    protected void updateWorldTransform(BaseTransform xform) {
        worldTx.setIdentity();
        if ((xform != null) && (!xform.isIdentity())) {
            worldTx.mul(xform);
        }
    }

    @Override
    protected void updateClipRect(Rectangle clipRect) {
        if (clipRect == null || clipRect.isEmpty()) {
            glContext.scissorTest(false, 0, 0, 0, 0);
        } else {
            // the scissor rectangle is specified using the lower-left
            // origin of the clip region (in the framebuffer's coordinate
            // space), so we must account for the x/y offsets of the
            // destination surface, and use a flipped y origin when rendering
            // to an ES2SwapChain
            int w = clipRect.width;
            int h = clipRect.height;
            int x = currentTarget.getContentX();
            int y = currentTarget.getContentY();
            if (currentTarget instanceof ES2RTTexture) {
                x += clipRect.x;
                y += clipRect.y;
            } else {
                int dsth = currentTarget.getPhysicalHeight();
                x += clipRect.x;
                y += dsth - (clipRect.y + h);
            }
            glContext.scissorTest(true, x, y, w, h);
        }
    }

    @Override
    protected void updateCompositeMode(CompositeMode mode) {
        switch (mode) {
            case CLEAR -> glContext.blendFunc(GLContext.GL_ZERO, GLContext.GL_ZERO);
            case SRC -> glContext.blendFunc(GLContext.GL_ONE, GLContext.GL_ZERO);
            case SRC_OVER -> glContext.blendFunc(GLContext.GL_ONE, GLContext.GL_ONE_MINUS_SRC_ALPHA);
            case DST_OUT -> glContext.blendFunc(GLContext.GL_ZERO, GLContext.GL_ONE_MINUS_SRC_ALPHA);
            case ADD -> glContext.blendFunc(GLContext.GL_ONE, GLContext.GL_ONE);
            default -> throw new InternalError("Unrecognized composite mode: " + mode);
        }
    }

    @Override
    public void setDeviceParametersFor2D() {
        // invalidate cache data
        indexBuffer = 0;
        shaderProgram = 0;
        glContext.setDeviceParametersFor2D();

        // Bind vertex attributes and index buffer
        glContext.enableVertexAttributes();
        setIndexBuffer(quadIndices);
    }

    @Override
    public void setDeviceParametersFor3D() {
        // unbind vertex attributes and index buffer
        glContext.disableVertexAttributes();
        glContext.setDeviceParametersFor3D();
    }

    @Override
    public void blit(RTTexture srcRTT, RTTexture dstRTT,
                     int srcX0, int srcY0, int srcX1, int srcY1,
                     int dstX0, int dstY0, int dstX1, int dstY1)
    {
        // If dstRTT is null then will blit to currently bound fbo
        int dstFboID = dstRTT == null ? 0 : ((ES2RTTexture)dstRTT).getFboID();
        int srcFboID = ((ES2RTTexture)srcRTT).getFboID();
        glContext.blitFBO(srcFboID, dstFboID,
                          srcX0, srcY0, srcX1, srcY1,
                          dstX0, dstY0, dstX1, dstY1);
    }

        void renderMeshView(Graphics g, ES2MeshView meshView) {
        ES2Shader shader = getPhongShader(meshView);
        setShaderProgram(shader.getProgramObject());
        float scaleX = g.getPixelScaleFactorX();
        float scaleY = g.getPixelScaleFactorY();
        shader.setMatrix("viewProjectionMatrix", scaledViewProjection(scaleX, scaleY));
        shader.setConstant("camPos", (float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
        updateWorldTransform(unscaledWorldTransform(g.getTransformNoClone(), scaleX, scaleY));
        updateRawMatrix(worldTx);
        shader.setMatrix("worldMatrix", rawMatrix);
        ES2PhongShader.setShaderParamaters(shader, meshView, this);
        glContext.renderMesh(meshView.getMesh().getBuffers(), meshView.getCullingMode(),
                meshView.isWireframe());
    }

    /** The view projection, scaled for HiDPI, as the shader needs it. */
    private float[] scaledViewProjection(float scaleX, float scaleY) {
        if (scaleX != 1.0 || scaleY != 1.0) {
            scratchTx = scratchTx.set(projViewTx);
            scratchTx.scale(scaleX, scaleY, 1.0);
            updateRawMatrix(scratchTx);
        } else {
            updateRawMatrix(projViewTx);
        }
        return rawMatrix;
    }

    /**
     * The world transform without the HiDPI scale of the swap chain
     * (ES2SwapChain.createGraphics()), which the view projection has.
     */
    private static BaseTransform unscaledWorldTransform(BaseTransform xform, float scaleX, float scaleY) {
        if (scaleX == 1.0 && scaleY == 1.0) {
            return xform;
        }
        scratchAffine3DTx.setToIdentity();
        scratchAffine3DTx.scale(1.0 / scaleX, 1.0 / scaleY);
        scratchAffine3DTx.concatenate(xform);
        return scratchAffine3DTx;
    }

    @Override
    protected void renderQuads(float coordArray[], byte colorArray[], int numVertices) {
        glContext.drawIndexedQuads(coordArray, colorArray, numVertices);
    }

    void printRawMatrix(String mesg) {
        System.err.println(mesg + " = ");
        for (int i = 0; i < 4; i++) {
            System.err.println(rawMatrix[i] + ", " + rawMatrix[i+4]
                    + ", " + rawMatrix[i+8] + ", " + rawMatrix[i+12]);
        }
    }

    // Need to transpose the matrix because OpenGL stores its matrix in
    // column major (though matrix computation is done in row major)
    private void updateRawMatrix(GeneralTransform3D src) {
        rawMatrix[0]  = (float)src.get(0); // Scale X
        rawMatrix[1]  = (float)src.get(4); // Shear Y
        rawMatrix[2]  = (float)src.get(8);
        rawMatrix[3]  = (float)src.get(12);
        rawMatrix[4]  = (float)src.get(1); // Shear X
        rawMatrix[5]  = (float)src.get(5); // Scale Y
        rawMatrix[6]  = (float)src.get(9);
        rawMatrix[7]  = (float)src.get(13);
        rawMatrix[8]  = (float)src.get(2);
        rawMatrix[9]  = (float)src.get(6);
        rawMatrix[10] = (float)src.get(10);
        rawMatrix[11] = (float)src.get(14);
        rawMatrix[12] = (float)src.get(3);  // Translate X
        rawMatrix[13] = (float)src.get(7);  // Translate Y
        rawMatrix[14] = (float)src.get(11);
        rawMatrix[15] = (float)src.get(15);
    }

    static {
        BaseTransform tx = Affine2D.getScaleInstance(1.0, -1.0);
        flipTx.setIdentity();
        flipTx.mul(tx);
    }
}
