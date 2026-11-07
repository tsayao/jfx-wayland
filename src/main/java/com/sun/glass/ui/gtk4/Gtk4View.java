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

import com.sun.glass.events.KeyEvent;
import com.sun.glass.events.MouseEvent;
import com.sun.glass.events.ViewEvent;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.View;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkButtonEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkEventSequence;
import com.sun.glass.ui.gtk4.wrappers.GdkDrop;
import com.sun.glass.ui.gtk4.wrappers.GdkEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkKeyEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkScrollEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkTouchEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkTouchpadEvent;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkDropTargetAsync;
import com.sun.glass.ui.gtk4.wrappers.GtkEventController;
import com.sun.glass.ui.gtk4.wrappers.GtkEventControllerKey;
import com.sun.glass.ui.gtk4.wrappers.GtkEventControllerLegacy;
import com.sun.glass.ui.gtk4.wrappers.GtkEventControllerMotion;
import com.sun.glass.ui.gtk4.wrappers.GtkNative;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Glass view. The input of the view widget is translated into Glass events
 * following the GTK 3 toolkit: raw button, motion and scroll events come from
 * a legacy event controller, crossing events from a motion controller, and key
 * events from a key controller on the toplevel.
 */
public class Gtk4View extends View {

    private static final Gtk4Log.Category POINTER = Gtk4Log.Category.POINTER;
    private static final Gtk4Log.Category POINTER_MOTION = Gtk4Log.Category.POINTER_MOTION;
    private static final Gtk4Log.Category KEYS = Gtk4Log.Category.KEYS;

    private static final int GDK_BUTTON_BACK = 8;
    private static final int GDK_BUTTON_FORWARD = 9;
    private static final int MOUSE_BUTTONS_MASK = Gtk4KeyMap.GDK_BUTTON1_MASK | Gtk4KeyMap.GDK_BUTTON2_MASK
            | Gtk4KeyMap.GDK_BUTTON3_MASK | Gtk4KeyMap.GDK_BUTTON4_MASK | Gtk4KeyMap.GDK_BUTTON5_MASK;

    private static final AtomicLong PTR_COUNTER = new AtomicLong(1);

    private long ptr;
    private int viewX;
    private int viewY;

    private Gtk4Window window;
    private GtkWidget viewWidget;
    private Gtk4SignalHelper signals;
    private final GtkEventController[] controllers = new GtkEventController[4];

    // The view and position of the last button press, where a drag starts
    private static Gtk4View lastPressedView;
    private double lastPressX;
    private double lastPressY;

    private boolean mouseEntered;
    private int lastMouseX;
    private int lastMouseY;
    // Buttons pressed in this view; Glass expects the state after the event
    private int pressedButtonsMask;
    private static int pressDispatchDepth;
    private static Gtk4View pointerView;
    // A press consumed by a window move or resize, whose release is ignored
    private boolean suppressRelease;

    private boolean imEnabled;
    private Gtk4IMEHandler imeHandler;

    @Override
    protected long _create(Map caps) {
        ptr = PTR_COUNTER.getAndIncrement();
        return ptr;
    }

    @Override
    protected long _getNativeView(long ptr) {
        return ptr;
    }

    @Override
    protected int _getX(long ptr) {
        return viewX;
    }

    @Override
    protected int _getY(long ptr) {
        return viewY;
    }

    @Override
    protected void _setParent(long ptr, long parentPtr) {
        notifyView(parentPtr == 0 ? ViewEvent.REMOVE : ViewEvent.ADD);
    }

    @Override
    protected boolean _close(long ptr) {
        disableIME();
        detach();
        this.ptr = 0;
        return true;
    }

    @Override
    protected void _scheduleRepaint(long ptr) {
    }

    @Override
    protected void _begin(long ptr) {
    }

    @Override
    protected void _end(long ptr) {
    }

    @Override
    protected long _getNativeFrameBuffer(long ptr) {
        return 0;
    }

    @Override
    protected void _uploadPixels(long ptr, Pixels pixels) {
        if (window != null && window.getCompositor() != null) {
            window.getCompositor().publishPixels(pixels);
        }
    }

    @Override
    protected boolean _enterFullscreen(long ptr, boolean animate, boolean keepRatio, boolean hideCursor) {
        if (window != null) {
            window.enterFullscreen();
        }
        return true;
    }

