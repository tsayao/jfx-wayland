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

import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;
import com.sun.glass.ui.gtk4.wrappers.GdkSurface;
import com.sun.glass.ui.gtk4.wrappers.Gtk;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Blurs the area behind a translucent toplevel with the Wayland
 * {@code ext-background-effect-v1} protocol.
 * <p>
 * Before GTK 4.24 GTK does not use the protocol, so the globals are bound on
 * GTK's Wayland connection with a private event queue, and the effect is
 * attached to the {@code wl_surface} of the window. The blur region is
 * double-buffered state that takes effect when GTK commits the surface.
 * <p>
 * GTK 4.24 attaches an effect to every surface itself and derives the blur
 * region from the CSS {@code backdrop-filter} of the widgets, and a surface
 * can only have one effect. The manager is then bound only to learn the
 * capabilities, and the blur is requested with CSS.
 */
final class Gtk4BackgroundEffect {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.STATE;

    private static final int CAPABILITY_BLUR = 1;

    private static final int GTK_MANAGED_MINOR_VERSION = 24;

    private static final int DISPLAY_GET_REGISTRY = 1;
    private static final int REGISTRY_BIND = 0;
    private static final int COMPOSITOR_CREATE_REGION = 1;
    private static final int REGION_DESTROY = 0;
    private static final int REGION_ADD = 1;
    private static final int REGION_SUBTRACT = 2;
    private static final int MANAGER_GET_BACKGROUND_EFFECT = 1;
    private static final int EFFECT_DESTROY = 0;
    private static final int EFFECT_SET_BLUR_REGION = 1;

    private static final int MARSHAL_FLAG_DESTROY = 1;

    private static final String MANAGER_NAME = "ext_background_effect_manager_v1";
    private static final String COMPOSITOR_NAME = "wl_compositor";

    // struct wl_message { name, signature, types }
    private static final long MESSAGE_SIZE = 3 * ValueLayout.ADDRESS.byteSize();
    // struct wl_interface { name, version, method_count, methods, event_count, events }
    private static final long INTERFACE_SIZE = 40;
    // union wl_argument
    private static final long ARGUMENT_SIZE = 8;

    private static boolean initialized;
    private static Wayland wl;
    private static MemorySegment display = MemorySegment.NULL;
    private static MemorySegment queue = MemorySegment.NULL;
    private static MemorySegment registry = MemorySegment.NULL;
    private static MemorySegment manager = MemorySegment.NULL;
    private static MemorySegment compositor = MemorySegment.NULL;
    private static int managerName;
    private static int capabilities;

    private MemorySegment effect;

    private Gtk4BackgroundEffect(MemorySegment effect) {
        this.effect = effect;
    }

    /**
     * Whether the compositor can blur the background of surfaces.
     */
    static boolean isSupported() {
        initialize();
        if (manager.equals(MemorySegment.NULL)) {
            return false;
        }
        dispatchPending();
        return !manager.equals(MemorySegment.NULL)
                && !compositor.equals(MemorySegment.NULL)
                && (capabilities & CAPABILITY_BLUR) != 0;
    }

    /**
     * Whether GTK owns the effect of every surface and blurs the area behind
     * the CSS {@code backdrop-filter} of the widgets.
     */
    static boolean isManagedByGtk() {
        return Gtk.getMinorVersion() >= GTK_MANAGED_MINOR_VERSION;
    }

    /**
     * Creates the effect for the surface of a toplevel, or returns null if
     * the compositor cannot blur or GTK owns the effect.
     */
    static Gtk4BackgroundEffect create(GdkSurface surface) {
        if (surface == null || surface.isNull() || isManagedByGtk() || !isSupported()) {
            return null;
        }
        try {
            MemorySegment wlSurface = (MemorySegment) wl.getWlSurface.invokeExact(surface.getPointer());
            if (wlSurface.equals(MemorySegment.NULL)) {
                return null;
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment args = arguments(arena, 2);
                args.setAtIndex(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
                args.setAtIndex(ValueLayout.ADDRESS, 1, wlSurface);
                MemorySegment effect = marshal(manager, MANAGER_GET_BACKGROUND_EFFECT, wl.effectInterface, 1, 0, args);
                if (effect.equals(MemorySegment.NULL)) {
                    return null;
                }
                Gtk4Log.log(LOG, "background effect created for surface %s", wlSurface);
                return new Gtk4BackgroundEffect(effect);
            }
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "cannot create the background effect: %s", t);
            return null;
        }
    }

