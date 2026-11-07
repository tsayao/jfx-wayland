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

package com.sun.glass.ui.gtk4.wrappers;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * The sender side of libei, which has no introspection data.
 */
public final class Ei {

    public static final int EVENT_CONNECT = 1;
    public static final int EVENT_DISCONNECT = 2;
    public static final int EVENT_SEAT_ADDED = 3;
    public static final int EVENT_SEAT_REMOVED = 4;
    public static final int EVENT_DEVICE_ADDED = 5;
    public static final int EVENT_DEVICE_REMOVED = 6;
    public static final int EVENT_DEVICE_PAUSED = 7;
    public static final int EVENT_DEVICE_RESUMED = 8;
    public static final int EVENT_PONG = 90;

    public static final int DEVICE_CAP_POINTER = 1 << 0;
    public static final int DEVICE_CAP_POINTER_ABSOLUTE = 1 << 1;
    public static final int DEVICE_CAP_KEYBOARD = 1 << 2;
    public static final int DEVICE_CAP_TOUCH = 1 << 3;
    public static final int DEVICE_CAP_SCROLL = 1 << 4;
    public static final int DEVICE_CAP_BUTTON = 1 << 5;

    private Ei() {
    }

    public static boolean isAvailable() {
        return H.LIB != null;
    }

    public static MemorySegment newSender() {
        return (MemorySegment) invoke(H.NEW_SENDER, MemorySegment.NULL);
    }

    public static void configureName(MemorySegment ei, String name) {
        try (Arena arena = Arena.ofConfined()) {
            invoke(H.CONFIGURE_NAME, ei, arena.allocateFrom(name));
        }
    }

    public static int setupBackendFd(MemorySegment ei, int fd) {
        return (int) invoke(H.SETUP_BACKEND_FD, ei, fd);
    }

    public static void dispatch(MemorySegment ei) {
        invoke(H.DISPATCH, ei);
    }

    public static MemorySegment getEvent(MemorySegment ei) {
        return (MemorySegment) invoke(H.GET_EVENT, ei);
    }

    public static long now(MemorySegment ei) {
        return (long) invoke(H.NOW, ei);
    }

    public static void unref(MemorySegment ei) {
        invoke(H.UNREF, ei);
    }

    public static int eventGetType(MemorySegment event) {
        return (int) invoke(H.EVENT_GET_TYPE, event);
    }

    public static MemorySegment eventGetSeat(MemorySegment event) {
        return (MemorySegment) invoke(H.EVENT_GET_SEAT, event);
    }

    public static MemorySegment eventGetDevice(MemorySegment event) {
        return (MemorySegment) invoke(H.EVENT_GET_DEVICE, event);
    }

    public static void eventUnref(MemorySegment event) {
        invoke(H.EVENT_UNREF, event);
    }

    /** Whether this libei has pings (1.3), which tell when the compositor processed the input. */
    public static boolean isPingAvailable() {
        return H.NEW_PING != null && H.PING != null && H.PING_UNREF != null && H.EVENT_PONG_GET_PING != null;
    }

    public static MemorySegment newPing(MemorySegment ei) {
        return (MemorySegment) invoke(H.NEW_PING, ei);
    }

    public static void ping(MemorySegment ping) {
        invoke(H.PING, ping);
    }

    public static void pingUnref(MemorySegment ping) {
        invoke(H.PING_UNREF, ping);
    }

    public static MemorySegment eventPongGetPing(MemorySegment event) {
        return (MemorySegment) invoke(H.EVENT_PONG_GET_PING, event);
    }

    public static void seatBindCapabilities(MemorySegment seat, int... capabilities) {
        for (int capability : capabilities) {
            invoke(H.SEAT_BIND_CAPABILITIES, seat, capability, MemorySegment.NULL);
        }
    }

    public static MemorySegment deviceRef(MemorySegment device) {
        return (MemorySegment) invoke(H.DEVICE_REF, device);
    }

    public static void deviceUnref(MemorySegment device) {
        invoke(H.DEVICE_UNREF, device);
    }

    public static boolean deviceHasCapability(MemorySegment device, int capability) {
        return (boolean) invoke(H.DEVICE_HAS_CAPABILITY, device, capability);
    }

