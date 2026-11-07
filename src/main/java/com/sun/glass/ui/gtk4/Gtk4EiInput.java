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

import com.sun.glass.ui.gtk4.wrappers.Ei;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The devices of the robot through libei: an absolute pointer, buttons, a
 * wheel and a keyboard, and a touch screen when the compositor offers one.
 * <p>
 * Everything runs on the thread of the robot ({@link Gtk4RobotThread}); the
 * calls wait for it. Each event is sent in a frame, and the compositor is
 * then pinged, so that it has processed the event when the call returns
 * (libei 1.3; an older libei gives the frame a moment instead). A device that
 * the compositor pauses for a moment is awaited. When the compositor ends the
 * connection, as when the remote desktop session is revoked, the calls throw
 * {@link DisconnectedException}, and the robot connects again.
 */
final class Gtk4EiInput {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.FOCUS;

    private static final long CONNECT_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private static final long SYNC_TIMEOUT = TimeUnit.SECONDS.toNanos(2);
    private static final long TOUCH_WAIT = TimeUnit.MILLISECONDS.toNanos(300);
    private static final int SCROLL_DETENT = 120;
    // The compositor finds the surface under the pointer at its next frame:
    // what follows a motion to another surface must wait for it
    static final long POINTER_FOCUS_DELAY = 40;
    // How long a frame is given to reach the compositor without a ping to wait for
    private static final long UNSYNCED_FRAME_MS = 30;
    static final int BTN_LEFT = 0x110;

    /** The compositor ended the connection of libei. */
    static final class DisconnectedException extends IllegalStateException {
        DisconnectedException() {
            super("The compositor disconnected the robot");
        }
    }

    // Used on the robot thread only
    private MemorySegment ei;
    private MemorySegment absolute;
    private MemorySegment buttons;
    private MemorySegment scroll;
    private MemorySegment keyboard;
    // A touch screen, when the compositor offers one
    private MemorySegment touchscreen;
    private MemorySegment seat;
    private boolean touchRequested;
    private int sequence;
    private MemorySegment pendingPing;
    private final Map<Integer, MemorySegment> touches = new HashMap<>();

    // Read from the event thread
    private volatile boolean disconnected;
    // The last position given, and the buttons held, which a click of the
    // robot for the color picker must not break
    private volatile double lastX = Double.NaN;
    private volatile double lastY = Double.NaN;
    private volatile int buttonsHeld;

    private Gtk4EiInput(MemorySegment ei) {
        this.ei = ei;
    }

    /**
     * Connects to the compositor: through the remote desktop session of the
     * test session, or of the portal in any session, which may ask the user.
     */
    static Gtk4EiInput connect() {
        if (!Ei.isAvailable()) {
            throw new UnsupportedOperationException("The robot requires libei");
        }
        return Gtk4RobotThread.callResponsive(() -> {
            int fd = Gtk4TestSession.isEnabled()
                    ? Gtk4TestSession.connectToEis() : Gtk4RemoteDesktopPortal.connectToEis();
            MemorySegment ei = Ei.newSender();
            Ei.configureName(ei, "JavaFX Robot");
            int status = Ei.setupBackendFd(ei, fd);
            if (status != 0) {
                Ei.unref(ei);
                throw new IllegalStateException("ei_setup_backend_fd failed: " + status);
            }
            Gtk4EiInput input = new Gtk4EiInput(ei);
            try {
                input.awaitDevices();
            } catch (RuntimeException e) {
                input.release();
                throw e;
            }
            return input;
        });
    }

    /** Whether the compositor ended the connection. */
    boolean isDisconnected() {
        return disconnected;
    }

    /** Releases the devices and the connection. */
    void close() {
        Gtk4RobotThread.execute(this::release);
    }

    private void release() {
        for (MemorySegment touch : touches.values()) {
            Ei.touchUnref(touch);
        }
        touches.clear();
        for (MemorySegment device : new MemorySegment[] {absolute, buttons, scroll, keyboard, touchscreen}) {
            if (device != null) {
                removed(device);
            }
        }
        if (ei != null) {
            Ei.unref(ei);
            ei = null;
        }
        disconnected = true;
    }