    @Override
    protected void _exitFullscreen(long ptr, boolean animate) {
        if (window != null) {
            window.exitFullscreen();
        }
    }

    // ---------------------------------------------------------------------
    // Attachment to a window

    void attach(Gtk4Window window, GtkWidget viewWidget, Gtk4SignalHelper signals) {
        this.window = window;
        this.viewWidget = viewWidget;
        this.signals = signals;

        GtkEventControllerLegacy legacy = GtkEventControllerLegacy.create();
        signals.addController(viewWidget, legacy);
        signals.connect(legacy, "event", this, "onEvent", int.class, GtkEventController.class, GdkEvent.class);
        controllers[0] = legacy;

        GtkEventControllerMotion motion = GtkEventControllerMotion.create();
        signals.addController(viewWidget, motion);
        signals.connect(motion, "enter", this, "onEnter",
                void.class, GtkEventController.class, double.class, double.class);
        signals.connect(motion, "leave", this, "onLeave", void.class, GtkEventController.class);
        controllers[1] = motion;

        GtkWidget keyWidget = window.isPopup() ? null : window.getKeyWidget();
        if (keyWidget != null) {
            GtkEventControllerKey key = GtkEventControllerKey.create();
            // Capture phase, so GTK key bindings (focus navigation, F10) do not run
            key.setPropagationPhase(Gtk.PHASE_CAPTURE);
            signals.addController(keyWidget, key);
            signals.connect(key, "key-pressed", this, "onKeyPressed",
                    int.class, GtkEventController.class, int.class, int.class, int.class);
            signals.connect(key, "key-released", this, "onKeyReleased",
                    void.class, GtkEventController.class, int.class, int.class, int.class);
            controllers[2] = key;
        }

        GtkDropTargetAsync dropTarget = GtkDropTargetAsync.create(null,
                Gdk.ACTION_COPY | Gdk.ACTION_MOVE | Gdk.ACTION_LINK);
        signals.addController(viewWidget, dropTarget);
        signals.connect(dropTarget, "accept", this, "onDropAccept",
                int.class, GtkEventController.class, GdkDrop.class);
        signals.connect(dropTarget, "drag-enter", this, "onDragEnter",
                int.class, GtkEventController.class, GdkDrop.class, double.class, double.class);
        signals.connect(dropTarget, "drag-motion", this, "onDragMotion",
                int.class, GtkEventController.class, GdkDrop.class, double.class, double.class);
        signals.connect(dropTarget, "drag-leave", this, "onDragLeave",
                void.class, GtkEventController.class, GdkDrop.class);
        signals.connect(dropTarget, "drop", this, "onDrop",
                int.class, GtkEventController.class, GdkDrop.class, double.class, double.class);
        controllers[3] = dropTarget;

        if (imEnabled) {
            enableOrResetIME();
        }
    }

    void detach() {
        disableIME();
        for (int i = 0; i < controllers.length; i++) {
            if (controllers[i] != null && signals != null) {
                signals.removeController(controllers[i]);
            }
            controllers[i] = null;
        }
        exitPointer();
        pressedButtonsMask = 0;
        if (lastPressedView == this) {
            lastPressedView = null;
        }
        window = null;
        viewWidget = null;
        signals = null;
    }

    // ---------------------------------------------------------------------
    // Notifications from the window

    void notifyViewEvent(int type) {
        notifyView(type);
    }

    void notifyViewPosition(int x, int y) {
        viewX = x;
        viewY = y;
        notifyView(ViewEvent.MOVE);
    }

    @Override
    public void notifyResize(int width, int height) {
        super.notifyResize(width, height);
    }

    @Override
    public void notifyMouse(int type, int button, int x, int y, int xAbs, int yAbs,
                            int modifiers, boolean isPopupTrigger, boolean isSynthesized) {
        super.notifyMouse(type, button, x, y, xAbs, yAbs, modifiers, isPopupTrigger, isSynthesized);
    }

    void notifyMouseExitIfEntered() {
        if (mouseEntered) {
            exitPointer();
            int xAbs = window != null ? window.getScreenX() + lastMouseX : lastMouseX;
            int yAbs = window != null ? window.getScreenY() + lastMouseY : lastMouseY;
            notifyMouse(MouseEvent.EXIT, MouseEvent.BUTTON_NONE, lastMouseX, lastMouseY, xAbs, yAbs,
                    0, false, false);
        }
    }

