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

import com.sun.glass.ui.gtk4.wrappers.GBytes;
import com.sun.glass.ui.gtk4.wrappers.GDBusConnection;
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.GVariant;
import com.sun.glass.ui.gtk4.wrappers.GVariantType;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkTexture;
import com.sun.glass.ui.gtk4.wrappers.GdkTextureDownloader;
import com.sun.glass.ui.gtk4.wrappers.Gio;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sun.glass.ui.gtk4.Gtk4RemoteDesktopPortal.PORTAL;
import static com.sun.glass.ui.gtk4.Gtk4RemoteDesktopPortal.PORTAL_PATH;
import static com.sun.glass.ui.gtk4.Gtk4RemoteDesktopPortal.REQUEST;
import static com.sun.glass.ui.gtk4.Gtk4RemoteDesktopPortal.RESPONSE_CALLBACK;

/**
 * What the compositor shows on the screens, through the Screenshot interface
 * of the XDG desktop portal, as libei cannot read the screen.
 * <p>
 * A pixel comes from PickColor, as the eyedropper applications do: the desktop
 * shows its picker, which the robot clicks with its pointer at the point, and
 * answers the color there, without a screenshot. The picker appears a moment
 * after the call, so the click waits for it, and comes again while the picker
 * has not answered. A region comes from a screenshot of all the screens, which
 * the portal saves in a file, read and then deleted here.
 * <p>
 * The requests run on the thread of the robot, whose main context receives
 * their answers: the main loop of the application does not run meanwhile.
 */
final class Gtk4ScreenshotPortal {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.FOCUS;

    private static final String SCREENSHOT = "org.freedesktop.portal.Screenshot";
    private static final int RESPONSE_SUCCESS = 0;

    // The clicks of PickColor: the first one, and then one every while, until
    // the picker answers
    private static final long PICK_FIRST_CLICK_MS = Long.getLong("glass.gtk4.colorPicker.delay", 80);
    private static final long PICK_CLICK_EVERY_MS = 400;
    private static final int PICK_CLICKS = 8;
    // The desktop may ask the user to allow the screenshot
    private static final long SCREENSHOT_TIMEOUT_MS = 60_000;

    private static final AtomicInteger tokens = new AtomicInteger();

    // The version of the interface: -1 until it is asked, 0 when there is none
    private static int version = -1;

    private Gtk4ScreenshotPortal() {
    }

    private static int version() {
        if (version < 0) {
            version = Gtk4RemoteDesktopPortal.portalVersion(SCREENSHOT);
            Gtk4Log.log(LOG, "screenshot portal version %d", version);
        }
        return version;
    }

    /** Whether the portal takes screenshots. */
    static boolean hasScreenshot() {
        return version() >= 1;
    }

    /** Whether the portal has PickColor, from version 2. */
    static boolean hasPickColor() {
        return version() >= 2;
    }

    /**
     * Opens the picker of the desktop and has it pick a color.
     *
     * @param click clicks the point to read, called when the picker should be
     *     shown, and again while it does not answer
     * @return the color, or null when the desktop did not pick one
     */
    static Color pickColor(Runnable click) {
        var clicker = new PickClicker(click);
        GVariant results = request("PickColor", new GVariant[0],
                PICK_FIRST_CLICK_MS + PICK_CLICK_EVERY_MS * PICK_CLICKS, clicker::tick);
        if (results == null) {
            return null;
        }
        try {
            Gtk4Log.log(LOG, "PickColor answered after %d clicks", clicker.clicks);
            return colorOf(results);
        } finally {
            results.unref();
        }
    }

    /** Clicks for PickColor: once the picker should be shown, then every while. */
    private static final class PickClicker {
        private final Runnable click;
        private int clicks;

        PickClicker(Runnable click) {
            this.click = click;
        }

        void tick(long elapsedMs) {
            if (clicks < PICK_CLICKS && elapsedMs >= PICK_FIRST_CLICK_MS + PICK_CLICK_EVERY_MS * clicks) {
                clicks++;
                click.run();
            }
        }
    }

    /** The color of the results of PickColor, three doubles from 0 to 1 in sRGB. */
    private static Color colorOf(GVariant results) {
        GVariant rgb = lookup(results, "color", "(ddd)");
        if (rgb == null) {
            return null;
        }
        try {
            return Color.color(component(rgb, 0), component(rgb, 1), component(rgb, 2));
        } finally {
            rgb.unref();
        }
    }

