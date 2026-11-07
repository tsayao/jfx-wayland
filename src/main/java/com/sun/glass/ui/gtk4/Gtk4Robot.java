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

import com.sun.glass.ui.Application;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.gtk4.wrappers.GBytes;
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkTexture;
import com.sun.glass.ui.gtk4.wrappers.GdkTextureDownloader;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A robot that generates input through the compositor and reads what it
 * shows, through the desktop portal, as the libei robot of jfx-tester does.
 * <p>
 * Wayland clients cannot inject input: the robot emulates the devices
 * through libei, in a remote desktop session of the compositor. In a test
 * session (-Dglass.gtk4.testSession=gnome) it is the session of GNOME Shell,
 * which also places the windows; elsewhere it is the session of the
 * RemoteDesktop portal, which the desktop asks the user to allow once. The
 * events reach the application as any other input. Positions are global, in
 * the logical coordinates of the compositor. libei and the portal run on the
 * thread of the robot ({@link Gtk4RobotThread}), which the calls wait for, so
 * that the application does not handle events in the middle of them. When
 * the compositor ends the connection, the robot connects again.
 * <p>
 * The keys are those of the active layout: the robot presses the key that
 * types the key code, as the key events of the layout give them.
 * <p>
 * The screen comes from the Screenshot interface of the portal: a pixel from
 * its PickColor, which shows the picker of the desktop and waits for a click
 * that the robot gives at the point, and a region, or a pixel when PickColor
 * does not work or a button is held (which the click would release), from a
 * screenshot of all the screens. The views drop the clicks of a pick that
 * reach them, when the picker was not shown yet. When the portal does not
 * take screenshots either, the pixels come from the frames that the windows
 * of the application present ({@code -Dglass.gtk4.colorPicker=false} and
 * {@code -Dglass.gtk4.screenshot=false} skip the portal).
 * <p>
 * Outside a test session the compositor does not tell where the windows are.
 * The robot learns it from its pointer: when the pointer it moved to a screen
 * point is at a point of a view, the window is where both points meet. The
 * first move to a window whose position is not known yet looks for the
 * window over the screens, and is then made to the intended point of it.
 */
final class Gtk4Robot extends GlassRobot {

    private static final int BTN_LEFT = Gtk4EiInput.BTN_LEFT;
    private static final int BTN_RIGHT = 0x111;
    private static final int BTN_MIDDLE = 0x112;
    private static final int BTN_SIDE = 0x113;
    private static final int BTN_EXTRA = 0x114;

    // How long a view may take to report the pointer of a move of the robot
    private static final long POINTER_REPORT_NANOS = 500_000_000L;
    // How long a move over a window of the application waits for its view to
    // get the pointer, before the robot sends what follows
    private static final long POINTER_FOCUS_NANOS = 150_000_000L;
    private static final long LOCATE_WAIT_NANOS = 60_000_000L;
    private static final long LOCATE_TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_PROBES = 600;
    // Event times further from the clock than this are on another clock
    private static final long PICK_CLOCK_SKEW_MS = 60_000;

    private static final boolean USE_COLOR_PICKER =
            !"false".equals(System.getProperty("glass.gtk4.colorPicker"));
    private static final boolean USE_SCREENSHOT =
            !"false".equals(System.getProperty("glass.gtk4.screenshot"));
    // The portal failed once: do not wait for it again
    private static boolean colorPickerFailed;
    private static boolean screenshotFailed;

    // Where the robot put the pointer
    private double mouseX;
    private double mouseY;
    private static long lastMoveTime;

    // The last point the robot moved the pointer to, until a view reports it
    private static double pendingX;
    private static double pendingY;
    private static long pendingTime;
    private static boolean pending;
    // Whether a view reported the last move of the robot
    private static boolean reported;

    // Where the views last saw the pointer, in screen coordinates
    private static double seenX;
    private static double seenY;
    private static long seenTime;

    // The monotonic milliseconds of the last pick, whose clicks the views drop
    private static long pickFrom;
    private static long pickUntil;

