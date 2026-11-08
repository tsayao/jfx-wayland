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

/**
 * The sizes of the color and depth buffers of the EGL configuration, from
 * prism.glBufferSize (32, 24 or 16 bits) and prism.glDepthSize, as in the
 * ES2 pipeline.
 */
record GLPixelFormat(int redSize, int greenSize, int blueSize, int alphaSize, int depthSize) {

    static GLPixelFormat fromSettings() {
        int depth = Integer.getInteger("prism.glDepthSize", 24);
        return switch (Integer.getInteger("prism.glBufferSize", 32)) {
            case 32 -> new GLPixelFormat(8, 8, 8, 8, depth);
            case 24 -> new GLPixelFormat(8, 8, 8, 0, depth);
            case 16 -> new GLPixelFormat(5, 6, 5, 0, depth);
            default -> throw new IllegalArgumentException("color buffer size "
                    + Integer.getInteger("prism.glBufferSize") + " not supported");
        };
    }
}
