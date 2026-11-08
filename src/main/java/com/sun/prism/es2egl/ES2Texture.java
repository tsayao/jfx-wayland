/*
 * Copyright (c) 2009, 2024, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.prism.MediaFrame;
import com.sun.prism.MultiTexture;
import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.impl.BaseTexture;
import com.sun.prism.impl.BufferUtil;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

class ES2Texture<T extends ES2TextureData> extends BaseTexture<ES2TextureResource<T>> {

    final ES2Context context;

    /**
     * How the pixels of a format are uploaded: the internal format of the
     * texture, the format and the type of the pixels, and the alignment of
     * their rows.
     */
    private record Upload(int internalFormat, int pixelFormat, int pixelType, int alignment) {

        static Upload of(PixelFormat format) {
            return switch (format) {
                case BYTE_BGRA_PRE, INT_ARGB_PRE ->
                        new Upload(GLContext.GL_RGBA, GLContext.GL_BGRA, GLContext.GL_UNSIGNED_INT_8_8_8_8_REV, 4);
                // An alpha channel in the texture, as the code that expands the
                // regions in BaseGraphics.drawTextureVO() expects the texels
                // outside of the content to be transparent
                case BYTE_RGB -> new Upload(GLContext.GL_RGBA, GLContext.GL_RGB, GLContext.GL_UNSIGNED_BYTE, 1);
                case BYTE_GRAY ->
                        new Upload(GLContext.GL_LUMINANCE, GLContext.GL_LUMINANCE, GLContext.GL_UNSIGNED_BYTE, 1);
                case BYTE_ALPHA -> new Upload(GLContext.GL_ALPHA, GLContext.GL_ALPHA, GLContext.GL_UNSIGNED_BYTE, 1);
                case FLOAT_XYZW -> new Upload(GLContext.GL_RGBA32F, GLContext.GL_RGBA, GLContext.GL_FLOAT, 4);
                case BYTE_APPLE_422 -> apple422();
                default -> throw new InternalError("Image format not supported: " + format);
            };
        }

        /** The formats of the video frames, whose words are in the byte order of their buffer. */
        static Upload ofFrame(PixelFormat format, ByteOrder order) {
            return switch (format) {
                case INT_ARGB_PRE -> new Upload(GLContext.GL_RGBA, GLContext.GL_BGRA,
                        order == ByteOrder.LITTLE_ENDIAN
                                ? GLContext.GL_UNSIGNED_INT_8_8_8_8_REV : GLContext.GL_UNSIGNED_INT_8_8_8_8, 4);
                case BYTE_APPLE_422 -> apple422();
                // MULTI_YCbCr_420 goes through a MultiTexture
                default -> throw new InternalError("Invalid video image format " + format);
            };
        }

        /** Requires GL_APPLE_ycbcr_422. */
        private static Upload apple422() {
            return new Upload(GLContext.GL_RGB, GLContext.GL_YCBCR_422_APPLE,
                    GLContext.GL_UNSIGNED_SHORT_8_8_APPLE, 2);
        }
    }

    /** Where the content is in a texture, and the size of the texture. */
    private record Layout(WrapMode wrapMode, int contentX, int contentY, int texWidth, int texHeight) {

        /**
         * The layout of content of w x h: a simulated CLAMP_TO_ZERO pads the
         * content with a transparent border, and a simulated CLAMP_TO_EDGE or
         * REPEAT adds a row and a column for the edges.
         */
        static Layout of(WrapMode wrapMode, int w, int h) {
            return switch (wrapMode) {
                case CLAMP_TO_ZERO_SIMULATED -> new Layout(wrapMode, 1, 1, w + 2, h + 2);
                case CLAMP_TO_EDGE_SIMULATED, REPEAT_SIMULATED ->
                        new Layout(wrapMode, 0, 0, isPowerOfTwo(w) ? w : w + 1, isPowerOfTwo(h) ? h : h + 1);
                default -> new Layout(wrapMode, 0, 0, w, h);
            };
        }

        /** The layout in a texture that the device can create, of a power of two size if it must. */
        Layout fitted(GLContext glCtx, int w, int h) {
            int maxSize = glCtx.getMaxTextureSize();
            if (texWidth > maxSize || texHeight > maxSize) {
                throw new RuntimeException("Requested texture dimensions (" + w + "x" + h + ") "
                        + "require dimensions (" + texWidth + "x" + texHeight + ") "
                        + "that exceed maximum texture size (" + maxSize + ")");
            }
            if (glCtx.canCreateNonPowTwoTextures()) {
                return this;
            }
            return new Layout(wrapMode, contentX, contentY,
                    nextPowerOfTwo(texWidth, maxSize), nextPowerOfTwo(texHeight, maxSize));
        }
    }

    /** The texture bound to the active unit, and the unit. */
    private record Binding(int unit, int texture) {
    }

    ES2Texture(ES2Context context, ES2TextureResource<T> resource,
               PixelFormat format, WrapMode wrapMode,
               int physicalWidth, int physicalHeight,
               int contentX, int contentY, int contentWidth, int contentHeight, boolean useMipmap) {
        super(resource, format, wrapMode,
              physicalWidth, physicalHeight,
              contentX, contentY, contentWidth, contentHeight, useMipmap);
        this.context = context;
    }

    ES2Texture(ES2Context context, ES2TextureResource<T> resource,
               PixelFormat format, WrapMode wrapMode,
               int physicalWidth, int physicalHeight,
               int contentX, int contentY, int contentWidth, int contentHeight,
               int maxContentWidth, int maxContentHeight, boolean useMipmap) {
        super(resource, format, wrapMode,
              physicalWidth, physicalHeight,
              contentX, contentY, contentWidth, contentHeight,
              maxContentWidth, maxContentHeight, useMipmap);
        this.context = context;
    }

    // TODO: We don't handle mipmap in shared texture yet.
    private ES2Texture(ES2Texture<T> sharedTex, WrapMode newMode) {
        super(sharedTex, newMode, false);
        this.context = sharedTex.context;
    }

    @Override
    protected Texture createSharedTexture(WrapMode newMode) {
        return new ES2Texture<>(this, newMode);
    }

    static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    /**
     * Given a starting value and a maximum limit, returns the first
     * power-of-two greater than or equal to the starting value.  If the
     * resulting value is greater than the maximum limit, zero is returned.
     */
    static int nextPowerOfTwo(int val, int max) {
        if (val > max) {
            return 0;
        }
        int i = 1;
        while (i < val) {
            i *= 2;
        }
        return i;
    }

    // ---------------------------------------------------------------------
    // Creation

    static ES2Texture<ES2TextureData> create(ES2Context context, PixelFormat format,
                                             WrapMode wrapMode, int w, int h, boolean useMipmap) {
        checkFormat(context, format);
        GLContext glCtx = context.getGLContext();
        Layout layout = Layout.of(supportedWrapMode(glCtx, wrapMode, w, h), w, h).fitted(glCtx, w, h);
        checkDimensions(layout.texWidth(), layout.texHeight(), format);
        ES2TextureData texData = allocate(context, layout.texWidth(), layout.texHeight(), format, bound -> {
            boolean created = createStorage(bound, Upload.of(format), layout.texWidth(), layout.texHeight(),
                    w != layout.texWidth() || h != layout.texHeight(), useMipmap);
            bound.texParamsMinMax(GLContext.GL_LINEAR, useMipmap);
            return created;
        });
        if (texData == null) {
            return null;
        }
        return new ES2Texture<>(context, new ES2TextureResource<>(texData), format, layout.wrapMode(),
                layout.texWidth(), layout.texHeight(), layout.contentX(), layout.contentY(), w, h, useMipmap);
    }

    private static void checkFormat(ES2Context context, PixelFormat format) {
        if (!context.getResourceFactory().isFormatSupported(format)) {
            throw new UnsupportedOperationException("Pixel format " + format + " not supported on this device");
        }
        if (format == PixelFormat.MULTI_YCbCr_420) {
            throw new IllegalArgumentException("Format requires multitexturing: " + format);
        }
    }

    /**
     * The wrap mode, or its simulated version when the device does not have
     * it: CLAMP_TO_ZERO without GL_CLAMP_TO_BORDER, or CLAMP_TO_EDGE and
     * REPEAT for sizes that are not powers of two without such textures.
     */
    private static WrapMode supportedWrapMode(GLContext glCtx, WrapMode wrapMode, int w, int h) {
        return switch (wrapMode) {
            case CLAMP_TO_ZERO -> glCtx.canClampToZero() ? wrapMode : wrapMode.simulatedVersion();
            case CLAMP_TO_EDGE, REPEAT ->
                    glCtx.canCreateNonPowTwoTextures() || (isPowerOfTwo(w) && isPowerOfTwo(h))
                            ? wrapMode : wrapMode.simulatedVersion();
            case CLAMP_NOT_NEEDED -> wrapMode;
            case CLAMP_TO_EDGE_SIMULATED, CLAMP_TO_ZERO_SIMULATED, REPEAT_SIMULATED ->
                    throw new IllegalArgumentException("Cannot request simulated wrap mode: " + wrapMode);
        };
    }

    private static void checkDimensions(int texWidth, int texHeight, PixelFormat format) {
        if (texWidth <= 0 || texHeight <= 0
                || texWidth >= Integer.MAX_VALUE / texHeight / format.getBytesPerPixelUnit()) {
            throw new RuntimeException("Illegal texture dimensions (" + texWidth + "x" + texHeight + ")");
        }
    }

    @FunctionalInterface
    private interface StorageCreator {
        /** Creates the storage of the texture bound to the active unit. */
        boolean create(GLContext glCtx);
    }

    /**
     * Creates a texture, within the budget of the VRAM pool, keeping the
     * texture bound to the active unit.
     *
     * @return the texture, or null if the pool or the device has no room for it
     */
    private static ES2TextureData allocate(ES2Context context, int texWidth, int texHeight, PixelFormat format,
                                           StorageCreator storage) {
        ES2VramPool pool = ES2VramPool.instance;
        long size = pool.estimateTextureSize(texWidth, texHeight, format);
        if (!pool.prepareForAllocation(size)) {
            return null;
        }
        GLContext glCtx = context.getGLContext();
        int savedTex = glCtx.getBoundTexture();
        ES2TextureData texData = new ES2TextureData(context, glCtx.genAndBindTexture(), texWidth, texHeight, size);
        boolean created = storage.create(glCtx);
        glCtx.setBoundTexture(savedTex);
        if (!created) {
            texData.dispose();
            return null;
        }
        return texData;
    }

    /**
     * Creates the storage of the bound texture. The content of a texture is
     * undefined after glTexImage2D without pixels: when the content does not
     * fill the texture, the padding is made transparent, with zeros in the
     * GL_ALPHA format (one byte per texel, whatever the internal format).
     */
    private static boolean createStorage(GLContext glCtx, Upload upload, int texWidth, int texHeight,
                                         boolean padded, boolean useMipmap) {
        Buffer zeros = padded ? BufferUtil.newByteBuffer(texWidth * texHeight) : null;
        // Set by earlier uploads; the rows of zeros are packed
        glCtx.pixelStorei(GLContext.GL_UNPACK_ROW_LENGTH, 0);
        glCtx.pixelStorei(GLContext.GL_UNPACK_SKIP_PIXELS, 0);
        glCtx.pixelStorei(GLContext.GL_UNPACK_SKIP_ROWS, 0);
        glCtx.pixelStorei(GLContext.GL_UNPACK_ALIGNMENT, 1);
        return glCtx.texImage2D(GLContext.GL_TEXTURE_2D, 0, upload.internalFormat(), texWidth, texHeight, 0,
                GLContext.GL_ALPHA, GLContext.GL_UNSIGNED_BYTE, zeros, useMipmap);
    }

    /** A texture for a video frame: a MultiTexture of its planes for YCbCr 4:2:0. */
    public static Texture create(ES2Context context, MediaFrame frame) {
        frame.holdFrame();
        try {
            return frame.getPixelFormat() == PixelFormat.MULTI_YCbCr_420
                    ? createMultiTexture(context, frame)
                    : createFrameTexture(context, frame);
        } finally {
            frame.releaseFrame();
        }
    }

    /**
     * A MultiTexture of the actual size of the frame, with a texture of the
     * encoded size for each plane: 0 = luma, 1 = Cb, 2 = Cr, 3 (optional) =
     * alpha. The chroma planes are half the size.
     */
    private static Texture createMultiTexture(ES2Context context, MediaFrame frame) {
        MultiTexture tex = new MultiTexture(frame.getPixelFormat(), WrapMode.CLAMP_TO_EDGE,
                frame.getWidth(), frame.getHeight());
        for (int index = 0; index < frame.planeCount(); index++) {
            boolean chroma = index == PixelFormat.YCBCR_PLANE_CHROMABLUE
                    || index == PixelFormat.YCBCR_PLANE_CHROMARED;
            int width = chroma ? frame.getEncodedWidth() / 2 : frame.getEncodedWidth();
            int height = chroma ? frame.getEncodedHeight() / 2 : frame.getEncodedHeight();
            ES2Texture<?> plane = create(context, PixelFormat.BYTE_ALPHA, WrapMode.CLAMP_TO_EDGE, width, height,
                    false);
            if (plane != null) {
                tex.setTexture(plane, index);
            }
        }
        return tex;
    }

    /**
     * A texture of the encoded size of the frame, which may have the
     * padding that the color space conversion needs.
     */
    private static Texture createFrameTexture(ES2Context context, MediaFrame frame) {
        GLContext glCtx = context.getGLContext();
        PixelFormat format = frame.getPixelFormat();
        int texWidth = textureDimension(glCtx, frame.getEncodedWidth());
        int texHeight = textureDimension(glCtx, frame.getEncodedHeight());
        checkDimensions(texWidth, texHeight, format);
        ES2TextureData texData = allocate(context, texWidth, texHeight, format, bound -> {
            boolean created = uploadFrame(bound, frame, texWidth, texHeight, true);
            bound.texParamsMinMax(GLContext.GL_LINEAR, false);
            return created;
        });
        if (texData == null) {
            return null;
        }
        return new ES2Texture<>(context, new ES2TextureResource<>(texData), format, WrapMode.CLAMP_TO_EDGE,
                texWidth, texHeight, 0, 0, frame.getWidth(), frame.getHeight(), false);
    }

    /** A dimension, or the next power of two when the device cannot create other sizes. */
    private static int textureDimension(GLContext glCtx, int dimension) {
        return glCtx.canCreateNonPowTwoTextures()
                ? dimension : nextPowerOfTwo(dimension, glCtx.getMaxTextureSize());
    }

    // ---------------------------------------------------------------------
    // Uploads

    /**
     * Uploads a region of the pixels into the bound texture. Desktop OpenGL
     * has GL_UNPACK_ROW_LENGTH: the rows of the pixels need not be packed.
     */
    private static void uploadRegion(GLContext glCtx, Buffer pixels, PixelFormat format,
                                     int dstx, int dsty, int srcx, int srcy, int srcw, int srch, int srcscan) {
        Upload upload = Upload.of(format);
        int rowLength = srcscan / format.getBytesPerPixelUnit();
        glCtx.pixelStorei(GLContext.GL_UNPACK_ALIGNMENT, upload.alignment());
        glCtx.pixelStorei(GLContext.GL_UNPACK_ROW_LENGTH, srcw == rowLength ? 0 : rowLength);
        int position = pixels.position();
        int elementSizeLog = getBufferElementSizeLog(pixels);
        int elementsInPixel = format.getBytesPerPixelUnit() >> elementSizeLog;
        pixels.position(srcx * elementsInPixel + srcy * (srcscan >> elementSizeLog));
        glCtx.texSubImage2D(GLContext.GL_TEXTURE_2D, 0, dstx, dsty, srcw, srch,
                upload.pixelFormat(), upload.pixelType(), pixels);
        pixels.position(position);
    }

    /**
     * Uploads a video frame into the bound texture, after creating its
     * storage when create is true.
     */
    private static boolean uploadFrame(GLContext glCtx, MediaFrame frame, int texWidth, int texHeight,
                                       boolean create) {
        frame.holdFrame();
        try {
            ByteBuffer pixels = frame.getBufferForPlane(0);
            Upload upload = Upload.ofFrame(frame.getPixelFormat(),
                    pixels != null ? pixels.order() : ByteOrder.nativeOrder());
            boolean padded = frame.getEncodedWidth() != texWidth || frame.getEncodedHeight() != texHeight;
            boolean created = !create || createStorage(glCtx, upload, texWidth, texHeight, padded, false);
            if (pixels != null) {
                glCtx.pixelStorei(GLContext.GL_UNPACK_ALIGNMENT, upload.alignment());
                glCtx.pixelStorei(GLContext.GL_UNPACK_ROW_LENGTH, frame.strideForPlane(0) / upload.alignment());
                glCtx.texSubImage2D(GLContext.GL_TEXTURE_2D, 0, 0, 0, frame.getEncodedWidth(), frame.getHeight(),
                        upload.pixelFormat(), upload.pixelType(), pixels);
            }
            return created;
        } finally {
            frame.releaseFrame();
        }
    }

    public static int getBufferElementSizeLog(Buffer b) {
        return switch (b) {
            case ByteBuffer _ -> 0;
            case IntBuffer _, FloatBuffer _ -> 2;
            default -> throw new InternalError("Unsupported Buffer type: " + b.getClass());
        };
    }

    @Override
    public void update(Buffer pixels, PixelFormat format,
                       int dstx, int dsty,
                       int srcx, int srcy,
                       int srcw, int srch,
                       int srcscan,
                       boolean skipFlush) {
        checkUpdateParams(pixels, format, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
        if (!skipFlush) {
            context.flushVertexBuffer();
        }
        withTextureBoundForUpdate(glCtx -> {
            upload(glCtx, pixels, format, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
            switch (getWrapMode()) {
                case CLAMP_TO_EDGE_SIMULATED ->
                        repeatEdges(glCtx, pixels, format, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
                case REPEAT_SIMULATED ->
                        wrapEdges(glCtx, pixels, format, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
                default -> {
                }
            }
        });
    }

    /** Uploads a region of the pixels at a position of the content of the texture. */
    private void upload(GLContext glCtx, Buffer pixels, PixelFormat format, int dstx, int dsty,
                        int srcx, int srcy, int srcw, int srch, int srcscan) {
        uploadRegion(glCtx, pixels, format, getContentX() + dstx, getContentY() + dsty,
                srcx, srcy, srcw, srch, srcscan);
    }

    /**
     * Simulates CLAMP_TO_EDGE: the right column and the bottom row of the
     * content, when they were updated, are repeated in the column and the row
     * after the content.
     */
    private void repeatEdges(GLContext glCtx, Buffer pixels, PixelFormat format, int dstx, int dsty,
                             int srcx, int srcy, int srcw, int srch, int srcscan) {
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        boolean right = contentW < getPhysicalWidth() && dstx + srcw == contentW;
        boolean bottom = contentH < getPhysicalHeight() && dsty + srch == contentH;
        if (right) {
            upload(glCtx, pixels, format, contentW, dsty, srcx + srcw - 1, srcy, 1, srch, srcscan);
        }
        if (bottom) {
            upload(glCtx, pixels, format, dstx, contentH, srcx, srcy + srch - 1, srcw, 1, srcscan);
        }
        if (right && bottom) {
            upload(glCtx, pixels, format, contentW, contentH, srcx + srcw - 1, srcy + srch - 1, 1, 1, srcscan);
        }
    }

    /**
     * Simulates REPEAT: the left column and the top row of the content, when
     * they were updated, are repeated in the column and the row after the
     * content.
     */
    private void wrapEdges(GLContext glCtx, Buffer pixels, PixelFormat format, int dstx, int dsty,
                           int srcx, int srcy, int srcw, int srch, int srcscan) {
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        boolean left = contentW < getPhysicalWidth() && dstx == 0;
        boolean top = contentH < getPhysicalHeight() && dsty == 0;
        if (left) {
            upload(glCtx, pixels, format, contentW, dsty, srcx, srcy, 1, srch, srcscan);
        }
        if (top) {
            upload(glCtx, pixels, format, dstx, contentH, srcx, srcy, srcw, 1, srcscan);
        }
        if (left && top) {
            upload(glCtx, pixels, format, contentW, contentH, srcx, srcy, 1, 1, srcscan);
        }
    }

    @Override
    public void update(MediaFrame frame, boolean skipFlush) {
        if (!skipFlush) {
            context.flushVertexBuffer();
        }
        withTextureBoundForUpdate(glCtx -> uploadFrame(glCtx, frame, getPhysicalWidth(), getPhysicalHeight(), false));
    }

    /**
     * Runs an update with the texture bound to the active unit: the unit of
     * the first two where it is already bound, or the active unit. The
     * texture and the unit active before are restored.
     */
    private void withTextureBoundForUpdate(Consumer<GLContext> update) {
        int texID = getNativeSourceHandle();
        if (texID == 0) {
            return;
        }
        GLContext glCtx = context.getGLContext();
        Binding saved = new Binding(glCtx.getActiveTextureUnit(), glCtx.getBoundTexture());
        int unit = unitBinding(glCtx, texID);
        if (unit < 0) {
            glCtx.setBoundTexture(texID);
        } else if (unit != saved.unit()) {
            glCtx.setActiveTextureUnit(unit);
        }
        update.accept(glCtx);
        if (saved.unit() != glCtx.getActiveTextureUnit()) {
            glCtx.setActiveTextureUnit(saved.unit());
        }
        if (saved.texture() != glCtx.getBoundTexture()) {
            glCtx.setBoundTexture(saved.texture());
        }
    }

    /** The first of the two first units where the texture is bound, or -1. */
    private static int unitBinding(GLContext glCtx, int texID) {
        for (int unit = 0; unit < 2; unit++) {
            if (glCtx.getBoundTexture(unit) == texID) {
                return unit;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------
    // Sampling state

    /**
     * Applies the wrap mode to the texture, if it changed. The state set with
     * glTexParameteri stays with the texture.
     */
    void updateWrapState() {
        WrapMode wrapMode = getWrapMode();
        ES2TextureData texData = resource.getResource();
        if (texData.getWrapMode() != wrapMode) {
            withTextureBound(texID -> context.getGLContext().updateWrapState(texID, wrapMode));
            texData.setWrapMode(wrapMode);
        }
    }

    /** Applies the filter to the texture, if it changed. */
    void updateFilterState() {
        boolean linear = getLinearFiltering();
        ES2TextureData texData = resource.getResource();
        if (texData.isFiltered() != linear) {
            withTextureBound(texID -> context.getGLContext().updateFilterState(texID, linear));
            texData.setFiltered(linear);
        }
    }

    /** Runs an action with the texture bound to the active unit, then binds the texture bound before. */
    private void withTextureBound(IntConsumer action) {
        GLContext glCtx = context.getGLContext();
        int savedTex = glCtx.getBoundTexture();
        int texID = resource.getResource().getTexID();
        if (savedTex != texID) {
            glCtx.setBoundTexture(texID);
        }
        action.accept(texID);
        if (savedTex != texID) {
            glCtx.setBoundTexture(savedTex);
        }
    }

    public int getNativeSourceHandle() {
        return resource.getResource().getTexID();
    }
}