    public static MemorySegment deviceGetRegion(MemorySegment device, long index) {
        return (MemorySegment) invoke(H.DEVICE_GET_REGION, device, index);
    }

    public static int regionGetX(MemorySegment region) {
        return (int) invoke(H.REGION_GET_X, region);
    }

    public static int regionGetY(MemorySegment region) {
        return (int) invoke(H.REGION_GET_Y, region);
    }

    public static int regionGetWidth(MemorySegment region) {
        return (int) invoke(H.REGION_GET_WIDTH, region);
    }

    public static int regionGetHeight(MemorySegment region) {
        return (int) invoke(H.REGION_GET_HEIGHT, region);
    }

    public static void deviceStartEmulating(MemorySegment device, int sequence) {
        invoke(H.DEVICE_START_EMULATING, device, sequence);
    }

    public static void deviceFrame(MemorySegment device, long time) {
        invoke(H.DEVICE_FRAME, device, time);
    }

    public static void devicePointerMotionAbsolute(MemorySegment device, double x, double y) {
        invoke(H.DEVICE_POINTER_MOTION_ABSOLUTE, device, x, y);
    }

    public static void deviceButtonButton(MemorySegment device, int button, boolean press) {
        invoke(H.DEVICE_BUTTON_BUTTON, device, button, press);
    }

    public static void deviceScrollDiscrete(MemorySegment device, int x, int y) {
        invoke(H.DEVICE_SCROLL_DISCRETE, device, x, y);
    }

    public static void deviceKeyboardKey(MemorySegment device, int keycode, boolean press) {
        invoke(H.DEVICE_KEYBOARD_KEY, device, keycode, press);
    }

    /** Whether this libei can emulate touch screens. */
    public static boolean isTouchAvailable() {
        return H.DEVICE_TOUCH_NEW != null && H.TOUCH_DOWN != null && H.TOUCH_MOTION != null && H.TOUCH_UP != null
                && H.TOUCH_UNREF != null;
    }

    public static MemorySegment deviceTouchNew(MemorySegment device) {
        return (MemorySegment) invoke(H.DEVICE_TOUCH_NEW, device);
    }

    public static void touchDown(MemorySegment touch, double x, double y) {
        invoke(H.TOUCH_DOWN, touch, x, y);
    }

    public static void touchMotion(MemorySegment touch, double x, double y) {
        invoke(H.TOUCH_MOTION, touch, x, y);
    }

    public static void touchUp(MemorySegment touch) {
        invoke(H.TOUCH_UP, touch);
    }

    public static void touchUnref(MemorySegment touch) {
        invoke(H.TOUCH_UNREF, touch);
    }

