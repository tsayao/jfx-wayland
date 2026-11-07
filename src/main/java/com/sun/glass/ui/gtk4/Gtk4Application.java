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
import com.sun.glass.ui.Accessible;
import com.sun.glass.ui.Application;
import com.sun.glass.ui.CommonDialogs.ExtensionFilter;
import com.sun.glass.ui.CommonDialogs.FileChooserResult;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.Size;
import com.sun.glass.ui.Timer;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk4.wrappers.Adw;
import com.sun.glass.ui.gtk4.wrappers.AdwWindow;
import com.sun.glass.ui.gtk4.wrappers.GListModel;
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GdkDevice;
import com.sun.glass.ui.gtk4.wrappers.GdkDmabufTextureBuilder;
import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;
import com.sun.glass.ui.gtk4.wrappers.GdkMonitor;
import com.sun.glass.ui.gtk4.wrappers.GdkRectangle;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkGraphicsOffload;
import com.sun.glass.ui.gtk4.wrappers.GtkSettings;
import com.sun.glass.ui.gtk4.wrappers.GtkUriLauncher;
import com.sun.javafx.application.preferences.PreferenceMapping;
import com.sun.prism.impl.PrismSettings;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class Gtk4Application extends Application {

    private static final Gtk4Log.Category SCREEN = Gtk4Log.Category.SCREEN;
    private static final int MIN_GTK_MAJOR = 4;
    private static final int MIN_GTK_MINOR = 14;

    static final boolean ADWAITA = useAdwaita() && AdwWindow.isAvailable();

    /**
     * KWin offsets the area it blurs behind a client-side decorated window
     * from the region in surface coordinates that GTK gives it, so there the
     * backdrop is off unless -Dglass.gtk4.backdrop.kwin=true.
     */
    private static final boolean BACKDROP_OFF_ON_KWIN = Boolean.getBoolean("glass.gtk4.backdrop")
            && isDesktop("KDE") && !Boolean.getBoolean("glass.gtk4.backdrop.kwin");

    static final boolean BACKDROP = Boolean.getBoolean("glass.gtk4.backdrop") && !BACKDROP_OFF_ON_KWIN;

    static final float UI_SCALE_OVERRIDE = PrismSettings.allowHiDPIScaling
            ? parseScale("glass.gtk.uiScale") : -1.0f;

    private final Object queueLock = new Object();
    private final ArrayDeque<Runnable> runnableQueue = new ArrayDeque<>();
    private final ArrayDeque<NestedLoop> nestedLoops = new ArrayDeque<>();
    private static final List<Arena> deferredArenas = new ArrayList<>();
    private volatile boolean running;

    private GMainContext mainContext;
    private Thread eventThread;
    private Gtk4SignalHelper appSignals;
    private Gtk4SignalHelper monitorSignals;
    private Gtk4PlatformSupport platformSupport;

    public Gtk4Application() {
        checkRequirements();
        Gtk4TestSession.initialize();
    }

    /**
     * Whether the windows are AdwWindows: by default only under GNOME, as
     * elsewhere libadwaita would ignore the GTK theme of the desktop, or as
     * -Dglass.gtk4.adwaita says.
     */
    private static boolean useAdwaita() {
        String value = System.getProperty("glass.gtk4.adwaita");
        if (value != null) {
            return !"false".equals(value);
        }
        return isDesktop("GNOME");
    }

    /** Whether XDG_CURRENT_DESKTOP names the desktop. */
    private static boolean isDesktop(String name) {
        String desktop = System.getenv("XDG_CURRENT_DESKTOP");
        return desktop != null && Arrays.stream(desktop.split(":")).anyMatch(name::equalsIgnoreCase);
    }

    private static float parseScale(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            value = System.getenv(name);
        }
        if (value == null) {
            return -1.0f;
        }
        value = value.trim();
        float scale;
        if (value.endsWith("%")) {
            scale = Integer.parseInt(value.substring(0, value.length() - 1)) / 100.0f;
        } else if (value.endsWith("DPI") || value.endsWith("dpi")) {
            scale = Integer.parseInt(value.substring(0, value.length() - 3)) / 96.0f;
        } else {
            scale = Float.parseFloat(value);
        }
        if (PrismSettings.verbose) {
            System.out.println("Forcing UI scaling factor: " + scale);
        }
        return scale;
    }

    @Override
    protected void runLoop(final Runnable launchable) {
        running = true;
        mainContext = GMainContext.default_();
        final Thread toolkitThread = new Thread(() -> {
            Gtk.init();
            if (ADWAITA) {
                Adw.init();
            }
            Gtk4CssUtils.ensureRegistered();
            publishBackdropState();

            appSignals = new Gtk4SignalHelper(Arena.global(), MethodHandles.lookup());
            connectMonitorSignals();
            platformSupport = new Gtk4PlatformSupport(this::notifyPreferencesChanged);

            processRunnableQueue();
            while (running) {
                iterate();
                closeDeferredArenas();
            }
            closeDeferredArenas();
        }, "Gtk4NativeMainLoopThread");

        eventThread = toolkitThread;
        setEventThread(toolkitThread);

        _invokeLater(launchable);

        toolkitThread.start();
    }

    /**
     * Runs one blocking iteration of the GLib main loop followed by the
     * pending Glass runnables and the remaining ready sources.
     */
    private void iterate() {
        boolean idle;
        synchronized (queueLock) {
            idle = runnableQueue.isEmpty();
        }
        mainContext.iteration(idle);
        processRunnableQueue();
        // Once terminating there is no event thread, so the handlers of the
        // remaining sources cannot call into Glass
        while (running && mainContext.pending()) {
            mainContext.iteration(false);
        }
    }

    @Override
    protected void finishTerminating() {
        running = false;
        mainContext.wakeup();
        setEventThread(null);
        super.finishTerminating();
    }

    /**
     * Transparent windows present their frames as the opaque ones do: the
     * dma-bufs of the pipelines carry premultiplied alpha, and reading each
     * frame back to upload it costs more than the frame. The software
     * pipeline uploads its frames anyway.
     */
    @Override
    public boolean shouldUpdateWindow() {
        return false;
    }

    @Override
    protected void _invokeLater(Runnable runnable) {
        synchronized (queueLock) {
            runnableQueue.add(runnable);
        }
        if (mainContext != null) {
            mainContext.wakeup();
        }
    }

    @Override
    protected void _invokeAndWait(Runnable runnable) {
        if (Thread.currentThread() == eventThread) {
            runnable.run();
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        _invokeLater(() -> {
            try {
                runnable.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void closeLater(Arena arena) {
        synchronized (deferredArenas) {
            deferredArenas.add(arena);
        }
    }

    private static void closeDeferredArenas() {
        Arena[] arenas;
        synchronized (deferredArenas) {
            if (deferredArenas.isEmpty()) {
                return;
            }
            arenas = deferredArenas.toArray(new Arena[0]);
            deferredArenas.clear();
        }
        for (Arena arena : arenas) {
            arena.close();
        }
    }

    private void processRunnableQueue() {
        int count;
        synchronized (queueLock) {
            count = runnableQueue.size();
        }
        for (int i = 0; i < count; i++) {
            NestedLoop loop = nestedLoops.peek();
            if (loop != null && loop.done) {
                return;
            }
            Runnable r;
            synchronized (queueLock) {
                r = runnableQueue.poll();
            }
            if (r == null) {
                return;
            }
            try {
                r.run();
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
        }
    }

    private static final class NestedLoop {
        Object result;
        boolean done;
    }

    @Override
    protected Object _enterNestedEventLoop() {
        NestedLoop loop = new NestedLoop();
        nestedLoops.push(loop);
        try {
            while (!loop.done) {
                iterate();
            }
        } finally {
            nestedLoops.remove(loop);
        }
        return loop.result;
    }

    @Override
    protected void _leaveNestedEventLoop(Object retValue) {
        NestedLoop loop = nestedLoops.peek();
        if (loop != null) {
            loop.result = retValue;
            loop.done = true;
        }
        mainContext.wakeup();
    }

    @Override
    public Window createWindow(Window owner, Screen screen, int styleMask) {
        return new Gtk4Window(owner, screen, styleMask);
    }

    @Override
    public View createView() {
        return new Gtk4View();
    }

    @Override
    public Cursor createCursor(int type) {
        return new Gtk4Cursor(type);
    }

    @Override
    public Cursor createCursor(int x, int y, Pixels pixels) {
        return new Gtk4Cursor(x, y, pixels);
    }

    @Override
    protected void staticCursor_setVisible(boolean visible) {
        Gtk4Window.setCursorVisible(visible);
    }

    @Override
    protected Size staticCursor_getBestSize(int width, int height) {
        return Gtk4Cursor.bestSize(width, height);
    }

    @Override
    public Pixels createPixels(int width, int height, ByteBuffer data) {
        return new Gtk4Pixels(width, height, data);
    }

    @Override
    public Pixels createPixels(int width, int height, ByteBuffer data, float scalex, float scaley) {
        return new Gtk4Pixels(width, height, data, scalex, scaley);
    }

    @Override
    public Pixels createPixels(int width, int height, IntBuffer data) {
        return new Gtk4Pixels(width, height, data);
    }

    @Override
    public Pixels createPixels(int width, int height, IntBuffer data, float scalex, float scaley) {
        return new Gtk4Pixels(width, height, data, scalex, scaley);
    }

    @Override
    protected int staticPixels_getNativeFormat() {
        return Pixels.Format.BYTE_BGRA_PRE;
    }

    @Override
    public Accessible createAccessible() {
        return Gtk4Accessible.isAvailable() ? new Gtk4Accessible() : null;
    }

    @Override
    public GlassRobot createRobot() {
        return new Gtk4Robot();
    }

    @Override
    public Timer createTimer(Runnable runnable) {
        return new Gtk4Timer(runnable);
    }

    @Override
    protected int staticTimer_getMinPeriod() {
        return 0;
    }

    @Override
    protected int staticTimer_getMaxPeriod() {
        return 1_000_000;
    }

    private static void checkRequirements() {
        int major;
        int minor;
        int micro;
        try {
            major = Gtk.getMajorVersion();
            minor = Gtk.getMinorVersion();
            micro = Gtk.getMicroVersion();
        } catch (Throwable t) {
            throw new UnsupportedOperationException("GTK 4 (libgtk-4.so.1) cannot be loaded", t);
        }
        List<String> missing = new ArrayList<>();
        if (major < MIN_GTK_MAJOR || (major == MIN_GTK_MAJOR && minor < MIN_GTK_MINOR)) {
            missing.add("GTK %d.%d or later (found %d.%d.%d)"
                    .formatted(MIN_GTK_MAJOR, MIN_GTK_MINOR, major, minor, micro));
        }
        if (!GtkGraphicsOffload.isAvailable()) {
            missing.add("GtkGraphicsOffload");
        }
        if (!GdkDmabufTextureBuilder.isAvailable()) {
            missing.add("GdkDmabufTextureBuilder");
        }
        String wayland = checkWayland();
        if (wayland != null) {
            missing.add(wayland);
        }
        if (!missing.isEmpty()) {
            throw new UnsupportedOperationException("The GTK 4 glass requires " + String.join(", ", missing));
        }
    }

    private static void publishBackdropState() {
        boolean active = BACKDROP && Gtk4BackgroundEffect.isSupported();
        if (BACKDROP_OFF_ON_KWIN) {
            System.err.println("Gtk4: KWin blurs the wrong area behind the windows, so the window backdrop is"
                    + " disabled (-Dglass.gtk4.backdrop.kwin=true enables it)");
        } else if (BACKDROP && !active) {
            System.err.println("Gtk4: the compositor does not support ext-background-effect-v1,"
                    + " so the window backdrop is disabled");
        }
        System.setProperty("glass.gtk4.backdrop.active", String.valueOf(active));
    }

    private static String checkWayland() {
        String backend = System.getenv("GDK_BACKEND");
        if (backend != null && !backend.isBlank()) {
            String first = backend.split(",")[0].trim();
            if (!first.equals("wayland") && !first.equals("*")) {
                return "a Wayland session, but GDK_BACKEND is " + backend + " (Xorg is not supported)";
            }
        }
        String display = System.getenv("WAYLAND_DISPLAY");
        if (display == null || display.isEmpty()) {
            display = "wayland-0";
        }
        Path socket = Path.of(display);
        if (!socket.isAbsolute()) {
            String runtimeDir = System.getenv("XDG_RUNTIME_DIR");
            if (runtimeDir == null || runtimeDir.isEmpty()) {
                return "a Wayland session, but XDG_RUNTIME_DIR is not set (Xorg is not supported)";
            }
            socket = Path.of(runtimeDir, display);
        }
        if (!Files.exists(socket)) {
            return "a Wayland session, but there is no compositor at " + socket + " (Xorg is not supported)";
        }
        return null;
    }

    private void connectMonitorSignals() {
        GdkDisplay display = GdkDisplay.getDefault();
        if (display.isNull()) {
            return;
        }
        GListModel monitors = display.getMonitors();
        appSignals.connect(monitors.cast(GObject::new), "items-changed", this, "onMonitorsChanged",
                void.class, GObject.class, int.class, int.class, int.class);
        connectPerMonitorSignals(monitors);
    }

    private void connectPerMonitorSignals(GListModel monitors) {
        if (monitorSignals != null) {
            monitorSignals.disconnectAllSignals();
        }
        monitorSignals = new Gtk4SignalHelper(Arena.global(), MethodHandles.lookup());
        int count = monitors.getNItems();
        for (int i = 0; i < count; i++) {
            GObject monitor = monitors.getObject(i);
            if (monitor.isNull()) {
                continue;
            }
            monitorSignals.connect(monitor, "invalidate", this, "onMonitorChanged", void.class, GObject.class);
            monitorSignals.connect(monitor, "notify::scale", this, "onMonitorChanged", void.class, GObject.class);
            // A monitor added at runtime comes before its geometry, which only notifies
            monitorSignals.connect(monitor, "notify::geometry", this, "onMonitorChanged", void.class, GObject.class);
            monitor.unref();
        }
    }

    @SuppressWarnings("unused")
    void onMonitorsChanged(GObject model, int position, int removed, int added) {
        GListModel monitors = GdkDisplay.getDefault().getMonitors();
        connectPerMonitorSignals(monitors);
        if (monitors.getNItems() == 0) {
            // Glass needs at least one screen; keep the last configuration
            // until a monitor is back (e.g. during hotplug)
            Gtk4Log.log(SCREEN, "onMonitorsChanged: no monitors");
            return;
        }
        Screen.notifySettingsChanged();
    }

    @SuppressWarnings("unused")
    void onMonitorChanged(GObject monitor) {
        Screen.notifySettingsChanged();
    }

    @Override
    protected double staticScreen_getVideoRefreshPeriod() {
        // A per-surface frame clock drives rendering; no global period
        return 0.0;
    }

    @Override
    protected Screen[] staticScreen_getScreens() {
        GdkDisplay display = GdkDisplay.getDefault();
        if (display.isNull()) {
            Gtk4Log.log(SCREEN, "staticScreen_getScreens: no display");
            return new Screen[0];
        }
        GListModel monitors = display.getMonitors();
        int count = monitors.getNItems();
        Gtk4Log.log(SCREEN, "staticScreen_getScreens: monitorCount=%d", count);
        Screen[] screens = new Screen[count];
        for (int i = 0; i < count; i++) {
            GdkMonitor monitor = monitors.getObject(i).cast(GdkMonitor::new);
            screens[i] = toScreen(i, monitor);
            monitor.unref();
        }
        return screens;
    }

    /**
     * The Glass screen of a monitor. GTK 4 coordinates are logical, so the
     * platform scale is 1; the output scale makes Prism render at the
     * physical resolution of the monitor. Wayland has no work area, so the
     * visible bounds are the full bounds, except in a test session.
     */
    private static Screen toScreen(int index, GdkMonitor monitor) {
        GdkRectangle geometry = new GdkRectangle(0, 0, 0, 0);
        monitor.getGeometry(geometry);
        int x = geometry.x();
        int y = geometry.y();
        int w = geometry.width();
        int h = geometry.height();
        float scale = UI_SCALE_OVERRIDE > 0 ? UI_SCALE_OVERRIDE : (float) Math.max(1.0, monitor.getScale());
        int dpiX = dpi(w * scale, monitor.getWidthMm());
        int dpiY = dpi(h * scale, monitor.getHeightMm());
        Gtk4Log.log(SCREEN, "screen[%d]: x=%d y=%d %dx%d scale=%.2f dpi=%dx%d", index, x, y, w, h, scale, dpiX, dpiY);
        int[] visible = Gtk4TestSession.isEnabled() ? Gtk4TestSession.getWorkArea(x, y, w, h) : null;
        if (visible == null) {
            visible = new int[] {x, y, w, h};
        }
        return new Screen(index, 32,
                x, y, w, h,
                x, y, w, h,
                visible[0], visible[1], visible[2], visible[3],
                dpiX, dpiY,
                1.0f, 1.0f,
                scale, scale);
    }

    /** The resolution of pixels over millimeters, or 96 when the size is not known. */
    private static int dpi(double pixels, int millimeters) {
        return millimeters <= 0 ? 96 : (int) Math.round(pixels * 25.4 / millimeters);
    }

    @Override
    protected FileChooserResult staticCommonDialogs_showFileChooser(
            Window owner, String folder, String filename, String title,
            int type, boolean multipleMode,
            ExtensionFilter[] extensionFilters, int defaultFilterIndex) {
        return Gtk4CommonDialogs.showFileChooser(owner, folder, filename, title, type,
                multipleMode, extensionFilters, defaultFilterIndex);
    }

    @Override
    protected File staticCommonDialogs_showFolderChooser(Window owner, String folder, String title) {
        return Gtk4CommonDialogs.showFolderChooser(owner, folder, title);
    }

    @Override
    protected void _showDocument(String uri) {
        GtkUriLauncher launcher = GtkUriLauncher.create(uri);
        launcher.launch(null, null, MemorySegment.NULL, MemorySegment.NULL);
        launcher.unref();
    }

    @Override
    protected long staticView_getMultiClickTime() {
        return GtkSettings.getDefault().getIntProperty("gtk-double-click-time");
    }

    @Override
    protected int staticView_getMultiClickMaxX() {
        return GtkSettings.getDefault().getIntProperty("gtk-double-click-distance");
    }

    @Override
    protected int staticView_getMultiClickMaxY() {
        return GtkSettings.getDefault().getIntProperty("gtk-double-click-distance");
    }

    @Override
    protected boolean _supportsTransparentWindows() {
        return true;
    }

    @Override
    protected boolean _supportsUnifiedWindows() {
        return false;
    }

    @Override
    protected boolean _supportsExtendedWindows() {
        return true;
    }

    @Override
    protected boolean _supportsInputMethods() {
        return true;
    }

    @Override
    protected int _getKeyCodeForChar(char c, int hint) {
        return Gtk4KeyMap.getKeyCodeForChar(c, hint);
    }

    @Override
    protected int _isKeyLocked(int keyCode) {
        GdkDevice keyboard = GdkDisplay.getDefault().getDefaultSeat().getKeyboard();
        if (keyboard.isNull()) {
            return KeyEvent.KEY_LOCK_UNKNOWN;
        }
        return switch (keyCode) {
            case KeyEvent.VK_CAPS_LOCK -> keyboard.getCapsLockState() ? KeyEvent.KEY_LOCK_ON : KeyEvent.KEY_LOCK_OFF;
            case KeyEvent.VK_NUM_LOCK -> keyboard.getNumLockState() ? KeyEvent.KEY_LOCK_ON : KeyEvent.KEY_LOCK_OFF;
            default -> KeyEvent.KEY_LOCK_UNKNOWN;
        };
    }

    @Override
    public Map<String, Object> getPlatformPreferences() {
        return platformSupport != null ? platformSupport.getPreferences() : Map.of();
    }

    @Override
    public Map<String, PreferenceMapping<?, ?>> getPlatformKeyMappings() {
        return Gtk4PlatformSupport.getKeyMappings();
    }

    @Override
    public Map<String, Class<?>> getPlatformKeys() {
        return Gtk4PlatformSupport.getKeys();
    }
}