    /**
     * Moves the pointer. The compositor has processed the motion when it
     * returns, but finds the surface under the pointer only at its next frame.
     */
    void moveTo(double x, double y) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = require(() -> absolute, "absolute pointer");
            Ei.devicePointerMotionAbsolute(device, x, y);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
            lastX = x;
            lastY = y;
        });
    }

    /**
     * The last position the pointer was moved to, or null before the first move.
     */
    double[] lastPosition() {
        double x = lastX;
        double y = lastY;
        return Double.isNaN(x) ? null : new double[] {x, y};
    }

    /** How many buttons the robot holds down. */
    int buttonsHeld() {
        return buttonsHeld;
    }

    /** Moves the pointer to a point and clicks the primary button there. */
    void click(double x, double y) {
        Gtk4RobotThread.execute(() -> {
            moveTo(x, y);
            sleep(POINTER_FOCUS_DELAY);
            button(BTN_LEFT, true);
            button(BTN_LEFT, false);
        });
    }

    void button(int evdevButton, boolean press) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = require(() -> buttons, "button");
            Ei.deviceButtonButton(device, evdevButton, press);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
            buttonsHeld = Math.max(0, buttonsHeld + (press ? 1 : -1));
        });
    }

    void scroll(int detents) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = require(() -> scroll, "scroll");
            Ei.deviceScrollDiscrete(device, 0, detents * SCROLL_DETENT);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
        });
    }

    void key(int evdevKeycode, boolean press) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = require(() -> keyboard, "keyboard");
            Ei.deviceKeyboardKey(device, evdevKeycode, press);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
        });
    }

    /**
     * Whether the compositor offers a touch screen. The capability is bound
     * on the first call, so that the other input does not change for a robot
     * that never touches.
     */
    boolean hasTouchscreen() {
        return Gtk4RobotThread.call(this::touchscreenOffered);
    }

    private boolean touchscreenOffered() {
        if (!Ei.isTouchAvailable() || seat == null) {
            return false;
        }
        if (!touchRequested) {
            touchRequested = true;
            Ei.seatBindCapabilities(seat, Ei.DEVICE_CAP_POINTER_ABSOLUTE, Ei.DEVICE_CAP_BUTTON,
                    Ei.DEVICE_CAP_SCROLL, Ei.DEVICE_CAP_KEYBOARD, Ei.DEVICE_CAP_TOUCH);
            long deadline = System.nanoTime() + TOUCH_WAIT;
            while (touchscreen == null && System.nanoTime() < deadline) {
                dispatch();
                sleep(5);
            }
            Gtk4Log.log(LOG, "robot touch screen: %s", touchscreen != null ? "yes" : "not offered");
        }
        return touchscreen != null;
    }

    private MemorySegment requireTouch() {
        if (!touchscreenOffered()) {
            throw new UnsupportedOperationException(Ei.isTouchAvailable()
                    ? "The compositor offers no touch screen to the robot" : "This libei cannot emulate touch screens");
        }
        return touchscreen;
    }

    void touchDown(int id, double x, double y) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = requireTouch();
            if (touches.containsKey(id)) {
                throw new IllegalStateException("The touch " + id + " is down already");
            }
            MemorySegment touch = Ei.deviceTouchNew(device);
            touches.put(id, touch);
            Ei.touchDown(touch, x, y);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
        });
    }

    void touchMove(int id, double x, double y) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = requireTouch();
            MemorySegment touch = touches.get(id);
            if (touch == null) {
                throw new IllegalStateException("The touch " + id + " is not down");
            }
            Ei.touchMotion(touch, x, y);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
        });
    }

    void touchUp(int id) {
        Gtk4RobotThread.execute(() -> {
            dispatch();
            MemorySegment device = requireTouch();
            MemorySegment touch = touches.remove(id);
            if (touch == null) {
                throw new IllegalStateException("The touch " + id + " is not down");
            }
            Ei.touchUp(touch);
            Ei.touchUnref(touch);
            Ei.deviceFrame(device, Ei.now(ei));
            sync();
        });
    }

    /**
     * A device of the robot; one that the compositor paused for a moment is
     * awaited until it comes back.
     */
    private MemorySegment require(Supplier<MemorySegment> device, String name) {
        long deadline = System.nanoTime() + SYNC_TIMEOUT;
        while (device.get() == null) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The compositor offers no " + name + " device to the robot");
            }
            sleep(5);
            dispatch();
        }
        return device.get();
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Waits for the compositor to have processed the frames sent.
     */
    private void sync() {
        if (!Ei.isPingAvailable()) {
            // libei before 1.3, as in Ubuntu 24.04, has no ping: the frame is
            // flushed by a dispatch, and given time to arrive
            dispatch();
            sleep(UNSYNCED_FRAME_MS);
            dispatch();
            return;
        }
        MemorySegment ping = Ei.newPing(ei);
        pendingPing = ping;
        try {
            Ei.ping(ping);
            long deadline = System.nanoTime() + SYNC_TIMEOUT;
            while (pendingPing != null) {
                dispatch();
                if (pendingPing == null) {
                    break;
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The compositor did not process the robot input");
                }
                sleep(1);
            }
        } finally {
            pendingPing = null;
            Ei.pingUnref(ping);
        }
    }

    private void awaitDevices() {
        long deadline = System.nanoTime() + CONNECT_TIMEOUT;
        while (absolute == null || buttons == null || scroll == null || keyboard == null) {
            dispatch();
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The compositor did not provide the robot devices:"
                        + " absolute=" + (absolute != null) + " buttons=" + (buttons != null)
                        + " scroll=" + (scroll != null) + " keyboard=" + (keyboard != null));
            }
            sleep(5);
        }
        for (long i = 0; ; i++) {
            MemorySegment region = Ei.deviceGetRegion(absolute, i);
            if (region.equals(MemorySegment.NULL)) {
                break;
            }
            Gtk4Log.log(LOG, "robot region %d,%d %dx%d", Ei.regionGetX(region), Ei.regionGetY(region),
                    Ei.regionGetWidth(region), Ei.regionGetHeight(region));
        }
        Gtk4Log.log(LOG, "robot devices ready");
    }

    private void dispatch() {
        if (disconnected) {
            throw new DisconnectedException();
        }
        Ei.dispatch(ei);
        for (MemorySegment event = Ei.getEvent(ei); !event.equals(MemorySegment.NULL); event = Ei.getEvent(ei)) {
            try {
                handle(event);
            } finally {
                Ei.eventUnref(event);
            }
        }
        if (disconnected) {
            throw new DisconnectedException();
        }
    }

    private void handle(MemorySegment event) {
        int type = Ei.eventGetType(event);
        switch (type) {
            case Ei.EVENT_SEAT_ADDED -> {
                seat = Ei.eventGetSeat(event);
                Ei.seatBindCapabilities(seat, Ei.DEVICE_CAP_POINTER_ABSOLUTE, Ei.DEVICE_CAP_BUTTON,
                        Ei.DEVICE_CAP_SCROLL, Ei.DEVICE_CAP_KEYBOARD);
            }
            case Ei.EVENT_DEVICE_RESUMED -> resumed(Ei.eventGetDevice(event));
            case Ei.EVENT_DEVICE_PAUSED, Ei.EVENT_DEVICE_REMOVED -> removed(Ei.eventGetDevice(event));
            case Ei.EVENT_DISCONNECT -> {
                Gtk4Log.log(LOG, "the compositor disconnected the robot");
                disconnected = true;
            }
            case Ei.EVENT_PONG -> {
                if (Ei.eventPongGetPing(event).equals(pendingPing)) {
                    pendingPing = null;
                }
            }
            default -> { }
        }
        Gtk4Log.log(LOG, "ei event %d", type);
    }

    private void resumed(MemorySegment device) {
        boolean used = false;
        if (absolute == null && Ei.deviceHasCapability(device, Ei.DEVICE_CAP_POINTER_ABSOLUTE)) {
            absolute = device;
            used = true;
        }
        if (buttons == null && Ei.deviceHasCapability(device, Ei.DEVICE_CAP_BUTTON)) {
            buttons = device;
            used = true;
        }
        if (scroll == null && Ei.deviceHasCapability(device, Ei.DEVICE_CAP_SCROLL)) {
            scroll = device;
            used = true;
        }
        if (keyboard == null && Ei.deviceHasCapability(device, Ei.DEVICE_CAP_KEYBOARD)) {
            keyboard = device;
            used = true;
        }
        if (touchscreen == null && Ei.isTouchAvailable() && Ei.deviceHasCapability(device, Ei.DEVICE_CAP_TOUCH)) {
            touchscreen = device;
            used = true;
            Gtk4Log.log(LOG, "robot touch screen available");
        }
        if (used) {
            Ei.deviceRef(device);
            Ei.deviceStartEmulating(device, ++sequence);
        }
    }

    private void removed(MemorySegment device) {
        boolean used = false;
        if (device.equals(absolute)) {
            absolute = null;
            used = true;
        }
        if (device.equals(buttons)) {
            buttons = null;
            used = true;
        }
        if (device.equals(scroll)) {
            scroll = null;
            used = true;
        }
        if (device.equals(keyboard)) {
            keyboard = null;
            used = true;
        }
        if (device.equals(touchscreen)) {
            touchscreen = null;
            used = true;
        }
        if (used) {
            // One reference for all the roles of the device
            Ei.deviceUnref(device);
        }
    }
}