    private static Object invoke(MethodHandle handle, Object... args) {
        if (handle == null) {
            throw new UnsupportedOperationException("libei is not available");
        }
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static final class H {
        private static final ValueLayout P = ValueLayout.ADDRESS;
        private static final ValueLayout I = ValueLayout.JAVA_INT;
        private static final ValueLayout Z = ValueLayout.JAVA_BOOLEAN;
        private static final ValueLayout J = ValueLayout.JAVA_LONG;
        private static final ValueLayout D = ValueLayout.JAVA_DOUBLE;

        static final SymbolLookup LIB = NativeSymbolLookup.lookupFirstAvailable("libei.so.1", "libei.so");

        static final MethodHandle NEW_SENDER = handle("ei_new_sender", FunctionDescriptor.of(P, P));
        static final MethodHandle CONFIGURE_NAME = handle("ei_configure_name", FunctionDescriptor.ofVoid(P, P));
        static final MethodHandle SETUP_BACKEND_FD = handle("ei_setup_backend_fd", FunctionDescriptor.of(I, P, I));
        static final MethodHandle DISPATCH = handle("ei_dispatch", FunctionDescriptor.ofVoid(P));
        static final MethodHandle GET_EVENT = handle("ei_get_event", FunctionDescriptor.of(P, P));
        static final MethodHandle NOW = handle("ei_now", FunctionDescriptor.of(J, P));
        static final MethodHandle UNREF = handle("ei_unref", FunctionDescriptor.of(P, P));
        static final MethodHandle EVENT_GET_TYPE = handle("ei_event_get_type", FunctionDescriptor.of(I, P));
        static final MethodHandle EVENT_GET_SEAT = handle("ei_event_get_seat", FunctionDescriptor.of(P, P));
        static final MethodHandle EVENT_GET_DEVICE = handle("ei_event_get_device", FunctionDescriptor.of(P, P));
        static final MethodHandle EVENT_UNREF = handle("ei_event_unref", FunctionDescriptor.of(P, P));
        static final MethodHandle NEW_PING = handle("ei_new_ping", FunctionDescriptor.of(P, P));
        static final MethodHandle PING = handle("ei_ping", FunctionDescriptor.ofVoid(P));
        static final MethodHandle PING_UNREF = handle("ei_ping_unref", FunctionDescriptor.of(P, P));
        static final MethodHandle EVENT_PONG_GET_PING = handle("ei_event_pong_get_ping", FunctionDescriptor.of(P, P));
        static final MethodHandle SEAT_BIND_CAPABILITIES = handle("ei_seat_bind_capabilities",
                FunctionDescriptor.ofVoid(P, I, P), Linker.Option.firstVariadicArg(1));
        static final MethodHandle DEVICE_REF = handle("ei_device_ref", FunctionDescriptor.of(P, P));
        static final MethodHandle DEVICE_UNREF = handle("ei_device_unref", FunctionDescriptor.of(P, P));
        static final MethodHandle DEVICE_HAS_CAPABILITY = handle("ei_device_has_capability",
                FunctionDescriptor.of(Z, P, I));
        static final MethodHandle DEVICE_GET_REGION = handle("ei_device_get_region", FunctionDescriptor.of(P, P, J));
        static final MethodHandle REGION_GET_X = handle("ei_region_get_x", FunctionDescriptor.of(I, P));
        static final MethodHandle REGION_GET_Y = handle("ei_region_get_y", FunctionDescriptor.of(I, P));
        static final MethodHandle REGION_GET_WIDTH = handle("ei_region_get_width", FunctionDescriptor.of(I, P));
        static final MethodHandle REGION_GET_HEIGHT = handle("ei_region_get_height", FunctionDescriptor.of(I, P));
        static final MethodHandle DEVICE_START_EMULATING = handle("ei_device_start_emulating",
                FunctionDescriptor.ofVoid(P, I));
        static final MethodHandle DEVICE_FRAME = handle("ei_device_frame", FunctionDescriptor.ofVoid(P, J));
        static final MethodHandle DEVICE_POINTER_MOTION_ABSOLUTE = handle("ei_device_pointer_motion_absolute",
                FunctionDescriptor.ofVoid(P, D, D));
        static final MethodHandle DEVICE_BUTTON_BUTTON = handle("ei_device_button_button",
                FunctionDescriptor.ofVoid(P, I, Z));
        static final MethodHandle DEVICE_SCROLL_DISCRETE = handle("ei_device_scroll_discrete",
                FunctionDescriptor.ofVoid(P, I, I));
        static final MethodHandle DEVICE_KEYBOARD_KEY = handle("ei_device_keyboard_key",
                FunctionDescriptor.ofVoid(P, I, Z));
        static final MethodHandle DEVICE_TOUCH_NEW = handle("ei_device_touch_new", FunctionDescriptor.of(P, P));
        static final MethodHandle TOUCH_DOWN = handle("ei_touch_down", FunctionDescriptor.ofVoid(P, D, D));
        static final MethodHandle TOUCH_MOTION = handle("ei_touch_motion", FunctionDescriptor.ofVoid(P, D, D));
        static final MethodHandle TOUCH_UP = handle("ei_touch_up", FunctionDescriptor.ofVoid(P));
        static final MethodHandle TOUCH_UNREF = handle("ei_touch_unref", FunctionDescriptor.of(P, P));

        private static MethodHandle handle(String name, FunctionDescriptor descriptor, Linker.Option... options) {
            if (LIB == null) {
                return null;
            }
            return LIB.find(name)
                    .map(symbol -> Linker.nativeLinker().downcallHandle(symbol, descriptor, options))
                    .orElse(null);
        }
    }
}
