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
package com.sun.glass.ui.gtk4;

import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkCustomLayout;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The layout of the widget that holds the view. Glass decides its minimum and
 * natural size: GTK would otherwise derive the natural size from the rendered
 * texture, whose size is in physical pixels, so that windows sized by their
 * content (popups and windows that are not resizable) would keep growing on
 * scaled outputs.
 */
final class Gtk4ContentLayout {

    private static final FunctionDescriptor MEASURE = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor ALLOCATE = FunctionDescriptor.ofVoid(
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private final GtkWidget widget;
    private final GtkCustomLayout layout;

    private int minWidth = 1;
    private int minHeight = 1;
    private int naturalWidth = 1;
    private int naturalHeight = 1;

    Gtk4ContentLayout(GtkWidget widget, Arena arena) {
        this.widget = widget;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            MemorySegment measure = Gtk4Upcalls.stub(
                    lookup.bind(this, "measure", MethodType.methodType(void.class,
                            MemorySegment.class, int.class, int.class,
                            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                    MEASURE, arena);
            MemorySegment allocate = Gtk4Upcalls.stub(
                    lookup.bind(this, "allocate", MethodType.methodType(void.class,
                            MemorySegment.class, int.class, int.class, int.class)),
                    ALLOCATE, arena);
            layout = GtkCustomLayout.create(MemorySegment.NULL, measure, allocate);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        widget.setLayoutManager(layout);
    }

    /**
     * Sets the size range of the content: the minimum, and the natural size used
     * when the content determines the size of its window.
     */
    void setSize(int minWidth, int minHeight, int naturalWidth, int naturalHeight) {
        int mw = Math.max(minWidth, 1);
        int mh = Math.max(minHeight, 1);
        int nw = Math.max(naturalWidth, mw);
        int nh = Math.max(naturalHeight, mh);
        if (mw != this.minWidth || mh != this.minHeight || nw != this.naturalWidth || nh != this.naturalHeight) {
            this.minWidth = mw;
            this.minHeight = mh;
            this.naturalWidth = nw;
            this.naturalHeight = nh;
            layout.layoutChanged();
        }
    }

    @SuppressWarnings("unused")
    private void measure(MemorySegment w, int orientation, int forSize,
                         MemorySegment minimum, MemorySegment natural,
                         MemorySegment minimumBaseline, MemorySegment naturalBaseline) {
        boolean horizontal = orientation == Gtk.ORIENTATION_HORIZONTAL;
        set(minimum, horizontal ? minWidth : minHeight);
        set(natural, horizontal ? naturalWidth : naturalHeight);
        set(minimumBaseline, -1);
        set(naturalBaseline, -1);
    }

    private static void set(MemorySegment ptr, int value) {
        if (ptr.address() != 0) {
            ptr.reinterpret(ValueLayout.JAVA_INT.byteSize()).set(ValueLayout.JAVA_INT, 0, value);
        }
    }

    @SuppressWarnings("unused")
    private void allocate(MemorySegment w, int width, int height, int baseline) {
        for (GtkWidget child = widget.getFirstChild(); !child.isNull(); child = child.getNextSibling()) {
            if (child.shouldLayout()) {
                child.allocate(width, height, -1, MemorySegment.NULL);
            }
        }
    }
}