    private static double component(GVariant rgb, int index) {
        GVariant value = rgb.getChildValue(index);
        try {
            return Math.clamp(value.getDouble(), 0.0, 1.0);
        } finally {
            value.unref();
        }
    }

    /** A screenshot of all the screens, as ARGB ints, from the origin of their union. */
    record Image(int width, int height, int[] argb) {
        int get(int x, int y) {
            return x < 0 || y < 0 || x >= width || y >= height ? 0 : argb[y * width + x];
        }
    }

    /**
     * Takes a screenshot of all the screens, without a dialog to choose what
     * it shows.
     *
     * @return the screenshot, or null when the portal did not take one
     */
    static Image screenshot() {
        GVariant results = request("Screenshot", new GVariant[] {
            Gtk4RemoteDesktopPortal.entry("interactive", GVariant.createBoolean_(false)),
            Gtk4RemoteDesktopPortal.entry("modal", GVariant.createBoolean_(false))
        }, SCREENSHOT_TIMEOUT_MS, null);
        if (results == null) {
            return null;
        }
        Path file;
        try {
            file = fileOf(results);
        } finally {
            results.unref();
        }
        return file != null ? readAndDelete(file) : null;
    }

    /** The file of the results of Screenshot, or null. */
    private static Path fileOf(GVariant results) {
        GVariant value = lookup(results, "uri", "s");
        if (value == null) {
            Gtk4Log.log(LOG, "the screenshot portal returned no file");
            return null;
        }
        String uri = value.getString(null);
        value.unref();
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            Gtk4Log.log(LOG, "cannot read the screenshot %s: %s", uri, e);
            return null;
        }
    }

    private static Image readAndDelete(Path file) {
        try {
            return read(file);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                Gtk4Log.log(LOG, "cannot delete the screenshot %s: %s", file, e);
            }
        }
    }

    private static Image read(Path file) {
        GdkTexture texture;
        try {
            texture = GdkTexture.createFromFilename(file.toString());
        } catch (RuntimeException e) {
            Gtk4Log.log(LOG, "cannot read the screenshot %s: %s", file, e);
            return null;
        }
        if (texture.isNull()) {
            return null;
        }
        try {
            return download(texture);
        } finally {
            texture.unref();
        }
    }

    /** The pixels of a texture, opaque, as the screen is. */
    private static Image download(GdkTexture texture) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        GdkTextureDownloader downloader = GdkTextureDownloader.create(texture);
        // B, G, R, A bytes, which read as little-endian ints are ARGB
        downloader.setFormat(Gdk.MEMORY_B8G8R8A8);
        long[] stride = new long[1];
        GBytes bytes = downloader.downloadBytes(stride);
        downloader.free();
        ByteBuffer pixels = ByteBuffer.wrap(bytes.getData()).order(ByteOrder.LITTLE_ENDIAN);
        bytes.unref();
        int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = pixels.getInt((int) (y * stride[0] + x * 4L)) | 0xff000000;
            }
        }
        return new Image(width, height, argb);
    }

    /** A value of a dictionary of results, of a type, or null. */
    private static GVariant lookup(GVariant results, String key, String type) {
        GVariantType variantType = GVariantType.create(type);
        GVariant value = results.lookupValue(key, variantType);
        variantType.free();
        return value == null || value.isNull() ? null : value;
    }

    // What a request does while it waits, with the milliseconds since the call
    private interface Waiting {
        void tick(long elapsedMs);
    }

    /**
     * Calls a method of the Screenshot interface, whose arguments are the
     * parent window and the options, and runs the main context of the robot
     * thread until the request answers or the time is over, when the request
     * is closed.
     *
     * @return the results of the answer, or null when the portal refused, failed or did not answer
     */
    private static GVariant request(String method, GVariant[] options, long timeoutMs, Waiting waiting) {
        GDBusConnection bus = Gtk4RemoteDesktopPortal.bus();
        String token = "jfx_shot_" + ProcessHandle.current().pid() + "_" + tokens.incrementAndGet();
        String expected = requestPath(bus, token);
        try (Arena arena = Arena.ofConfined()) {
            var waiter = new Waiter();
            MemorySegment callback = waiter.callback(arena);
            int subscription = subscribe(bus, expected, callback);
            String path = expected;
            try {
                path = call(bus, method, withToken(options, token));
                if (!expected.equals(path)) {
                    bus.signalUnsubscribe(subscription);
                    subscription = subscribe(bus, path, callback);
                }
                if (!waiter.await(timeoutMs, waiting)) {
                    Gtk4Log.log(LOG, "the screenshot portal did not answer %s", method);
                    close(bus, path);
                    return null;
                }
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "the screenshot portal failed %s: %s", method, e);
                if (!waiter.done) {
                    close(bus, path);
                }
                return null;
            } finally {
                bus.signalUnsubscribe(subscription);
            }
            return waiter.successfulResults(method);
        }
    }

    /** The object path of the Request that a call with a token creates. */
    private static String requestPath(GDBusConnection bus, String token) {
        String sender = bus.getUniqueName().substring(1).replace('.', '_');
        return PORTAL_PATH + "/request/" + sender + "/" + token;
    }

    private static GVariant[] withToken(GVariant[] options, String token) {
        GVariant[] all = Arrays.copyOf(options, options.length + 1);
        all[options.length] = Gtk4RemoteDesktopPortal.entry("handle_token", GVariant.createString(token));
        return all;
    }

    /** Calls a method of the interface and returns the path of its Request. */
    private static String call(GDBusConnection bus, String method, GVariant[] options) {
        GVariant reply = bus.callSync(PORTAL, PORTAL_PATH, SCREENSHOT, method,
                GVariant.createTuple(new GVariant[] {GVariant.createString(""),
                    Gtk4RemoteDesktopPortal.array("{sv}", options)}),
                null, Gio.G_DBUS_CALL_FLAGS_NONE, -1, null);
        GVariant handle = reply.getChildValue(0);
        String path = handle.getString(null);
        handle.unref();
        reply.unref();
        return path;
    }

    /** Ends a request, and the picker of the desktop with it. */
    private static void close(GDBusConnection bus, String path) {
        try {
            bus.callSync(PORTAL, path, REQUEST, "Close", GVariant.createTuple(new GVariant[0]), null,
                    Gio.G_DBUS_CALL_FLAGS_NONE, -1, null).unref();
        } catch (RuntimeException e) {
            Gtk4Log.log(LOG, "cannot close the screenshot portal request: %s", e);
        }
    }

    private static int subscribe(GDBusConnection bus, String path, MemorySegment callback) {
        return bus.signalSubscribe(PORTAL, REQUEST, "Response", path, null,
                Gio.G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE, callback, null, null);
    }

    /** Waits for the Response signal of a Request. */
    private static final class Waiter {
        private int code = -1;
        private GVariant results;
        private boolean done;

        MemorySegment callback(Arena arena) {
            try {
                return Gtk4SignalHelper.upcall(MethodHandles.lookup(),
                        MethodHandles.lookup().bind(this, "response", MethodType.methodType(void.class,
                                MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
                                MemorySegment.class, GVariant.class, MemorySegment.class)),
                        RESPONSE_CALLBACK, arena);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Iterates the main context of the robot thread until the response
         * arrives, or the time is over.
         *
         * @return whether the response arrived
         */
        boolean await(long timeoutMs, Waiting waiting) {
            GMainContext context = Gtk4RobotThread.context();
            long called = System.nanoTime();
            long deadline = called + timeoutMs * 1_000_000L;
            while (!done && System.nanoTime() <= deadline) {
                if (!context.iteration(false)) {
                    Gtk4EiInput.sleep(1);
                }
                if (waiting != null && !done) {
                    waiting.tick((System.nanoTime() - called) / 1_000_000L);
                }
            }
            return done;
        }

        /** The results of a successful response, or null. */
        GVariant successfulResults(String method) {
            if (code == RESPONSE_SUCCESS) {
                return results;
            }
            Gtk4Log.log(LOG, "the screenshot portal refused %s (response %d)", method, code);
            if (results != null) {
                results.unref();
            }
            return null;
        }

        @SuppressWarnings("unused")
        void response(MemorySegment connection, MemorySegment sender, MemorySegment path,
                      MemorySegment iface, MemorySegment signal, GVariant parameters, MemorySegment data) {
            if (done) {
                return;
            }
            GVariant value = parameters.getChildValue(0);
            code = value.getUint32();
            value.unref();
            results = parameters.getChildValue(1);
            done = true;
        }
    }
}
