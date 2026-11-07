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

import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Size;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkCursor;
import com.sun.glass.ui.gtk4.wrappers.GdkTexture;
import com.sun.glass.ui.gtk4.wrappers.GtkSettings;

import java.util.HashMap;
import java.util.Map;

final class Gtk4Cursor extends Cursor {

    private static final int DEFAULT_CURSOR_SIZE = 24;
    private static final Map<String, GdkCursor> namedCursors = new HashMap<>();

    private GdkCursor gdkCursor;

    Gtk4Cursor(int type) {
        super(type);
    }

    Gtk4Cursor(int x, int y, Pixels pixels) {
        super(x, y, pixels);
    }

    @Override
    protected long _createCursor(int x, int y, Pixels pixels) {
        GdkTexture texture = Gtk4Pixels.toTexture(pixels);
        if (texture == null) {
            return 0L;
        }
        gdkCursor = GdkCursor.createFromTexture(texture, x, y, named("default"));
        texture.unref();
        return gdkCursor.getPointer().address();
    }

    /**
     * The GDK cursor for this cursor; {@code null} selects the default cursor.
     */
    GdkCursor getGdkCursor() {
        if (getType() == CURSOR_CUSTOM) {
            // Glass creates a custom cursor when its native cursor is asked for
            getNativeCursor();
            return gdkCursor;
        }
        return getType() == CURSOR_DEFAULT ? null : named(cursorName(getType()));
    }

    static GdkCursor hiddenCursor() {
        return named("none");
    }

    static GdkCursor edgeCursor(int edge) {
        return named(switch (edge) {
            case Gdk.SURFACE_EDGE_NORTH_WEST -> "nw-resize";
            case Gdk.SURFACE_EDGE_NORTH -> "n-resize";
            case Gdk.SURFACE_EDGE_NORTH_EAST -> "ne-resize";
            case Gdk.SURFACE_EDGE_WEST -> "w-resize";
            case Gdk.SURFACE_EDGE_EAST -> "e-resize";
            case Gdk.SURFACE_EDGE_SOUTH_WEST -> "sw-resize";
            case Gdk.SURFACE_EDGE_SOUTH -> "s-resize";
            default -> "se-resize";
        });
    }

    private static GdkCursor named(String name) {
        return namedCursors.computeIfAbsent(name, n -> GdkCursor.createFromName(n, null));
    }

    static Size bestSize(int width, int height) {
        int size = GtkSettings.getDefault().getIntProperty("gtk-cursor-theme-size");
        if (size <= 0) {
            size = DEFAULT_CURSOR_SIZE;
        }
        return new Size(size, size);
    }

    private static String cursorName(int type) {
        return switch (type) {
            case CURSOR_CROSSHAIR -> "crosshair";
            case CURSOR_TEXT -> "text";
            case CURSOR_WAIT -> "wait";
            case CURSOR_RESIZE_SOUTHWEST -> "sw-resize";
            case CURSOR_RESIZE_SOUTHEAST -> "se-resize";
            case CURSOR_RESIZE_NORTHWEST -> "nw-resize";
            case CURSOR_RESIZE_NORTHEAST -> "ne-resize";
            case CURSOR_RESIZE_UP -> "n-resize";
            case CURSOR_RESIZE_DOWN -> "s-resize";
            case CURSOR_RESIZE_LEFT -> "w-resize";
            case CURSOR_RESIZE_RIGHT -> "e-resize";
            case CURSOR_RESIZE_LEFTRIGHT -> "ew-resize";
            case CURSOR_RESIZE_UPDOWN -> "ns-resize";
            case CURSOR_OPEN_HAND -> "grab";
            case CURSOR_CLOSED_HAND -> "grabbing";
            case CURSOR_POINTING_HAND -> "pointer";
            case CURSOR_MOVE -> "move";
            case CURSOR_DISAPPEAR, CURSOR_NONE -> "none";
            default -> "default";
        };
    }
}
