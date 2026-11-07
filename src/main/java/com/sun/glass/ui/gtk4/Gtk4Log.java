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

import java.util.Locale;
import java.util.Set;

final class Gtk4Log {

    enum Category {
        SIZING,
        STATE,
        LIFECYCLE,
        POPUP,
        DRAG_DROP,
        CLIPBOARD,
        POINTER,
        POINTER_MOTION,
        TOUCH,
        KEYS,
        HEADERBAR,
        IME,
        COMPOSITOR,
        SCREEN,
        FOCUS
    }

    private static final boolean NONE_ENABLED;
    private static final Set<Category> ENABLED;

    static {
        String value = System.getProperty("glass.gtk4.verbose");

        if (value != null && !value.isEmpty()) {
            String[] tokens = value.split(",");
            Set<Category> categories = new java.util.HashSet<>();
            for (String token : tokens) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if ("all".equals(t)) {
                    categories.addAll(Set.of(Category.values()));
                    break;
                }
                try {
                    categories.add(Category.valueOf(t.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    System.err.println("[Gtk4Log] Unknown verbose category: " + token.trim());
                }
            }
            ENABLED = categories.isEmpty() ? Set.of() : Set.copyOf(categories);
        } else {
            ENABLED = Set.of();
        }
        NONE_ENABLED = ENABLED.isEmpty();
    }

    private Gtk4Log() {
    }

    static boolean isEnabled(Category category) {
        return !NONE_ENABLED && ENABLED.contains(category);
    }

    static void log(Category category, String message) {
        if (NONE_ENABLED) return;
        if (ENABLED.contains(category)) {
            System.out.println("[GTK4 " + category.name() + "] " + message);
        }
    }

    static void log(Category category, String format, Object... args) {
        if (NONE_ENABLED) return;
        if (ENABLED.contains(category)) {
            System.out.println("[GTK4 " + category.name() + "] "
                    + String.format(format, args));
        }
    }
}

