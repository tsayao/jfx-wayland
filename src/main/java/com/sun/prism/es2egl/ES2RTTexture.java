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
import com.sun.prism.Graphics;
import com.sun.prism.Image;
import com.sun.prism.PixelFormat;
import com.sun.prism.RTTexture;
import com.sun.prism.ReadbackRenderTarget;
import com.sun.prism.Texture;
import com.sun.prism.impl.PrismSettings;
import com.sun.prism.impl.PrismTrace;

import java.nio.Buffer;

class ES2RTTexture extends ES2Texture<ES2RTTextureData>
        implements ES2RenderTarget, RTTexture, ReadbackRenderTarget
{

    private boolean opaque;

    private ES2RTTexture(ES2Context context, ES2TextureResource<ES2RTTextureData> resource,
                         WrapMode wrapMode,
                         int physicalWidth, int physicalHeight,
                         int contentX, int contentY,
                         int contentWidth, int contentHeight,
                         int maxContentWidth, int maxContentHeight)
    {
        super(context, resource, PixelFormat.BYTE_BGRA_PRE, wrapMode,
                physicalWidth, physicalHeight,
                contentX, contentY,
                contentWidth, contentHeight,
                maxContentWidth, maxContentHeight, false);
        PrismTrace.rttCreated(resource.getResource().getFboID(),
                              physicalWidth, physicalHeight,
                              PixelFormat.BYTE_BGRA_PRE.getBytesPerPixelUnit());
        this.opaque = false;
    }

    /**
     * Attach a depth buffer to the currently bound FBO
     * @param context current context
     */
    void attachDepthBuffer(ES2Context context) {
        // Return immediately if this RTT already has a depth buffer
        ES2RTTextureData texData = resource.getResource();
        int dbID = texData.getDepthBufferID();
        if (dbID != 0) {
            return;
        }
        int msaaSamples = isMSAA() ? context.getGLContext().getSampleSize() : 0;
        dbID = context.getGLContext().createDepthBuffer(getPhysicalWidth(),
                getPhysicalHeight(), msaaSamples);

        // Add to disposer record so that we can cleanup the depth buffer when
        // this RTT is destroyed.
        texData.setDepthBufferID(dbID);
    }

    /**
     * Create and attach a color multisample render buffer to current FBO
     * @param context current context
     */
    private void createAndAttachMSAABuffer(ES2Context context) {
        // Assert isMSAA() must be true
        ES2RTTextureData texData = resource.getResource();
        int rbID = texData.getMSAARenderBufferID();
        if (rbID != 0) {
            return;
        }
        GLContext glContext = context.getGLContext();
        rbID = glContext.createRenderBuffer(getPhysicalWidth(),
                getPhysicalHeight(), glContext.getSampleSize());

        // Add to disposer record so that we can cleanup the MSAA
        // render buffer when this RTT is destroyed.
        texData.setMSAARenderBufferID(rbID);
    }

    /**
     * Whether the content is padded with a border of one transparent texel,
     * which simulates CLAMP_TO_ZERO without GL_CLAMP_TO_BORDER. Only
     * CLAMP_NOT_NEEDED and CLAMP_TO_ZERO are supported for RT textures.
     */
    private static boolean isPadded(GLContext glContext, WrapMode wrapMode) {
        return switch (wrapMode) {
            case CLAMP_NOT_NEEDED -> false;
            case CLAMP_TO_ZERO -> !glContext.canClampToZero();
            case CLAMP_TO_EDGE_SIMULATED, CLAMP_TO_ZERO_SIMULATED, REPEAT_SIMULATED ->
                    throw new IllegalArgumentException("Cannot request simulated wrap mode: " + wrapMode);
            default -> throw new IllegalArgumentException("wrap mode not supported for RT textures: " + wrapMode);
        };
    }

    /**
     * The dimension of a texture that holds a padded dimension, of a power of
     * two when the device needs it, and not under the minimum size of the RT
     * textures, or 0 when it exceeds the maximum size.
     */
    private static int textureDimension(GLContext glContext, int paddedDim) {
        int maxSize = glContext.getMaxTextureSize();
        int texDim;
        if (glContext.canCreateNonPowTwoTextures()) {
            texDim = paddedDim <= maxSize ? paddedDim : 0;
        } else {
            texDim = nextPowerOfTwo(paddedDim, maxSize);
        }
        return texDim == 0 ? 0 : Math.max(texDim, PrismSettings.minRTTSize);
    }

    private static RuntimeException tooLarge(String requested, GLContext glContext) {
        return new RuntimeException("Requested texture " + requested
                + " exceeds the maximum texture size (" + glContext.getMaxTextureSize() + ")");
    }

    /**
     * The content dimension of an RT texture created for a dimension. The
     * content of an RT texture is the texture without its padding: RT
     * textures are recycled (by Decora), and the viewport of their reuses is
     * set to the content region.
     */
    static int getCompatibleDimension(ES2Context context, int dim, WrapMode wrapMode) {
        GLContext glContext = context.getGLContext();
        int border = isPadded(glContext, wrapMode) ? 1 : 0;
        int texDim = textureDimension(glContext, dim + 2 * border);
        if (texDim == 0) {
            throw tooLarge("dimension (" + dim + ")", glContext);
        }
        return texDim - 2 * border;
    }

    static ES2RTTexture create(ES2Context context, int w, int h, WrapMode wrapMode, boolean msaa) {
        GLContext glContext = context.getGLContext();
        boolean padded = isPadded(glContext, wrapMode);
        int border = padded ? 1 : 0;
        int texWidth = textureDimension(glContext, w + 2 * border);
        int texHeight = textureDimension(glContext, h + 2 * border);
        if (texWidth == 0 || texHeight == 0) {
            throw tooLarge("dimensions (" + w + "x" + h + ")", glContext);
        }
        ES2VramPool pool = ES2VramPool.instance;
        long size = pool.estimateRTTextureSize(texWidth, texHeight, false);
        if (!pool.prepareForAllocation(size)) {
            return null;
        }
        // The framebuffer and the texture bound to the first unit are restored
        glContext.setActiveTextureUnit(0);
        int savedFBO = glContext.getBoundFBO();
        int savedTex = glContext.getBoundTexture();
        ES2RTTextureData texData = createTextureData(context, texWidth, texHeight, size, msaa);
        ES2RTTexture rtt = new ES2RTTexture(context, new ES2TextureResource<>(texData),
                padded ? wrapMode.simulatedVersion() : wrapMode,
                texWidth, texHeight, border, border, w, h, texWidth - 2 * border, texHeight - 2 * border);
        if (msaa) {
            rtt.createAndAttachMSAABuffer(context);
        }
        glContext.bindFBO(savedFBO);
        glContext.setBoundTexture(savedTex);
        return rtt;
    }

    /**
     * The texture and the framebuffer of an RT texture, left bound. A
     * multisampled one has no texture: a multisampled renderbuffer is
     * attached to its framebuffer instead.
     */
    private static ES2RTTextureData createTextureData(ES2Context context, int texWidth, int texHeight, long size,
                                                      boolean msaa) {
        GLContext glContext = context.getGLContext();
        int texID = msaa ? 0 : glContext.createTexture(texWidth, texHeight);
        int fboID = 0;
        if (texID != 0 || msaa) {
            fboID = glContext.createFBO(texID);
            if (fboID == 0) {
                glContext.deleteTexture(texID);
                texID = 0;
            }
        }
        return new ES2RTTextureData(context, texID, fboID, texWidth, texHeight, size);
    }

    @Override
    public Texture getBackBuffer() {
        return this;
    }

    @Override
    public Graphics createGraphics() {
        return ES2Graphics.create(context, this);
    }

    @Override
    public int[] getPixels() {
        return null;
    }

    @Override
    public boolean readPixels(Buffer pixels, int x, int y, int width, int height) {
        context.flushVertexBuffer();
        GLContext glContext = context.getGLContext();
        int id = glContext.getBoundFBO();
        int fboID = getFboID();
        boolean changeBoundFBO = id != fboID;
        if (changeBoundFBO) {
            glContext.bindFBO(fboID);
        }
        boolean result = glContext.readPixels(pixels, x, y, width, height);
        if (changeBoundFBO) {
            glContext.bindFBO(id);
        }
        return result;
    }

    @Override
    public boolean readPixels(Buffer pixels) {
        return readPixels(pixels, getContentX(), getContentY(),
                 getContentWidth(), getContentHeight());
    }

    @Override
    public int getFboID() {
        return resource.getResource().getFboID();
    }

    @Override
    public Screen getAssociatedScreen() {
        return context.getAssociatedScreen();
    }

    @Override
    public void update(Image img) {
        throw new UnsupportedOperationException("update() not supported for RTTextures");
    }

    @Override
    public void update(Image img, int dstx, int dsty) {
        throw new UnsupportedOperationException("update() not supported for RTTextures");
    }

    @Override
    public void update(Image img, int dstx, int dsty, int w, int h) {
        throw new UnsupportedOperationException("update() not supported for RTTextures");
    }

    @Override
    public void update(Image img, int dstx, int dsty, int w, int h, boolean skipFlush) {
        throw new UnsupportedOperationException("update() not supported for RTTextures");
    }

    @Override
    public void update(Buffer pixels, PixelFormat format,
            int dstx, int dsty,
            int srcx, int srcy, int srcw, int srch, int srcscan,
            boolean skipFlush) {
        throw new UnsupportedOperationException("update() not supported for RTTextures");
    }

    @Override
    public boolean isOpaque() {
        return opaque;
    }

    @Override
    public void setOpaque(boolean opaque) {
        this.opaque = opaque;
    }

    @Override
    public boolean isVolatile() {
        return false;
    }

    @Override
    public boolean isMSAA() {
        return resource.getResource().getMSAARenderBufferID() != 0;
    }
}