    /**
     * Marks the pointer as inside this view. The pointer is in one view at a
     * time; GTK does not report leaving a view that it did not report
     * entering, as happens when a popup under the pointer is removed.
     */
    private void enterPointer() {
        if (pointerView != null && pointerView != this) {
            pointerView.notifyMouseExitIfEntered();
        }
        pointerView = this;
        mouseEntered = true;
    }

    private void exitPointer() {
        mouseEntered = false;
        if (pointerView == this) {
            pointerView = null;
        }
    }

    void onWindowFocus(boolean focused) {
        if (imeHandler != null && imeHandler.isEnabled()) {
            if (focused) {
                imeHandler.focusIn();
            } else {
                imeHandler.focusOut();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Pointer

    int onEvent(GtkEventController controller, GdkEvent event) {
        int type = event.getEventType();
        if ((type == Gdk.BUTTON_PRESS || type == Gdk.BUTTON_RELEASE || type == Gdk.MOTION_NOTIFY
                || type == Gdk.SCROLL) && Gtk4Robot.isPickEvent(event.getTime())) {
            // A click of the robot for the color picker of the desktop, which
            // was not shown yet: the application does not get it
            Gtk4Log.log(POINTER, "dropped an event of the color picker of the robot");
            return 1;
        }
        if (isBlocked() && type != Gdk.MOTION_NOTIFY) {
            // A modal window disables the others: their input is dropped, and a
            // press brings the modal window forward
            if (type == Gdk.BUTTON_PRESS || type == Gdk.TOUCH_BEGIN) {
                ((Gtk4Window) getWindow()).notifyBlockedPress();
            }
            return 1;
        }
        switch (type) {
            case Gdk.BUTTON_PRESS, Gdk.BUTTON_RELEASE -> {
                processButton(event.cast(GdkButtonEvent::new), type == Gdk.BUTTON_PRESS);
                return 1;
            }
            case Gdk.MOTION_NOTIFY -> {
                processMotion(event);
                return 1;
            }
            case Gdk.SCROLL -> {
                processScroll(event.cast(GdkScrollEvent::new));
                return 1;
            }
            case Gdk.TOUCH_BEGIN, Gdk.TOUCH_UPDATE, Gdk.TOUCH_END, Gdk.TOUCH_CANCEL -> {
                processTouch(event.cast(GdkTouchEvent::new), type);
                return 1;
            }
            case Gdk.TOUCHPAD_PINCH, Gdk.TOUCHPAD_SWIPE -> {
                processTouchpad(event.cast(GdkTouchpadEvent::new), type);
                return 1;
            }
            default -> {
                return 0;
            }
        }
    }

    /**
     * Translates the surface position of an event to view coordinates.
     */
    private boolean toViewCoordinates(GdkEvent event, double[] x, double[] y) {
        if (viewWidget == null || !event.getPosition(x, y)) {
            return false;
        }
        GtkNative nativeWidget = viewWidget.getNative();
        double[] tx = new double[1];
        double[] ty = new double[1];
        nativeWidget.getSurfaceTransform(tx, ty);
        GtkWidget root = nativeWidget.cast(GtkWidget::new);
        return root.translateCoordinates(viewWidget, x[0] - tx[0], y[0] - ty[0], x, y);
    }

    private void processButton(GdkButtonEvent event, boolean press) {
        double[] px = new double[1];
        double[] py = new double[1];
        if (!toViewCoordinates(event, px, py)) {
            return;
        }
        int button = event.getButton();
        int x = (int) px[0];
        int y = (int) py[0];
        Gtk4Log.log(POINTER, "button: %s button=%d at (%d,%d)", press ? "PRESS" : "RELEASE", button, x, y);

        if (press && window != null) {
            lastPressedView = this;
            lastPressX = px[0];
            lastPressY = py[0];
            if (viewWidget != null && !viewWidget.hasFocus()) {
                viewWidget.grabFocus();
            }
            int nPress = consecutivePresses(event);
            if (window.handleExtendedButtonPress(event, button, nPress, px[0], py[0])) {
                suppressRelease = button == Gdk.BUTTON_PRIMARY && nPress == 1;
                return;
            }
        } else if (!press && suppressRelease && button == Gdk.BUTTON_PRIMARY) {
            suppressRelease = false;
            return;
        }

        int mask = switch (button) {
            case 1 -> Gtk4KeyMap.GDK_BUTTON1_MASK;
            case 2 -> Gtk4KeyMap.GDK_BUTTON2_MASK;
            case 3 -> Gtk4KeyMap.GDK_BUTTON3_MASK;
            case GDK_BUTTON_BACK -> Gtk4KeyMap.GDK_BUTTON4_MASK;
            case GDK_BUTTON_FORWARD -> Gtk4KeyMap.GDK_BUTTON5_MASK;
            default -> 0;
        };

        // The event state is the state just before the event; Glass expects the
        // state after it
        int state = event.getModifierState() & ~MOUSE_BUTTONS_MASK;
        if (press) {
            pressedButtonsMask |= mask;
        } else {
            pressedButtonsMask &= ~mask;
        }
        state |= pressedButtonsMask;

        int glassButton = toGlassButton(button);
        if (glassButton == MouseEvent.BUTTON_NONE) {
            return;
        }

        int xAbs = window != null ? window.getScreenX() + x : x;
        int yAbs = window != null ? window.getScreenY() + y : y;
        boolean popupTrigger = press && button == Gdk.BUTTON_SECONDARY;
        if (press) {
            pressDispatchDepth++;
        }
        try {
            notifyMouse(press ? MouseEvent.DOWN : MouseEvent.UP, glassButton, x, y, xAbs, yAbs,
                    Gtk4KeyMap.toGlassModifier(state), popupTrigger, false);

            if (popupTrigger) {
                notifyMenu(x, y, xAbs, yAbs, false);
            }
        } finally {
            if (press) {
                pressDispatchDepth--;
            }
        }
    }

    /**
     * Whether a button press is being delivered. GTK grabs the pointer for
     * the widget that receives a press once the press is delivered, so a
     * widget removed meanwhile keeps the grab.
     */
    static boolean isDispatchingPress() {
        return pressDispatchDepth > 0;
    }

    // Tracks double clicks for the extended window title area
    private long lastPressTime;
    private int lastPressButton;
    private int pressCount;

    private int consecutivePresses(GdkButtonEvent event) {
        long time = Integer.toUnsignedLong(event.getTime());
        int button = event.getButton();
        if (button == lastPressButton && time - lastPressTime <= getMultiClickTime()) {
            pressCount++;
        } else {
            pressCount = 1;
        }
        lastPressTime = time;
        lastPressButton = button;
        return pressCount;
    }

    private static int toGlassButton(int gdkButton) {
        return switch (gdkButton) {
            case 1 -> MouseEvent.BUTTON_LEFT;
            case 2 -> MouseEvent.BUTTON_OTHER;
            case 3 -> MouseEvent.BUTTON_RIGHT;
            case GDK_BUTTON_BACK -> MouseEvent.BUTTON_BACK;
            case GDK_BUTTON_FORWARD -> MouseEvent.BUTTON_FORWARD;
            default -> MouseEvent.BUTTON_NONE;
        };
    }

    private void processMotion(GdkEvent event) {
        double[] px = new double[1];
        double[] py = new double[1];
        if (!toViewCoordinates(event, px, py)) {
            return;
        }
        Gtk4Robot.pointerAt(window, px[0], py[0]);
        int x = (int) px[0];
        int y = (int) py[0];
        lastMouseX = x;
        lastMouseY = y;

        int state = (event.getModifierState() & ~MOUSE_BUTTONS_MASK) | pressedButtonsMask;
        int modifiers = Gtk4KeyMap.toGlassModifier(state);
        int xAbs = window != null ? window.getScreenX() + x : x;
        int yAbs = window != null ? window.getScreenY() + y : y;

        if (window != null && pressedButtonsMask == 0 && !window.updateExtendedCursor(px[0], py[0])) {
            // On the resize border of an extended window, which is not client area
            if (mouseEntered) {
                exitPointer();
                notifyMouse(MouseEvent.EXIT, MouseEvent.BUTTON_NONE, x, y, xAbs, yAbs, modifiers, false, false);
            }
            return;
        }
        if (!mouseEntered && pressedButtonsMask == 0) {
            enterPointer();
            notifyMouse(MouseEvent.ENTER, MouseEvent.BUTTON_NONE, x, y, xAbs, yAbs, modifiers, false, false);
        }

        boolean isDrag = (modifiers & (KeyEvent.MODIFIER_BUTTON_PRIMARY | KeyEvent.MODIFIER_BUTTON_MIDDLE
                | KeyEvent.MODIFIER_BUTTON_SECONDARY | KeyEvent.MODIFIER_BUTTON_BACK
                | KeyEvent.MODIFIER_BUTTON_FORWARD)) != 0;

        int button = MouseEvent.BUTTON_NONE;
        if ((modifiers & KeyEvent.MODIFIER_BUTTON_PRIMARY) != 0) {
            button = MouseEvent.BUTTON_LEFT;
        } else if ((modifiers & KeyEvent.MODIFIER_BUTTON_MIDDLE) != 0) {
            button = MouseEvent.BUTTON_OTHER;
        } else if ((modifiers & KeyEvent.MODIFIER_BUTTON_SECONDARY) != 0) {
            button = MouseEvent.BUTTON_RIGHT;
        } else if ((modifiers & KeyEvent.MODIFIER_BUTTON_BACK) != 0) {
            button = MouseEvent.BUTTON_BACK;
        } else if ((modifiers & KeyEvent.MODIFIER_BUTTON_FORWARD) != 0) {
            button = MouseEvent.BUTTON_FORWARD;
        }

        Gtk4Log.log(POINTER_MOTION, "motion: (%d,%d) drag=%b", x, y, isDrag);
        notifyMouse(isDrag ? MouseEvent.DRAG : MouseEvent.MOVE, button, x, y, xAbs, yAbs,
                modifiers, false, false);
    }

    private void processTouch(GdkTouchEvent event, int type) {
        double[] px = new double[1];
        double[] py = new double[1];
        GdkEventSequence sequence = event.getEventSequence();
        if (sequence == null || sequence.isNull() || !toViewCoordinates(event, px, py)) {
            return;
        }
        int x = (int) px[0];
        int y = (int) py[0];
        int xAbs = window != null ? window.getScreenX() + x : x;
        int yAbs = window != null ? window.getScreenY() + y : y;
        int state = event.getModifierState() & ~MOUSE_BUTTONS_MASK;
        Gtk4Log.log(POINTER, "touch: type=%d sequence=%x at (%d,%d) emulating=%b",
                type, sequence.getPointer().address(), x, y, event.getEmulatingPointer());

        Gtk4GestureSupport.touch(this, type, sequence.getPointer().address(), x, y, xAbs, yAbs,
                Gtk4KeyMap.toGlassModifier(state));

        if (!event.getEmulatingPointer()) {
            return;
        }
        lastMouseX = x;
        lastMouseY = y;
        switch (type) {
            case Gdk.TOUCH_BEGIN -> {
                if (viewWidget != null && !viewWidget.hasFocus()) {
                    viewWidget.grabFocus();
                }
                lastPressedView = this;
                pressedButtonsMask |= Gtk4KeyMap.GDK_BUTTON1_MASK;
                enterPointer();
                notifyMouse(MouseEvent.ENTER, MouseEvent.BUTTON_NONE, x, y, xAbs, yAbs,
                        Gtk4KeyMap.toGlassModifier(state), false, true);
                notifyMouse(MouseEvent.DOWN, MouseEvent.BUTTON_LEFT, x, y, xAbs, yAbs,
                        Gtk4KeyMap.toGlassModifier(state | pressedButtonsMask), false, true);
            }
            case Gdk.TOUCH_UPDATE -> notifyMouse(MouseEvent.DRAG, MouseEvent.BUTTON_LEFT, x, y, xAbs, yAbs,
                    Gtk4KeyMap.toGlassModifier(state | pressedButtonsMask), false, true);
            default -> {
                pressedButtonsMask &= ~Gtk4KeyMap.GDK_BUTTON1_MASK;
                notifyMouse(MouseEvent.UP, MouseEvent.BUTTON_LEFT, x, y, xAbs, yAbs,
                        Gtk4KeyMap.toGlassModifier(state | pressedButtonsMask), false, true);
                notifyMouse(MouseEvent.EXIT, MouseEvent.BUTTON_NONE, x, y, xAbs, yAbs,
                        Gtk4KeyMap.toGlassModifier(state | pressedButtonsMask), false, true);
                exitPointer();
            }
        }
    }

    private void processTouchpad(GdkTouchpadEvent event, int type) {
        double[] px = new double[1];
        double[] py = new double[1];
        int x = lastMouseX;
        int y = lastMouseY;
        if (toViewCoordinates(event, px, py)) {
            x = (int) px[0];
            y = (int) py[0];
        }
        int xAbs = window != null ? window.getScreenX() + x : x;
        int yAbs = window != null ? window.getScreenY() + y : y;
        int modifiers = Gtk4KeyMap.toGlassModifier(event.getModifierState() & ~MOUSE_BUTTONS_MASK);
        int phase = event.getGesturePhase();
        if (type == Gdk.TOUCHPAD_PINCH) {
            Gtk4Log.log(POINTER, "pinch: phase=%d scale=%.3f angle=%.3f", phase,
                    event.getPinchScale(), event.getPinchAngleDelta());
            Gtk4GestureSupport.pinch(this, phase, event.getPinchScale(), event.getPinchAngleDelta(),
                    x, y, xAbs, yAbs, modifiers);
        } else {
            double[] dx = new double[1];
            double[] dy = new double[1];
            event.getDeltas(dx, dy);
            Gtk4Log.log(POINTER, "swipe: phase=%d fingers=%d dx=%.1f dy=%.1f", phase,
                    event.getNFingers(), dx[0], dy[0]);
            Gtk4GestureSupport.swipe(this, phase, dx[0], dy[0], event.getNFingers(), x, y, xAbs, yAbs, modifiers);
        }
    }

    private void processScroll(GdkScrollEvent event) {
        double dx = 0;
        double dy = 0;
        double multiplier = 40.0;
        int direction = event.getDirection();
        switch (direction) {
            case Gdk.SCROLL_UP -> dy = 1;
            case Gdk.SCROLL_DOWN -> dy = -1;
            case Gdk.SCROLL_LEFT -> dx = 1;
            case Gdk.SCROLL_RIGHT -> dx = -1;
            case Gdk.SCROLL_SMOOTH -> {
                double[] deltaX = new double[1];
                double[] deltaY = new double[1];
                event.getDeltas(deltaX, deltaY);
                dx = -deltaX[0];
                dy = -deltaY[0];
                if (event.getUnit() == Gdk.SCROLL_UNIT_SURFACE) {
                    // Touchpads report logical pixels
                    multiplier = 1.0;
                }
            }
            default -> {
            }
        }

        int state = event.getModifierState();
        if (direction != Gdk.SCROLL_SMOOTH && (state & Gtk4KeyMap.GDK_SHIFT_MASK) != 0) {
            double t = dy;
            dy = dx;
            dx = t;
        }
        if (dx == 0 && dy == 0) {
            return;
        }

        double[] px = new double[1];
        double[] py = new double[1];
        int x = lastMouseX;
        int y = lastMouseY;
        if (toViewCoordinates(event, px, py)) {
            x = (int) px[0];
            y = (int) py[0];
        }
        int xAbs = window != null ? window.getScreenX() + x : x;
        int yAbs = window != null ? window.getScreenY() + y : y;
        Gtk4Log.log(POINTER, "scroll: dx=%.2f dy=%.2f multiplier=%.0f", dx, dy, multiplier);
        notifyScroll(x, y, xAbs, yAbs, dx, dy, Gtk4KeyMap.toGlassModifier(state),
                0, 0, 0, 0, multiplier, multiplier);
    }

    void onEnter(GtkEventController controller, double x, double y) {
        Gtk4Robot.pointerAt(window, x, y);
        if (isPickEvent(controller)) {
            return;
        }
        int ix = (int) x;
        int iy = (int) y;
        lastMouseX = ix;
        lastMouseY = iy;
        if (mouseEntered) {
            return;
        }
        if (window != null && !window.updateExtendedCursor(x, y)) {
            return;
        }
        enterPointer();
        GdkEvent event = controller.getCurrentEvent();
        // Workaround for JDK-8126843: the entering pointer has no buttons pressed
        int state = event.isNull() ? 0 : event.getModifierState() & ~MOUSE_BUTTONS_MASK;
        int xAbs = window != null ? window.getScreenX() + ix : ix;
        int yAbs = window != null ? window.getScreenY() + iy : iy;
        Gtk4Log.log(POINTER_MOTION, "enter: (%d,%d)", ix, iy);
        notifyMouse(MouseEvent.ENTER, MouseEvent.BUTTON_NONE, ix, iy, xAbs, yAbs,
                Gtk4KeyMap.toGlassModifier(state), false, false);
    }

    void onLeave(GtkEventController controller) {
        if (!mouseEntered || isPickEvent(controller)) {
            return;
        }
        exitPointer();
        GdkEvent event = controller.getCurrentEvent();
        int state = event.isNull() ? 0 : event.getModifierState();
        int xAbs = window != null ? window.getScreenX() + lastMouseX : lastMouseX;
        int yAbs = window != null ? window.getScreenY() + lastMouseY : lastMouseY;
        Gtk4Log.log(POINTER_MOTION, "leave: (%d,%d)", lastMouseX, lastMouseY);
        notifyMouse(MouseEvent.EXIT, MouseEvent.BUTTON_NONE, lastMouseX, lastMouseY, xAbs, yAbs,
                Gtk4KeyMap.toGlassModifier(state), false, false);
    }

    /** Whether the current event is one of the moves of the robot for the color picker. */
    private static boolean isPickEvent(GtkEventController controller) {
        GdkEvent event = controller.getCurrentEvent();
        return !event.isNull() && Gtk4Robot.isPickEvent(event.getTime());
    }

    static Gtk4View getLastPressedView() {
        return lastPressedView;
    }

    /**
     * The position of the last button press, relative to the surface.
     */
    double[] getLastPressSurfacePosition() {
        if (viewWidget == null) {
            return null;
        }
        GtkNative nativeWidget = viewWidget.getNative();
        double[] x = new double[1];
        double[] y = new double[1];
        if (!viewWidget.translateCoordinates(nativeWidget.cast(GtkWidget::new), lastPressX, lastPressY, x, y)) {
            return null;
        }
        double[] tx = new double[1];
        double[] ty = new double[1];
        nativeWidget.getSurfaceTransform(tx, ty);
        return new double[] {x[0] + tx[0], y[0] + ty[0]};
    }

    void resetPressedButtons() {
        pressedButtonsMask = 0;
    }

    // ---------------------------------------------------------------------
    // Drop target

    int onDropAccept(GtkEventController controller, GdkDrop drop) {
        return 1;
    }

    int onDragEnter(GtkEventController controller, GdkDrop drop, double x, double y) {
        Gtk4DnD.enter(drop);
        return dragOver(drop, x, y, true);
    }

    int onDragMotion(GtkEventController controller, GdkDrop drop, double x, double y) {
        return dragOver(drop, x, y, false);
    }

    private int dragOver(GdkDrop drop, double x, double y, boolean enter) {
        int ix = (int) x;
        int iy = (int) y;
        int xAbs = window != null ? window.getScreenX() + ix : ix;
        int yAbs = window != null ? window.getScreenY() + iy : iy;
        int available = Gtk4DnD.toGlassAction(drop.getActions());
        int recommended = Gtk4DnD.recommendedAction(drop.getActions());
        int result = enter
                ? notifyDragEnter(ix, iy, xAbs, yAbs, recommended)
                : notifyDragOver(ix, iy, xAbs, yAbs, recommended);
        Gtk4Log.log(Gtk4Log.Category.DRAG_DROP, "drag %s: drop actions=0x%x, recommended=%d, accepted=%d",
                enter ? "enter" : "motion", drop.getActions(), recommended, result);
        return Gtk4DnD.preferredGdkAction(result & available);
    }

    void onDragLeave(GtkEventController controller, GdkDrop drop) {
        // A drop is followed by a leave, which would reset the gesture
        if (!Gtk4DnD.isDropDragged()) {
            notifyDragLeave();
        }
        Gtk4DnD.leave(drop);
    }

    int onDrop(GtkEventController controller, GdkDrop drop, double x, double y) {
        Gtk4DnD.drop(drop);
        int ix = (int) x;
        int iy = (int) y;
        int xAbs = window != null ? window.getScreenX() + ix : ix;
        int yAbs = window != null ? window.getScreenY() + iy : iy;
        try {
            int available = Gtk4DnD.toGlassAction(drop.getActions());
            int result = notifyDragDrop(ix, iy, xAbs, yAbs, Gtk4DnD.recommendedAction(drop.getActions()));
            drop.finish(Gtk4DnD.preferredGdkAction(result & available));
        } finally {
            Gtk4DnD.dropFinished(drop);
        }
        return 1;
    }

    // ---------------------------------------------------------------------
    // Keyboard

    private boolean isBlocked() {
        return getWindow() != null && !getWindow().isEnabled();
    }

    int onKeyPressed(GtkEventController controller, int keyval, int keycode, int state) {
        if (isBlocked()) {
            return 1;
        }
        GdkEvent event = controller.getCurrentEvent();
        if (imeHandler != null && imeHandler.filter(event)) {
            return 1;
        }
        processKey(event, true, keyval, keycode, state);
        return 1;
    }

    void onKeyReleased(GtkEventController controller, int keyval, int keycode, int state) {
        if (isBlocked()) {
            return;
        }
        GdkEvent event = controller.getCurrentEvent();
        if (imeHandler != null && imeHandler.filter(event)) {
            return;
        }
        processKey(event, false, keyval, keycode, state);
    }

    private void processKey(GdkEvent event, boolean press, int keyval, int keycode, int state) {
        int layout = event.isNull() ? 0 : event.cast(GdkKeyEvent::new).getLayout();
        int glassKey = Gtk4KeyMap.getGlassKey(keyval, keycode, state, layout);
        int glassModifier = Gtk4KeyMap.toGlassModifier(state);
        if (press) {
            glassModifier |= Gtk4KeyMap.glassKeyToModifier(glassKey);
        } else {
            glassModifier &= ~Gtk4KeyMap.glassKeyToModifier(glassKey);
        }

        char keyChar = Gtk4KeyMap.toChar(keyval, state);
        char[] chars = keyChar > 0 ? new char[] {keyChar} : new char[0];

        Gtk4Log.log(KEYS, "key: %s keyval=0x%x keycode=%d glassKey=%d modifiers=0x%x",
                press ? "PRESS" : "RELEASE", keyval, keycode, glassKey, glassModifier);

        notifyKey(press ? KeyEvent.PRESS : KeyEvent.RELEASE, glassKey, chars, glassModifier);

        // TYPED events are only sent for printable characters
        if (press && keyChar > 0 && window != null) {
            notifyKey(KeyEvent.TYPED, KeyEvent.VK_UNDEFINED, chars, glassModifier);
        }
    }

    // ---------------------------------------------------------------------
    // Input methods

    @Override
    protected void _enableInputMethodEvents(long ptr, boolean enable) {
        imEnabled = enable;
        if (enable) {
            enableOrResetIME();
        } else {
            disableIME();
        }
    }

    @Override
    protected void _finishInputMethodComposition(long ptr) {
        if (imEnabled) {
            enableOrResetIME();
        }
    }

    void notifyInputMethodLinux(String str, int commitLength, int cursor, byte attr) {
        if (commitLength > 0) {
            notifyInputMethod(str, null, null, null, commitLength, cursor, 0);
        } else {
            int[] attBounds = new int[] {0, str.length()};
            byte[] attValues = new byte[] {attr};
            notifyInputMethod(str, attBounds, attBounds, attValues, 0, cursor, 0);
        }
    }

    /**
     * The position of the input method candidate window, relative to the view.
     */
    double[] getInputMethodCandidatePosition(int offset) {
        double[] pos = notifyInputMethodCandidatePosRequest(offset);
        if (pos != null && window != null) {
            pos[0] -= window.getX() + getX();
            pos[1] -= window.getY() + getY();
        }
        return pos;
    }

    private void enableOrResetIME() {
        if (imeHandler == null && window != null && viewWidget != null) {
            imeHandler = new Gtk4IMEHandler(this, viewWidget, window.getWindowArena());
        }
        if (imeHandler != null) {
            imeHandler.enableOrResetIME();
        }
    }

    private void disableIME() {
        if (imeHandler != null) {
            imeHandler.disable();
            imeHandler = null;
        }
    }

    void repaintEverything() {
        notifyRepaint(0, 0, 0, 0);
    }
}