    /**
     * Blurs the given rectangle in surface coordinates, leaving out the
     * corners outside a circle of the given radius.
     */
    void setBlurRegion(int x, int y, int width, int height, int radius) {
        if (effect == null || width <= 0 || height <= 0) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment args = arguments(arena, 4);
            args.setAtIndex(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment region = marshal(compositor, COMPOSITOR_CREATE_REGION, wl.regionInterface, 1, 0, args);
            if (region.equals(MemorySegment.NULL)) {
                return;
            }
            addRectangle(region, args, REGION_ADD, x, y, width, height);
            int r = Math.min(radius, Math.min(width, height) / 2);
            subtractCorners(region, args, x, y, width, height, r);
            args.setAtIndex(ValueLayout.ADDRESS, 0, region);
            marshal(effect, EFFECT_SET_BLUR_REGION, MemorySegment.NULL, 1, 0, args);
            marshal(region, REGION_DESTROY, MemorySegment.NULL, 1, MARSHAL_FLAG_DESTROY, args);
            Gtk4Log.log(LOG, "blur region %d,%d %dx%d radius %d", x, y, width, height, r);
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "cannot set the blur region: %s", t);
        }
    }

    /**
     * Leaves out of a region the corners outside a circle of a radius, a row
     * of pixels at a time.
     */
    private static void subtractCorners(MemorySegment region, MemorySegment args, int x, int y,
                                        int width, int height, int r) throws Throwable {
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt((double) r * r - dy * dy));
            if (inset <= 0) {
                continue;
            }
            addRectangle(region, args, REGION_SUBTRACT, x, y + row, inset, 1);
            addRectangle(region, args, REGION_SUBTRACT, x + width - inset, y + row, inset, 1);
            addRectangle(region, args, REGION_SUBTRACT, x, y + height - 1 - row, inset, 1);
            addRectangle(region, args, REGION_SUBTRACT, x + width - inset, y + height - 1 - row, inset, 1);
        }
    }

    /**
     * Removes the effect. The surface keeps no blur after its next commit.
     */
    void dispose() {
        if (effect == null) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            marshal(effect, EFFECT_DESTROY, MemorySegment.NULL, 1, MARSHAL_FLAG_DESTROY, arguments(arena, 1));
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "cannot destroy the background effect: %s", t);
        }
        effect = null;
    }

    private static void addRectangle(MemorySegment region, MemorySegment args, int opcode,
                                     int x, int y, int width, int height) throws Throwable {
        args.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);
        args.setAtIndex(ValueLayout.JAVA_LONG, 1, 0L);
        args.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
        args.setAtIndex(ValueLayout.JAVA_LONG, 3, 0L);
        args.set(ValueLayout.JAVA_INT, 0, x);
        args.set(ValueLayout.JAVA_INT, ARGUMENT_SIZE, y);
        args.set(ValueLayout.JAVA_INT, 2 * ARGUMENT_SIZE, width);
        args.set(ValueLayout.JAVA_INT, 3 * ARGUMENT_SIZE, height);
        marshal(region, opcode, MemorySegment.NULL, 1, 0, args);
    }

    private static MemorySegment arguments(Arena arena, int count) {
        return arena.allocate(ARGUMENT_SIZE * count, ARGUMENT_SIZE);
    }

    private static MemorySegment marshal(MemorySegment proxy, int opcode, MemorySegment iface,
                                         int version, int flags, MemorySegment args) throws Throwable {
        return (MemorySegment) wl.marshalArrayFlags.invokeExact(proxy, opcode, iface, version, flags, args);
    }

    private static void dispatchPending() {
        try {
            int ignored = (int) wl.dispatchQueuePending.invokeExact(display, queue);
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "cannot dispatch the background effect queue: %s", t);
        }
    }

    private static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            wl = Wayland.load();
            if (wl == null) {
                return;
            }
            MemorySegment gdkDisplay = GdkDisplay.getDefault().getPointer();
            if (!isWaylandDisplay(gdkDisplay)) {
                Gtk4Log.log(LOG, "background effects need a Wayland display");
                return;
            }
            display = (MemorySegment) wl.getWlDisplay.invokeExact(gdkDisplay);
            queue = (MemorySegment) wl.createQueue.invokeExact(display);
            registry = createRegistry();
            int ignored = (int) wl.addListener.invokeExact(registry, wl.registryListener, MemorySegment.NULL);
            ignored = (int) wl.roundtripQueue.invokeExact(display, queue);
            if (!manager.equals(MemorySegment.NULL)) {
                // Receive the capabilities
                ignored = (int) wl.roundtripQueue.invokeExact(display, queue);
            }
            Gtk4Log.log(LOG, "background effect manager %s, capabilities 0x%x",
                    manager.equals(MemorySegment.NULL) ? "unavailable" : "available", capabilities);
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "background effects are unavailable: %s", t);
            manager = MemorySegment.NULL;
        }
    }

    private static boolean isWaylandDisplay(MemorySegment gdkDisplay) throws Throwable {
        long waylandType = (long) wl.waylandDisplayGetType.invokeExact();
        return !gdkDisplay.equals(MemorySegment.NULL)
                && (int) wl.typeCheckInstanceIsA.invokeExact(gdkDisplay, waylandType) != 0;
    }

    /** The registry, created on a wrapper of the display so that its events go to the private queue. */
    private static MemorySegment createRegistry() throws Throwable {
        MemorySegment wrapper = (MemorySegment) wl.createWrapper.invokeExact(display);
        wl.setQueue.invokeExact(wrapper, queue);
        int version = (int) wl.getVersion.invokeExact(wrapper);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment args = arguments(arena, 1);
            args.setAtIndex(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            return marshal(wrapper, DISPLAY_GET_REGISTRY, wl.registryInterface, version, 0, args);
        } finally {
            wl.wrapperDestroy.invokeExact(wrapper);
        }
    }

    private static MemorySegment bind(int name, MemorySegment iface, int version) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ifaceName = iface.get(ValueLayout.ADDRESS, 0);
            MemorySegment args = arguments(arena, 4);
            args.setAtIndex(ValueLayout.JAVA_LONG, 0, 0L);
            args.set(ValueLayout.JAVA_INT, 0, name);
            args.setAtIndex(ValueLayout.ADDRESS, 1, ifaceName);
            args.setAtIndex(ValueLayout.JAVA_LONG, 2, 0L);
            args.set(ValueLayout.JAVA_INT, 2 * ARGUMENT_SIZE, version);
            args.setAtIndex(ValueLayout.ADDRESS, 3, MemorySegment.NULL);
            return marshal(registry, REGISTRY_BIND, iface, version, 0, args);
        }
    }

    // ---------------------------------------------------------------------
    // Listeners, called while the private queue is dispatched

    static void onGlobal(MemorySegment data, MemorySegment registry, int name,
                         MemorySegment iface, int version) {
        try {
            String interfaceName = iface.reinterpret(256).getString(0);
            if (MANAGER_NAME.equals(interfaceName) && manager.equals(MemorySegment.NULL)) {
                manager = bind(name, wl.managerInterface, 1);
                managerName = name;
                int ignored = (int) wl.addListener.invokeExact(manager, wl.managerListener, MemorySegment.NULL);
            } else if (COMPOSITOR_NAME.equals(interfaceName) && compositor.equals(MemorySegment.NULL)) {
                compositor = bind(name, wl.compositorInterface, 1);
            }
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "cannot bind %s: %s", iface, t);
        }
    }

    static void onGlobalRemove(MemorySegment data, MemorySegment registry, int name) {
        if (name == managerName && !manager.equals(MemorySegment.NULL)) {
            // Existing effects become inert; no new ones are created
            capabilities = 0;
        }
    }

    static void onCapabilities(MemorySegment data, MemorySegment manager, int flags) {
        capabilities = flags;
    }

    /**
     * The libwayland-client functions and the protocol interface definitions.
     */
    private static final class Wayland {
        final MethodHandle marshalArrayFlags;
        final MethodHandle addListener;
        final MethodHandle createQueue;
        final MethodHandle createWrapper;
        final MethodHandle wrapperDestroy;
        final MethodHandle setQueue;
        final MethodHandle getVersion;
        final MethodHandle roundtripQueue;
        final MethodHandle dispatchQueuePending;
        final MethodHandle getWlDisplay;
        final MethodHandle getWlSurface;
        final MethodHandle waylandDisplayGetType;
        final MethodHandle typeCheckInstanceIsA;

        final MemorySegment registryInterface;
        final MemorySegment compositorInterface;
        final MemorySegment regionInterface;
        final MemorySegment managerInterface;
        final MemorySegment effectInterface;
        final MemorySegment registryListener;
        final MemorySegment managerListener;

        private Wayland(SymbolLookup client, SymbolLookup gtk, SymbolLookup gobject) throws Exception {
            ValueLayout ptr = ValueLayout.ADDRESS;
            ValueLayout i32 = ValueLayout.JAVA_INT;
            marshalArrayFlags = function(client, "wl_proxy_marshal_array_flags",
                    FunctionDescriptor.of(ptr, ptr, i32, ptr, i32, i32, ptr));
            addListener = function(client, "wl_proxy_add_listener", FunctionDescriptor.of(i32, ptr, ptr, ptr));
            createQueue = function(client, "wl_display_create_queue", FunctionDescriptor.of(ptr, ptr));
            createWrapper = function(client, "wl_proxy_create_wrapper", FunctionDescriptor.of(ptr, ptr));
            wrapperDestroy = function(client, "wl_proxy_wrapper_destroy", FunctionDescriptor.ofVoid(ptr));
            setQueue = function(client, "wl_proxy_set_queue", FunctionDescriptor.ofVoid(ptr, ptr));
            getVersion = function(client, "wl_proxy_get_version", FunctionDescriptor.of(i32, ptr));
            roundtripQueue = function(client, "wl_display_roundtrip_queue", FunctionDescriptor.of(i32, ptr, ptr));
            dispatchQueuePending = function(client, "wl_display_dispatch_queue_pending",
                    FunctionDescriptor.of(i32, ptr, ptr));
            getWlDisplay = function(gtk, "gdk_wayland_display_get_wl_display", FunctionDescriptor.of(ptr, ptr));
            getWlSurface = function(gtk, "gdk_wayland_surface_get_wl_surface", FunctionDescriptor.of(ptr, ptr));
            waylandDisplayGetType = function(gtk, "gdk_wayland_display_get_type",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            typeCheckInstanceIsA = function(gobject, "g_type_check_instance_is_a",
                    FunctionDescriptor.of(i32, ptr, ValueLayout.JAVA_LONG));

            registryInterface = interfaceOf(client, "wl_registry_interface");
            compositorInterface = interfaceOf(client, "wl_compositor_interface");
            regionInterface = interfaceOf(client, "wl_region_interface");
            Arena arena = Arena.global();
            managerInterface = arena.allocate(INTERFACE_SIZE, 8);
            effectInterface = arena.allocate(INTERFACE_SIZE, 8);
            defineEffectInterfaces(arena, interfaceOf(client, "wl_surface_interface"));

            registryListener = pointers(arena,
                    listener(arena, "onGlobal", FunctionDescriptor.ofVoid(ptr, ptr, i32, ptr, i32)),
                    listener(arena, "onGlobalRemove", FunctionDescriptor.ofVoid(ptr, ptr, i32)));
            managerListener = pointers(arena, listener(arena, "onCapabilities", FunctionDescriptor.ofVoid(ptr, ptr, i32)));
        }

        private static MethodHandle function(SymbolLookup library, String name, FunctionDescriptor descriptor) {
            return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(), descriptor);
        }

        private static MemorySegment interfaceOf(SymbolLookup client, String name) {
            return client.find(name).orElseThrow().reinterpret(INTERFACE_SIZE);
        }

        /** An event of a listener, handled by a static method of the same name. */
        private static MemorySegment listener(Arena arena, String method, FunctionDescriptor descriptor)
                throws ReflectiveOperationException {
            MethodHandle handle = MethodHandles.lookup().findStatic(Gtk4BackgroundEffect.class, method,
                    descriptor.toMethodType());
            return Linker.nativeLinker().upcallStub(handle, descriptor, arena);
        }

        /** The interfaces of ext-background-effect-v1, which libwayland-client does not know. */
        private void defineEffectInterfaces(Arena arena, MemorySegment surfaceInterface) {
            defineInterface(arena, managerInterface, MANAGER_NAME,
                    new String[][] {{"destroy", ""}, {"get_background_effect", "no"}},
                    new MemorySegment[][] {{}, {effectInterface, surfaceInterface}},
                    new String[][] {{"capabilities", "u"}},
                    new MemorySegment[][] {{MemorySegment.NULL}});
            defineInterface(arena, effectInterface, "ext_background_effect_surface_v1",
                    new String[][] {{"destroy", ""}, {"set_blur_region", "?o"}},
                    new MemorySegment[][] {{}, {regionInterface}},
                    new String[0][], new MemorySegment[0][]);
        }

        static Wayland load() {
            try {
                Arena arena = Arena.global();
                SymbolLookup client = SymbolLookup.libraryLookup("libwayland-client.so.0", arena);
                SymbolLookup gtk = SymbolLookup.libraryLookup("libgtk-4.so.1", arena);
                SymbolLookup gobject = SymbolLookup.libraryLookup("libgobject-2.0.so.0", arena);
                return new Wayland(client, gtk, gobject);
            } catch (Throwable t) {
                Gtk4Log.log(LOG, "libwayland-client is unavailable: %s", t);
                return null;
            }
        }

        private static MemorySegment pointers(Arena arena, MemorySegment... values) {
            MemorySegment array = arena.allocate(ValueLayout.ADDRESS, Math.max(values.length, 1));
            for (int i = 0; i < values.length; i++) {
                array.setAtIndex(ValueLayout.ADDRESS, i, values[i]);
            }
            return array;
        }

        private static MemorySegment messages(Arena arena, String[][] specs, MemorySegment[][] types) {
            if (specs.length == 0) {
                return MemorySegment.NULL;
            }
            MemorySegment array = arena.allocate(MESSAGE_SIZE * specs.length, 8);
            for (int i = 0; i < specs.length; i++) {
                long base = i * MESSAGE_SIZE;
                array.set(ValueLayout.ADDRESS, base, arena.allocateFrom(specs[i][0]));
                array.set(ValueLayout.ADDRESS, base + 8, arena.allocateFrom(specs[i][1]));
                array.set(ValueLayout.ADDRESS, base + 16, pointers(arena, types[i]));
            }
            return array;
        }

        private static void defineInterface(Arena arena, MemorySegment iface, String name,
                                            String[][] requests, MemorySegment[][] requestTypes,
                                            String[][] events, MemorySegment[][] eventTypes) {
            iface.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(name));
            iface.set(ValueLayout.JAVA_INT, 8, 1);
            iface.set(ValueLayout.JAVA_INT, 12, requests.length);
            iface.set(ValueLayout.ADDRESS, 16, messages(arena, requests, requestTypes));
            iface.set(ValueLayout.JAVA_INT, 24, events.length);
            iface.set(ValueLayout.ADDRESS, 32, messages(arena, events, eventTypes));
        }
    }
}
