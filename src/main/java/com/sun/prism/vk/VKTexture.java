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

import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.impl.BaseTexture;

import java.lang.foreign.MemorySegment;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

class VKTexture<T extends VKTextureData> extends BaseTexture<VKTextureResource<T>> {

    private final VKContext context;
    private final VKImage image;

    VKTexture(VKContext context, VKTextureResource<T> resource, PixelFormat format, WrapMode wrapMode,
              int physicalWidth, int physicalHeight,
              int contentX, int contentY, int contentWidth, int contentHeight, boolean useMipmap) {
        super(resource, format, wrapMode, physicalWidth, physicalHeight,
              contentX, contentY, contentWidth, contentHeight, useMipmap);
        this.context = context;
        this.image = resource.getResource().getResource();
    }

    VKTexture(VKContext context, VKTextureResource<T> resource, PixelFormat format, WrapMode wrapMode,
              int physicalWidth, int physicalHeight,
              int contentX, int contentY, int contentWidth, int contentHeight,
              int maxContentWidth, int maxContentHeight, boolean useMipmap) {
        super(resource, format, wrapMode, physicalWidth, physicalHeight,
              contentX, contentY, contentWidth, contentHeight,
              maxContentWidth, maxContentHeight, useMipmap);
        this.context = context;
        this.image = resource.getResource().getResource();
    }

    private VKTexture(VKTexture<T> sharedTex, WrapMode newMode) {
        super(sharedTex, newMode, false);
        this.context = sharedTex.context;
        this.image = sharedTex.image;
    }

    static int nativeFormat(PixelFormat format) {
        return switch (format) {
            case BYTE_ALPHA -> VKImage.FORMAT_ALPHA8;
            case BYTE_GRAY -> VKImage.FORMAT_GRAY8;
            case FLOAT_XYZW -> VKImage.FORMAT_RGBA32F;
            default -> VKImage.FORMAT_BGRA8;
        };
    }

    VKImage getImage() {
        return image;
    }

    VKContext getContext() {
        return context;
    }

    @Override
    protected Texture createSharedTexture(WrapMode newMode) {
        return new VKTexture<>(this, newMode);
    }

    @Override
    public void update(Buffer buffer, PixelFormat format,
                       int dstx, int dsty, int srcx, int srcy,
                       int srcw, int srch, int srcscan, boolean skipFlush) {
        if (!resource.isValid()) {
            return;
        }
        if (!skipFlush) {
            // The quads batched before the update, which may sample the texture,
            // are drawn with its old pixels
            context.flushVertexBuffer();
        }
        switch (format) {
            case BYTE_RGB -> updateRGB((ByteBuffer) buffer, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
            default -> upload(buffer, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
        }
    }

    /**
     * Uploads pixels whose format matches the native texture format.
     */
    private void upload(Buffer buffer, int dstx, int dsty, int srcx, int srcy,
                        int srcw, int srch, int srcscan) {
        // The segment starts at the position of the buffer; the scan is in bytes
        MemorySegment src = MemorySegment.ofBuffer(buffer);
        context.getDevice().updateImage(image, src, dstx, dsty, srcx, srcy, srcw, srch, srcscan);
    }

    private void updateRGB(ByteBuffer buffer, int dstx, int dsty, int srcx, int srcy,
                           int srcw, int srch, int srcscan) {
        byte[] bgra = new byte[srcw * srch * 4];
        int base = buffer.position();
        for (int y = 0; y < srch; y++) {
            int src = base + (srcy + y) * srcscan + srcx * 3;
            int dst = y * srcw * 4;
            for (int x = 0; x < srcw; x++) {
                bgra[dst++] = buffer.get(src + 2);
                bgra[dst++] = buffer.get(src + 1);
                bgra[dst++] = buffer.get(src);
                bgra[dst++] = (byte) 0xff;
                src += 3;
            }
        }
        context.getDevice().updateImage(image, MemorySegment.ofArray(bgra), dstx, dsty, 0, 0, srcw, srch, srcw * 4);
    }

    @Override
    public void update(MediaFrame frame, boolean skipFlush) {
        if (!resource.isValid()) {
            return;
        }
        frame.holdFrame();
        try {
            ByteBuffer pixels = frame.getBufferForPlane(0);
            update(pixels, frame.getPixelFormat(), 0, 0, 0, 0,
                   frame.getEncodedWidth(), frame.getEncodedHeight(), frame.strideForPlane(0), skipFlush);
        } finally {
            frame.releaseFrame();
        }
    }
}
