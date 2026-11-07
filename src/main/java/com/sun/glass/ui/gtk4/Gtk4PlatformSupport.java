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

import com.sun.glass.ui.gtk4.wrappers.GNetworkMonitor;
import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GdkRGBA;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkBox;
import com.sun.glass.ui.gtk4.wrappers.GtkSettings;
import com.sun.glass.ui.gtk4.wrappers.GtkStyleContext;
import com.sun.javafx.application.preferences.PreferenceMapping;
import javafx.scene.paint.Color;

import java.lang.foreign.Arena;
import java.lang.invoke.MethodHandles;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Collects the platform preferences and reports changes. The keys are the same
 * as those of the GTK 3 toolkit.
 */
final class Gtk4PlatformSupport {

    private static final String[] COLORS = {
        "theme_fg_color", "theme_bg_color", "theme_base_color",
        "theme_selected_bg_color", "theme_selected_fg_color",
        "insensitive_bg_color", "insensitive_fg_color", "insensitive_base_color",
        "theme_unfocused_fg_color", "theme_unfocused_bg_color", "theme_unfocused_base_color",
        "theme_unfocused_selected_bg_color", "theme_unfocused_selected_fg_color",
        "borders", "unfocused_borders", "warning_color", "error_color", "success_color"
    };

    private static final String[] OBSERVED_SETTINGS = {
        "gtk-theme-name", "gtk-enable-animations", "gtk-overlay-scrolling",
        "gtk-interface-color-scheme", "gtk-application-prefer-dark-theme"
    };

    private final Consumer<Map<String, Object>> changeHandler;
    private final Gtk4SignalHelper signals = new Gtk4SignalHelper(Arena.global(), MethodHandles.lookup());
    private Map<String, Object> preferences;

    Gtk4PlatformSupport(Consumer<Map<String, Object>> changeHandler) {
        this.changeHandler = changeHandler;
        GtkSettings settings = GtkSettings.getDefault();
        if (settings != null && !settings.isNull()) {
            for (String name : OBSERVED_SETTINGS) {
                if (settings.hasProperty(name)) {
                    signals.connect(settings, "notify::" + name, this, "onChanged",
                            void.class, GObject.class);
                }
            }
        }
        GNetworkMonitor monitor = GNetworkMonitor.getDefault();
        if (!monitor.isNull()) {
            signals.connect(monitor.cast(GObject::new), "network-changed", this, "onChanged",
                    void.class, GObject.class);
        }
    }

    Map<String, Object> getPreferences() {
        if (preferences == null) {
            preferences = Collections.unmodifiableMap(collect());
        }
        return preferences;
    }

    @SuppressWarnings("unused")
    void onChanged(GObject source) {
        Map<String, Object> newPreferences = collect();
        if (!newPreferences.equals(preferences)) {
            preferences = Collections.unmodifiableMap(newPreferences);
            changeHandler.accept(preferences);
        }
    }

    private static Map<String, Object> collect() {
        Map<String, Object> prefs = new HashMap<>();
        collectColors(prefs);
        collectSettings(prefs);
        GNetworkMonitor monitor = GNetworkMonitor.getDefault();
        if (!monitor.isNull()) {
            prefs.put("GTK.network_metered", monitor.getNetworkMetered());
        }
        return prefs;
    }

    /** The named colors of the theme, resolved through the style context of a widget. */
    private static void collectColors(Map<String, Object> prefs) {
        GtkBox widget = GtkBox.create(Gtk.ORIENTATION_VERTICAL, 0);
        widget.refSink();
        try {
            GtkStyleContext style = widget.getStyleContext();
            GdkRGBA rgba = new GdkRGBA(0, 0, 0, 0);
            for (String name : COLORS) {
                if (style.lookupColor(name, rgba)) {
                    prefs.put("GTK." + name, Color.color(clamp(rgba.red()), clamp(rgba.green()), clamp(rgba.blue())));
                }
            }
        } finally {
            widget.unref();
        }
    }

    /** The theme name, and whether animations and overlay scrolling are on. */
    private static void collectSettings(Map<String, Object> prefs) {
        GtkSettings settings = GtkSettings.getDefault();
        if (settings == null || settings.isNull()) {
            return;
        }
        String themeName = settings.getStringProperty("gtk-theme-name");
        if (themeName != null) {
            prefs.put("GTK.theme_name", themeName);
        }
        prefs.put("GTK.enable_animations", settings.getBooleanProperty("gtk-enable-animations"));
        if (settings.hasProperty("gtk-overlay-scrolling")) {
            prefs.put("GTK.overlay_scrolling", settings.getBooleanProperty("gtk-overlay-scrolling"));
        }
    }

    private static double clamp(float value) {
        return Math.clamp(value, 0.0, 1.0);
    }

    static Map<String, PreferenceMapping<?, ?>> getKeyMappings() {
        return Map.of(
            "GTK.theme_fg_color", new PreferenceMapping<>("foregroundColor", Color.class),
            "GTK.theme_bg_color", new PreferenceMapping<>("backgroundColor", Color.class),
            "GTK.theme_selected_bg_color", new PreferenceMapping<>("accentColor", Color.class),
            "GTK.enable_animations", new PreferenceMapping<>("reducedMotion", Boolean.class, b -> !b),
            "GTK.overlay_scrolling", new PreferenceMapping<>("persistentScrollBars", Boolean.class, b -> !b),
            "GTK.network_metered", new PreferenceMapping<>("reducedData", Boolean.class)
        );
    }

    static Map<String, Class<?>> getKeys() {
        Map<String, Class<?>> keys = new HashMap<>();
        keys.put("GTK.theme_name", String.class);
        for (String name : COLORS) {
            keys.put("GTK." + name, Color.class);
        }
        keys.put("GTK.enable_animations", Boolean.class);
        keys.put("GTK.overlay_scrolling", Boolean.class);
        keys.put("GTK.network_metered", Boolean.class);
        return Collections.unmodifiableMap(keys);
    }
}
