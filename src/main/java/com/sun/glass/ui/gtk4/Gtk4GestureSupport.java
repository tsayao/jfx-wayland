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

import com.sun.glass.events.SwipeGesture;
import com.sun.glass.events.TouchEvent;
import com.sun.glass.ui.GestureSupport;
import com.sun.glass.ui.TouchInputSupport;
import com.sun.glass.ui.View;
import com.sun.glass.ui.gtk4.wrappers.Gdk;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the touch screen and touchpad events of GDK into Glass touch and
 * gesture events. Touch screen gestures (scroll, zoom and rotation) are
 * computed from the touch points; touchpad pinches and swipes are reported by
 * GDK.
 */
final class Gtk4GestureSupport {

    private static final double SWIPE_THRESHOLD = 60;

    private static GestureSupport touchGestures;
    private static GestureSupport touchpadGestures;
    private static TouchInputSupport touches;

    private static final Map<Long, int[]> points = new LinkedHashMap<>();
    private static View touchView;
    private static int touchModifiers;
    private static int baseCount;
    private static double baseX;
    private static double baseY;
    private static double baseDistance;
    private static double baseAngle;
    private static double rotation;
    private static double lastAngle;

    private static double pinchRotation;
    private static double swipeX;
    private static double swipeY;

    private Gtk4GestureSupport() {
    }

    private static void initialize() {
        if (touches == null) {
            touchGestures = new GestureSupport(true);
            touchpadGestures = new GestureSupport(false);
            touches = new TouchInputSupport(touchGestures.createTouchCountListener(), true);
        }
    }

    /**
     * Reports a touch point that was pressed, moved or released, along with
     * the other points that are down, and the gestures they perform.
     */
    static void touch(View view, int gdkType, long id, int x, int y, int xAbs, int yAbs, int modifiers) {
        initialize();
        if (view != touchView) {
            points.clear();
            touchView = view;
        }
        int state = switch (gdkType) {
            case Gdk.TOUCH_BEGIN -> TouchEvent.TOUCH_PRESSED;
            case Gdk.TOUCH_UPDATE -> TouchEvent.TOUCH_MOVED;
            default -> TouchEvent.TOUCH_RELEASED;
        };
        if (state != TouchEvent.TOUCH_PRESSED && !points.containsKey(id)) {
            return;
        }
        points.put(id, new int[] {x, y, xAbs, yAbs});
        touchModifiers = modifiers;
        notifyTouchPoints(view, id, state, modifiers);
        if (state == TouchEvent.TOUCH_RELEASED) {
            points.remove(id);
        }
        int count = points.size();
        if (count != baseCount) {
            resetBase();
        } else if (state == TouchEvent.TOUCH_MOVED) {
            performTouchGestures(view, modifiers);
        }
        touchFinished(view, count);
    }

    /** A touch event with every point down: the one that changed, and the others still. */
    private static void notifyTouchPoints(View view, long id, int state, int modifiers) {
        touches.notifyBeginTouchEvent(view, modifiers, true, points.size());
        for (Map.Entry<Long, int[]> e : points.entrySet()) {
            int[] p = e.getValue();
            int pointState = e.getKey() == id ? state : TouchEvent.TOUCH_STILL;
            touches.notifyNextTouchEvent(view, pointState, e.getKey(), p[0], p[1], p[2], p[3]);
        }
        touches.notifyEndTouchEvent(view);
    }

    private static void resetBase() {
        baseCount = points.size();
        double[] c = centroid();
        baseX = c[0];
        baseY = c[1];
        baseDistance = spread(c);
        baseAngle = angle();
        lastAngle = baseAngle;
        rotation = 0;
    }

    private static void performTouchGestures(View view, int modifiers) {
        int count = points.size();
        if (count == 0) {
            return;
        }
        double[] c = centroid();
        int x = (int) Math.round(c[0]);
        int y = (int) Math.round(c[1]);
        int[] first = points.values().iterator().next();
        int xAbs = x + first[2] - first[0];
        int yAbs = y + first[3] - first[1];
        if (count >= 2) {
            double distance = spread(c);
            if (baseDistance > 0) {
                touchGestures.handleTotalZooming(view, modifiers, true, false, x, y, xAbs, yAbs,
                        distance / baseDistance, distance - baseDistance);
            }
            double a = angle();
            double delta = a - lastAngle;
            if (delta > 180) {
                delta -= 360;
            } else if (delta < -180) {
                delta += 360;
            }
            lastAngle = a;
            rotation += delta;
            touchGestures.handleTotalRotation(view, modifiers, true, false, x, y, xAbs, yAbs, rotation);
        }
        touchGestures.handleTotalScrolling(view, modifiers, true, false, count, x, y, xAbs, yAbs,
                c[0] - baseX, c[1] - baseY, 1.0, 1.0);
    }