    @Override
    public void create() {
        if (Gtk4TestSession.isEnabled()) {
            sharedInput();
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * Called by the views with the position of the pointer in them. Right
     * after a move of the robot, it gives the screen position of the window.
     */
    static void pointerAt(Gtk4Window window, double x, double y) {
        if (window == null) {
            return;
        }
        if (pending) {
            reportMove(window, x, y);
        }
        if (Gtk4TestSession.isEnabled() || window.isPositionKnown()) {
            seenX = window.getScreenX() + x;
            seenY = window.getScreenY() + y;
            seenTime = System.nanoTime();
        }
    }

    /**
     * A view saw the pointer while a move of the robot is pending: the move
     * reached the application, and gives where its window is.
     */
    private static void reportMove(Gtk4Window window, double x, double y) {
        boolean inTime = System.nanoTime() - pendingTime <= POINTER_REPORT_NANOS;
        reported |= inTime;
        if (!window.isShowingToplevel()) {
            return;
        }
        pending = false;
        // Too late, the user may have moved the pointer
        if (inTime && !Gtk4TestSession.isEnabled()) {
            window.setViewScreenPosition((int) Math.round(pendingX - x), (int) Math.round(pendingY - y));
        }
    }

    /**
     * Whether a pointer event that a view got is one of the clicks of the
     * last pick of a color, which went to the view because the picker of the
     * desktop was not shown yet. The time of the event is in the 32-bit
     * milliseconds of the monotonic clock of the compositor, which Mutter and
     * KWin share with Java.
     */
    static boolean isPickEvent(int eventTime) {
        if (pickFrom == 0) {
            return false;
        }
        long now = monotonicMillis();
        long time = unwrapEventTime(eventTime, now);
        boolean sameClock = Math.abs(now - time) <= PICK_CLOCK_SKEW_MS;
        return sameClock && time >= pickFrom && time <= pickUntil;
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    /** The full milliseconds of a 32-bit event time, the nearest to now. */
    private static long unwrapEventTime(int eventTime, long now) {
        long time = (now & ~0xffffffffL) | Integer.toUnsignedLong(eventTime);
        return time > now + (1L << 31) ? time - (1L << 32) : time;
    }

    // One remote desktop session for the robots and the touch robot
    private static Gtk4EiInput sharedInput;
    // Why the robot has no input, which asking again would not change: no
    // libei, no portal, or the user said no
    private static UnsupportedOperationException unavailable;

    static Gtk4EiInput sharedInput() {
        if (unavailable != null) {
            throw new UnsupportedOperationException(unavailable.getMessage(), unavailable);
        }
        if (sharedInput != null && sharedInput.isDisconnected()) {
            Gtk4Log.log(Gtk4Log.Category.FOCUS, "robot: connecting again");
            sharedInput.close();
            sharedInput = null;
        }
        if (sharedInput == null) {
            try {
                sharedInput = Gtk4EiInput.connect();
            } catch (UnsupportedOperationException e) {
                unavailable = e;
                throw e;
            }
        }
        if (Gtk4TestSession.isEnabled()) {
            Gtk4TestSession.awaitPlacements();
        }
        return sharedInput;
    }

    /**
     * Sends input; when the compositor ended the connection, connects again
     * and sends it once more.
     */
    private static void send(Consumer<Gtk4EiInput> action) {
        try {
            action.accept(sharedInput());
        } catch (Gtk4EiInput.DisconnectedException e) {
            action.accept(sharedInput());
        }
    }

    @Override
    public void keyPress(KeyCode keyCode) {
        Application.checkEventThread();
        key(keyCode, true);
    }

    @Override
    public void keyRelease(KeyCode keyCode) {
        Application.checkEventThread();
        key(keyCode, false);
    }

    private void key(KeyCode keyCode, boolean press) {
        int keycode = Gtk4KeyMap.toRobotKeycode(keyCode.getCode());
        if (keycode <= 0) {
            throw new IllegalArgumentException("The robot cannot type " + keyCode);
        }
        send(in -> in.key(keycode, press));
    }

    /**
     * Where the pointer is: where a view last saw it, when that is after the
     * last move of the robot (the user may have moved it), or else where the
     * robot put it.
     */
    @Override
    public double getMouseX() {
        Application.checkEventThread();
        return seenTime > lastMoveTime ? seenX : mouseX;
    }

    @Override
    public double getMouseY() {
        Application.checkEventThread();
        return seenTime > lastMoveTime ? seenY : mouseY;
    }

    @Override
    public void mouseMove(double x, double y) {
        Application.checkEventThread();
        send(in -> {
            double[] p = toScreen(in, x, y);
            moveTo(in, p[0], p[1]);
            mouseX = p[0];
            mouseY = p[1];
        });
        lastMoveTime = System.nanoTime();
    }

    /**
     * The screen point of a point computed with the position a window had:
     * one assumed off the test session, or one the user has since changed by
     * moving the window in it.
     */
    private static double[] toScreen(Gtk4EiInput in, double x, double y) {
        List<Gtk4Window> hits = Gtk4Window.getToplevelsAt((int) Math.floor(x), (int) Math.floor(y));
        if (!hits.isEmpty()) {
            Gtk4Window w = hits.getFirst();
            int assumedX = w.getScreenX();
            int assumedY = w.getScreenY();
            boolean moved = Gtk4TestSession.isEnabled()
                    ? w.refreshTestPosition()
                    : !w.isPositionKnown() && locate(in, w);
            if (moved) {
                return new double[] {x - assumedX + w.getScreenX(), y - assumedY + w.getScreenY()};
            }
        }
        return new double[] {x, y};
    }

    /**
     * Moves the pointer and waits until the compositor gives it to the
     * surface under it, so that what follows goes there.
     */
    private static void moveTo(Gtk4EiInput in, double x, double y) {
        pending(x, y);
        in.moveTo(x, y);
        awaitPointerFocus(x, y);
    }

    // A move whose position a view will report
    private static void pending(double x, double y) {
        pendingX = x;
        pendingY = y;
        pendingTime = System.nanoTime();
        pending = true;
        reported = false;
    }

    /**
     * Over a window of the application whose position is known, the view
     * reports the motion once the compositor gave it the pointer: the robot
     * waits for that, running the main loop to receive it. Elsewhere the
     * compositor finds the surface at its next frame, which the robot gives
     * it time for.
     */
    private static void awaitPointerFocus(double x, double y) {
        if (isOverKnownWindow(x, y)) {
            runMainLoopWhile(() -> !reported, POINTER_FOCUS_NANOS);
            if (reported) {
                return;
            }
        }
        Gtk4EiInput.sleep(Gtk4EiInput.POINTER_FOCUS_DELAY);
    }

    private static boolean isOverKnownWindow(double x, double y) {
        List<Gtk4Window> hits = Gtk4Window.getToplevelsAt((int) Math.floor(x), (int) Math.floor(y));
        return !hits.isEmpty() && (Gtk4TestSession.isEnabled() || hits.getFirst().isPositionKnown());
    }

    /**
     * Moves the pointer over the screens until the window reports it: from
     * the screen where the pointer is, then the others, each from its center
     * outwards, for a few seconds at most.
     */
    private static boolean locate(Gtk4EiInput in, Gtk4Window w) {
        long timeout = System.nanoTime() + LOCATE_TIMEOUT_NANOS;
        int count = 0;
        for (double[] p : probes(w, in.lastPosition())) {
            if (++count > MAX_PROBES || System.nanoTime() > timeout) {
                break;
            }
            probe(in, p[0], p[1]);
            if (w.isPositionKnown()) {
                Gtk4Log.log(Gtk4Log.Category.SIZING, "robot: found the window after %d moves", count);
                return true;
            }
        }
        Gtk4Log.log(Gtk4Log.Category.SIZING, "robot: the window was not found after %d moves", count);
        return false;
    }

    /** The points where to look for a window, in the order to try them. */
    private static List<double[]> probes(Gtk4Window w, double[] pointer) {
        int step = Math.max(24, Math.min(w.getViewWidth(), w.getViewHeight()) / 2);
        List<Screen> screens = new ArrayList<>(Screen.getScreens());
        if (pointer != null) {
            screens.sort(Comparator.comparingInt(screen -> contains(screen, pointer[0], pointer[1]) ? 0 : 1));
        }
        List<double[]> probes = new ArrayList<>();
        for (Screen screen : screens) {
            probes.addAll(screenProbes(screen, step));
        }
        return probes;
    }

    /** A grid of points over a screen, from its center outwards. */
    private static List<double[]> screenProbes(Screen screen, int step) {
        double cx = screen.getX() + screen.getWidth() / 2.0;
        double cy = screen.getY() + screen.getHeight() / 2.0;
        List<double[]> points = new ArrayList<>();
        for (int y = screen.getY() + step / 2; y < screen.getY() + screen.getHeight(); y += step) {
            for (int x = screen.getX() + step / 2; x < screen.getX() + screen.getWidth(); x += step) {
                points.add(new double[] {x, y});
            }
        }
        points.sort(Comparator.comparingDouble(p -> Math.hypot(p[0] - cx, p[1] - cy)));
        return points;
    }

    /** Moves the pointer to a point and gives the views a moment to report it. */
    private static void probe(Gtk4EiInput in, double x, double y) {
        pending(x, y);
        in.moveTo(x, y);
        runMainLoopWhile(() -> pending, LOCATE_WAIT_NANOS);
    }

    /** Runs the main loop of the application while a condition holds, for a time at most. */
    private static void runMainLoopWhile(BooleanSupplier condition, long nanos) {
        GMainContext context = GMainContext.default_();
        long deadline = System.nanoTime() + nanos;
        while (condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (!context.iteration(false)) {
                Gtk4EiInput.sleep(1);
            }
        }
    }

    private static boolean contains(Screen screen, double x, double y) {
        return x >= screen.getX() && x < screen.getX() + screen.getWidth()
                && y >= screen.getY() && y < screen.getY() + screen.getHeight();
    }

    @Override
    public void mousePress(MouseButton... buttons) {
        Application.checkEventThread();
        for (MouseButton button : buttons) {
            int evdev = evdevButton(button);
            send(in -> in.button(evdev, true));
        }
    }

    @Override
    public void mouseRelease(MouseButton... buttons) {
        Application.checkEventThread();
        for (MouseButton button : buttons) {
            int evdev = evdevButton(button);
            send(in -> in.button(evdev, false));
        }
    }

    @Override
    public void mouseWheel(int wheelAmt) {
        Application.checkEventThread();
        send(in -> in.scroll(wheelAmt));
    }

    private static int evdevButton(MouseButton button) {
        return switch (button) {
            case PRIMARY -> BTN_LEFT;
            case SECONDARY -> BTN_RIGHT;
            case MIDDLE -> BTN_MIDDLE;
            case BACK -> BTN_SIDE;
            case FORWARD -> BTN_EXTRA;
            default -> throw new IllegalArgumentException("The robot cannot press " + button);
        };
    }

    // ---------------------------------------------------------------------
    // Screen capture

    /**
     * Reads the pixel the compositor shows at a point: with the color picker
     * of the desktop, or else from a screenshot, or else from the frame of the
     * window there.
     */
    @Override
    public Color getPixelColor(double x, double y) {
        Application.checkEventThread();
        Color picked = pickColor(x, y);
        if (picked != null) {
            return picked;
        }
        int[] data = new int[1];
        double scale = scale();
        getScreenCapture((int) Math.floor(x * scale), (int) Math.floor(y * scale), 1, 1, data, false);
        return convertFromIntArgb(data[0]);
    }

    /**
     * Picks the color at a point with the color picker portal, which the
     * robot clicks with its pointer, then puts the pointer back. The whole
     * pick runs on the thread of the robot.
     *
     * @return the color, or null when the picker is not available, or a
     *     button is held, which its click would release
     */
    private Color pickColor(double x, double y) {
        Gtk4EiInput in = pickerInput();
        if (in == null) {
            return null;
        }
        double[] back = in.lastPosition();
        double[] p = toScreen(in, x, y);
        Color color = pickAt(in, p[0], p[1]);
        if (back != null && !in.isDisconnected()) {
            moveTo(in, back[0], back[1]);
        }
        if (color == null && !in.isDisconnected()) {
            colorPickerFailed = true;
            Gtk4Log.log(Gtk4Log.Category.FOCUS, "robot: PickColor does not work, reading pixels from screenshots");
        }
        return color;
    }

    /**
     * The input for a pick, or null when the picker is not available, or a
     * button is held, which its click would release.
     */
    private static Gtk4EiInput pickerInput() {
        if (!USE_COLOR_PICKER || colorPickerFailed || !Gtk4ScreenshotPortal.hasPickColor()) {
            return null;
        }
        try {
            Gtk4EiInput in = sharedInput();
            return in.buttonsHeld() > 0 ? null : in;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Picks the color at a screen point on the thread of the robot. The
     * clicks that reach the views, before the picker is shown, are dropped.
     */
    private static Color pickAt(Gtk4EiInput in, double x, double y) {
        pending(x, y);
        pickFrom = monotonicMillis();
        pickUntil = Long.MAX_VALUE;
        try {
            return Gtk4RobotThread.call(() -> Gtk4ScreenshotPortal.pickColor(() -> in.click(x, y)));
        } catch (Gtk4EiInput.DisconnectedException e) {
            return null;
        } finally {
            // The compositor processed the clicks of the pick before it answered
            pickUntil = monotonicMillis();
        }
    }

    /**
     * A screenshot of all the screens through the portal, or null when it
     * does not take them. It runs on the thread of the robot, while the
     * application keeps responding: the desktop may ask the user first.
     */
    private static Gtk4ScreenshotPortal.Image screenshot() {
        if (!USE_SCREENSHOT || screenshotFailed || !Gtk4ScreenshotPortal.hasScreenshot()) {
            return null;
        }
        Gtk4ScreenshotPortal.Image shot = Gtk4RobotThread.callResponsive(Gtk4ScreenshotPortal::screenshot);
        if (shot == null) {
            screenshotFailed = true;
            Gtk4Log.log(Gtk4Log.Category.FOCUS, "robot: reading pixels from the frames of the windows");
        }
        return shot;
    }

    /**
     * Captures a region in physical pixels: from a screenshot of all the
     * screens, whose origin is that of their union, or else from the frames
     * of the windows of the application.
     */
    @Override
    public void getScreenCapture(int x, int y, int width, int height, int[] data, boolean scaleToFit) {
        Application.checkEventThread();
        Gtk4ScreenshotPortal.Image shot = screenshot();
        if (shot != null) {
            capture(shot, x, y, width, height, data);
        } else {
            captureFrames(x, y, width, height, data);
        }
    }

    private static void capture(Gtk4ScreenshotPortal.Image shot, int x, int y, int width, int height, int[] data) {
        ScreenUnion union = ScreenUnion.of(Screen.getScreens());
        // The pixels of the screenshot for a logical unit
        double shotScale = union.width() > 0 ? shot.width() / union.width() : 1.0;
        double scale = scale();
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                double lx = (x + i + 0.5) / scale - union.x();
                double ly = (y + j + 0.5) / scale - union.y();
                data[j * width + i] = shot.get((int) Math.floor(lx * shotScale), (int) Math.floor(ly * shotScale));
            }
        }
    }

    /** The origin and the width of the union of the screens, in logical coordinates. */
    private record ScreenUnion(double x, double y, double width) {
        static ScreenUnion of(List<Screen> screens) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            for (Screen screen : screens) {
                minX = Math.min(minX, screen.getX());
                minY = Math.min(minY, screen.getY());
                maxX = Math.max(maxX, screen.getX() + screen.getWidth());
            }
            return new ScreenUnion(minX, minY, maxX - minX);
        }
    }

    private void captureFrames(int x, int y, int width, int height, int[] data) {
        double scale = scale();
        Map<Gtk4Window, Frame> frames = new HashMap<>();
        List<Gtk4Window> stackingOrder = Gtk4Window.getStackingOrder();
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                // Physical pixel to logical position
                int lx = (int) Math.floor((x + i) / scale);
                int ly = (int) Math.floor((y + j) / scale);
                Gtk4Window window = Gtk4Window.getWindowAt(stackingOrder, lx, ly);
                int argb = 0;
                if (window != null) {
                    Frame frame = frames.computeIfAbsent(window, Gtk4Robot::capture);
                    if (frame != null) {
                        double fx = (x + i - window.getScreenX() * scale) * frame.width / (window.getViewWidth() * scale);
                        double fy = (y + j - window.getScreenY() * scale) * frame.height / (window.getViewHeight() * scale);
                        argb = frame.get((int) fx, (int) fy);
                    }
                }
                data[j * width + i] = argb;
            }
        }
    }

