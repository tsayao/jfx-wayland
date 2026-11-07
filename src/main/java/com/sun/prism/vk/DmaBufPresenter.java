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

public interface DmaBufPresenter {

    boolean isComposited();

    /**
     * Shows a frame in a dma-buf, whose descriptor stays owned by the caller.
     *
     * @param release called when the window no longer reads the buffer
     * @return false when the window cannot show the frame, as when it could
     *     not import an earlier one: the caller presents its pixels instead
     */
    boolean publishDmaBuf(int fd, int stride, int offset, int fourcc, long modifier,
                          int width, int height, Runnable release);

    /**
     * Asks for the whole window to be painted again, as a frame that could not
     * be shown was dropped. Called on any thread.
     */
    void requestRepaint();
}
