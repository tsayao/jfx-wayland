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

import com.sun.glass.ui.gtk4.wrappers.GDBusConnection;
import com.sun.glass.ui.gtk4.wrappers.GLib;
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.GUnixFDList;
import com.sun.glass.ui.gtk4.wrappers.GVariant;
import com.sun.glass.ui.gtk4.wrappers.GVariantType;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class Gtk4TestSession {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.SIZING;

    private static final String PROPERTY = "glass.gtk4.testSession";
    private static final String GNOME = "gnome";

    private static final int CONNECTION_FLAGS_AUTHENTICATION_CLIENT = 1;
    private static final int CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION = 8;

    private static final String REMOTE_DESKTOP = "org.gnome.Mutter.RemoteDesktop";
    private static final String REMOTE_DESKTOP_PATH = "/org/gnome/Mutter/RemoteDesktop";
    private static final String REMOTE_DESKTOP_SESSION = "org.gnome.Mutter.RemoteDesktop.Session";

    private static final int RETRY_INTERVAL = 20;
    private static final long PLACEMENT_TIMEOUT = TimeUnit.SECONDS.toNanos(2);

    private static final String FIND_WINDOW =
            "global.display.list_all_windows().find(w => w.get_wm_class() === '%s')";
    private static final String MOVE_WINDOW = """
            (() => {
              const w = %s;
              if (!w || !w.get_compositor_private()?.is_mapped()) return null;
              const r = w.get_frame_rect();
              w.move_frame(false, %s ?? r.x, %s ?? r.y);
              const f = w.get_frame_rect();
              return [f.x, f.y];
            })()""";
    private static final String WINDOW_POSITION = """
            (() => {
              const w = %s;
              if (!w || !w.get_compositor_private()?.is_mapped()) return null;
              const f = w.get_frame_rect();
              return [f.x, f.y];
            })()""";

    private static final String ACTIVATE_WINDOW = """
            (() => {
              const w = %s;
              if (!w) return false;
              w.activate(global.display.get_current_time_roundtrip());
              return [w.has_focus(), global.display.focus_window?.get_wm_class() ?? 'none'].join(' ');
            })()""";
    private static final String STACKING_ORDER = """
            global.display.sort_windows_by_stacking(global.display.list_all_windows())
              .filter(w => !w.minimized && w.get_compositor_private()?.is_mapped())
              .map(w => w.get_wm_class()).reverse().join(' ')""";
    private static final String PRIMARY_WORK_AREA = """
            (() => {
              const a = global.workspace_manager.get_active_workspace()
                .get_work_area_for_monitor(global.display.get_primary_monitor());
              return [a.x, a.y, a.width, a.height];
            })()""";
    private static final String WORK_AREA = """
            (() => {
              const d = global.display;
              const ws = global.workspace_manager.get_active_workspace();
              for (let i = 0; i < d.get_n_monitors(); i++) {
                const g = d.get_monitor_geometry(i);
                if (g.x === %d && g.y === %d && g.width === %d && g.height === %d) {
                  const a = ws.get_work_area_for_monitor(i);
                  return [a.x, a.y, a.width, a.height];
                }
              }
              return null;
            })()""";

    private static final boolean ENABLED = isRequested();
    private static final AtomicInteger counter = new AtomicInteger();
    private static final List<Gtk4Window> pending = new ArrayList<>();

    private static GDBusConnection connection;
    private static MemorySegment retryCallback;
    private static int retrySource;

    private Gtk4TestSession() {}

    static boolean isEnabled() {
        return ENABLED;
    }

    private static boolean isRequested() {
        String session = System.getProperty(PROPERTY);
        if (session == null) {
            return false;
        }
        if (!GNOME.equals(session)) {
            throw new IllegalStateException("Unsupported " + PROPERTY + ": " + session);
        }
        return true;
    }

    static void initialize() {
        if (!ENABLED) {
            return;
        }
        String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        if (address == null || address.isEmpty()) {
            throw new IllegalStateException(PROPERTY + " requires a D-Bus session bus");
        }
        try {
            connection = GDBusConnection.createForAddressSync(address,
                    CONNECTION_FLAGS_AUTHENTICATION_CLIENT | CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION, null, null);
            if (!"2".equals(eval("1 + 1"))) {
                throw new IllegalStateException("unexpected reply from org.gnome.Shell.Eval");
            }
            eval("Main.overview.hide()");
        } catch (RuntimeException e) {
            throw new IllegalStateException(PROPERTY + "=" + GNOME
                    + " requires gnome-shell running with --unsafe-mode on the session bus", e);
        }
        Gtk4Log.log(LOG, "test session connected to GNOME Shell");
    }

    static String newTag() {
        return "jfx-test-" + ProcessHandle.current().pid() + "-" + counter.incrementAndGet();
    }

    static int[] moveWindow(String tag, Integer x, Integer y) {
        return parseInts(eval(MOVE_WINDOW.formatted(FIND_WINDOW.formatted(tag), x, y)), 2);
    }

    static int[] getWindowPosition(String tag) {
        return parseInts(eval(WINDOW_POSITION.formatted(FIND_WINDOW.formatted(tag))), 2);
    }

    static int connectToEis() {
        GVariant created = connection.callSync(REMOTE_DESKTOP, REMOTE_DESKTOP_PATH, REMOTE_DESKTOP,
                "CreateSession", null, null, 0, -1, null);
        GVariant path = created.getChildValue(0);
        String session = path.getString(null);
        path.unref();
        created.unref();
        connection.callSync(REMOTE_DESKTOP, session, REMOTE_DESKTOP_SESSION, "Start",
                null, null, 0, -1, null).unref();

        GVariant options = GVariant.createArray(GVariantType.create("{sv}"), new GVariant[0]);
        GUnixFDList[] fds = new GUnixFDList[1];
        GVariant reply = connection.callWithUnixFdListSync(REMOTE_DESKTOP, session, REMOTE_DESKTOP_SESSION,
                "ConnectToEIS", GVariant.createTuple(new GVariant[] {options}), null, 0, -1, null, fds, null);
        try {
            GVariant handle = reply.getChildValue(0);
            int index = handle.getHandle();
            handle.unref();
            if (fds[0] == null || fds[0].isNull()) {
                throw new IllegalStateException("ConnectToEIS returned no file descriptor");
            }
            int fd = fds[0].get(index);
            fds[0].unref();
            Gtk4Log.log(LOG, "remote desktop session %s connected to EIS", session);
            return fd;
        } finally {
            reply.unref();
        }
    }

    static String activateWindow(String tag) {
        return eval(ACTIVATE_WINDOW.formatted(FIND_WINDOW.formatted(tag)));
    }

    /**
     * The tags of the windows shown by the compositor, topmost first.
     */
    static List<String> getStackingOrder() {
        String value = eval(STACKING_ORDER);
        if (value == null || value.length() < 2) {
            return List.of();
        }
        String names = value.substring(1, value.length() - 1).trim();
        return names.isEmpty() ? List.of() : List.of(names.split(" "));
    }

    static int[] getPrimaryWorkArea() {
        return parseInts(eval(PRIMARY_WORK_AREA), 4);
    }

    static int[] getWorkArea(int x, int y, int width, int height) {
        return parseInts(eval(WORK_AREA.formatted(x, y, width, height)), 4);
    }

    static void schedulePlacement(Gtk4Window window) {
        if (!pending.contains(window)) {
            pending.add(window);
        }
        if (retrySource == 0) {
            retrySource = GLib.timeoutAddFull(GLib.PRIORITY_DEFAULT, RETRY_INTERVAL, retryCallback(),
                    MemorySegment.NULL, MemorySegment.NULL);
        }
    }

    /**
     * Runs the event loop until the compositor shows the windows waiting to
     * be placed, so that input generated next reaches them.
     */
    static void awaitPlacements() {
        long deadline = System.nanoTime() + PLACEMENT_TIMEOUT;
        GMainContext context = GMainContext.default_();
        while (!pending.isEmpty() && System.nanoTime() < deadline) {
            context.iteration(true);
        }
    }

    static void cancelPlacement(Gtk4Window window) {
        pending.remove(window);
    }

    @SuppressWarnings("unused")
    private static int retry(MemorySegment data) {
        try {
            while (!pending.isEmpty() && pending.getFirst().applyTestPlacement(pending.size() == 1)) {
                pending.removeFirst();
            }
        } catch (Throwable t) {
            t.printStackTrace();
            pending.clear();
        }
        if (!pending.isEmpty()) {
            return 1;
        }
        retrySource = 0;
        return 0;
    }

    private static MemorySegment retryCallback() {
        if (retryCallback == null) {
            try {
                retryCallback = Gtk4Upcalls.stub(
                        MethodHandles.lookup().findStatic(Gtk4TestSession.class, "retry",
                                MethodType.methodType(int.class, MemorySegment.class)),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return retryCallback;
    }

    private static String eval(String script) {
        GVariant reply = connection.callSync("org.gnome.Shell", "/org/gnome/Shell", "org.gnome.Shell", "Eval",
                GVariant.createTuple(new GVariant[] {GVariant.createString(script)}), null, 0, -1, null);
        try {
            GVariant success = reply.getChildValue(0);
            GVariant result = reply.getChildValue(1);
            boolean ok = success.getBoolean();
            String value = result.getString(null);
            success.unref();
            result.unref();
            if (!ok) {
                throw new IllegalStateException("org.gnome.Shell.Eval failed: " + value);
            }
            return value;
        } finally {
            reply.unref();
        }
    }

    private static int[] parseInts(String value, int count) {
        if (value == null || !value.startsWith("[") || !value.endsWith("]")) {
            return null;
        }
        String[] parts = value.substring(1, value.length() - 1).split(",");
        if (parts.length != count) {
            return null;
        }
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }
}