    /**
     * The scale of the physical pixels of the captures: that of the main
     * screen, with which GlassRobot converts the positions of the captures.
     */
    private static double scale() {
        Screen main = Screen.getMainScreen();
        float scale = main != null ? main.getRecommendedOutputScaleX() : 1f;
        return scale > 0 ? scale : 1.0;
    }

    private record Frame(int width, int height, int stride, ByteBuffer pixels) {
        int get(int x, int y) {
            if (x < 0 || y < 0 || x >= width || y >= height) {
                return 0;
            }
            return pixels.getInt(y * stride + x * 4);
        }
    }

    /**
     * Downloads the frame a window presents as BGRA, which read as
     * little-endian ints is ARGB.
     */
    private static Frame capture(Gtk4Window window) {
        Gtk4Compositor compositor = window.getCompositor();
        GdkTexture texture = compositor != null ? compositor.getCurrentTexture() : null;
        if (texture == null || texture.isNull()) {
            return null;
        }
        GdkTextureDownloader downloader = GdkTextureDownloader.create(texture);
        downloader.setFormat(Gdk.MEMORY_B8G8R8A8);
        long[] stride = new long[1];
        GBytes bytes = downloader.downloadBytes(stride);
        downloader.free();
        byte[] data = bytes.getData();
        bytes.unref();
        return new Frame(texture.getWidth(), texture.getHeight(), (int) stride[0],
                ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN));
    }
}