    private static void touchFinished(View view, int count) {
        if (touchGestures.isScrolling() && count == 0) {
            touchGestures.handleScrollingEnd(view, touchModifiers, count, true, false,
                    View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE);
        }
        if (touchGestures.isRotating() && count < 2) {
            touchGestures.handleRotationEnd(view, touchModifiers, true, false,
                    View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE);
        }
        if (touchGestures.isZooming() && count < 2) {
            touchGestures.handleZoomingEnd(view, touchModifiers, true, false,
                    View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE, View.GESTURE_NO_VALUE);
        }
    }

    private static double[] centroid() {
        double sx = 0;
        double sy = 0;
        for (int[] p : points.values()) {
            sx += p[0];
            sy += p[1];
        }
        int n = Math.max(1, points.size());
        return new double[] {sx / n, sy / n};
    }

    private static double spread(double[] c) {
        double sum = 0;
        for (int[] p : points.values()) {
            sum += Math.hypot(p[0] - c[0], p[1] - c[1]);
        }
        return points.isEmpty() ? 0 : sum / points.size();
    }

    private static double angle() {
        if (points.size() < 2) {
            return 0;
        }
        Iterator<int[]> it = points.values().iterator();
        int[] a = it.next();
        int[] b = it.next();
        return Math.toDegrees(Math.atan2(b[1] - a[1], b[0] - a[0]));
    }

    /**
     * Reports a touchpad pinch, as a zoom and a rotation.
     */
    static void pinch(View view, int phase, double scale, double angleDelta,
                      int x, int y, int xAbs, int yAbs, int modifiers) {
        initialize();
        switch (phase) {
            case Gdk.TOUCHPAD_GESTURE_PHASE_BEGIN -> pinchRotation = 0;
            case Gdk.TOUCHPAD_GESTURE_PHASE_UPDATE -> {
                touchpadGestures.handleTotalZooming(view, modifiers, false, false, x, y, xAbs, yAbs,
                        scale, View.GESTURE_NO_DOUBLE_VALUE);
                pinchRotation += Math.toDegrees(angleDelta);
                touchpadGestures.handleTotalRotation(view, modifiers, false, false, x, y, xAbs, yAbs,
                        pinchRotation);
            }
            default -> {
                if (touchpadGestures.isZooming()) {
                    touchpadGestures.handleZoomingEnd(view, modifiers, false, false, x, y, xAbs, yAbs);
                }
                if (touchpadGestures.isRotating()) {
                    touchpadGestures.handleRotationEnd(view, modifiers, false, false, x, y, xAbs, yAbs);
                }
            }
        }
    }

    /**
     * Reports a touchpad swipe of three or more fingers when it ends.
     */
    static void swipe(View view, int phase, double dx, double dy, int fingers,
                      int x, int y, int xAbs, int yAbs, int modifiers) {
        switch (phase) {
            case Gdk.TOUCHPAD_GESTURE_PHASE_BEGIN -> {
                swipeX = 0;
                swipeY = 0;
            }
            case Gdk.TOUCHPAD_GESTURE_PHASE_UPDATE -> {
                swipeX += dx;
                swipeY += dy;
            }
            case Gdk.TOUCHPAD_GESTURE_PHASE_END -> {
                if (Math.max(Math.abs(swipeX), Math.abs(swipeY)) < SWIPE_THRESHOLD) {
                    return;
                }
                int dir = Math.abs(swipeX) >= Math.abs(swipeY)
                        ? (swipeX > 0 ? SwipeGesture.DIR_RIGHT : SwipeGesture.DIR_LEFT)
                        : (swipeY > 0 ? SwipeGesture.DIR_DOWN : SwipeGesture.DIR_UP);
                GestureSupport.handleSwipePerformed(view, modifiers, false, false, fingers, dir,
                        x, y, xAbs, yAbs);
            }
            default -> {
            }
        }
    }
}
