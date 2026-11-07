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
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.GUnixFDList;
import com.sun.glass.ui.gtk4.wrappers.GVariant;
import com.sun.glass.ui.gtk4.wrappers.GVariantType;
import com.sun.glass.ui.gtk4.wrappers.Gio;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The input of the robot in any session, through the RemoteDesktop interface
 * of the XDG desktop portal: a remote desktop session with a keyboard and a
 * pointer, connected to the EIS server of the compositor for libei.
 * <p>
 * The desktop asks the user to allow it once: the session is persistent, and
 * its restore token is kept in {@code $XDG_STATE_HOME/jfx-wayland/remote-desktop}
 * (or {@code ~/.local/state}), so that the next sessions start without asking.
 */
final class Gtk4RemoteDesktopPortal {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.FOCUS;

    static final String PORTAL = "org.freedesktop.portal.Desktop";
    static final String PORTAL_PATH = "/org/freedesktop/portal/desktop";
    private static final String REMOTE_DESKTOP = "org.freedesktop.portal.RemoteDesktop";
    static final String REQUEST = "org.freedesktop.portal.Request";

    private static final int RESPONSE_SUCCESS = 0;
    private static final int RESPONSE_CANCELLED = 1;
    private static final int DEVICE_KEYBOARD = 1;
    private static final int DEVICE_POINTER = 2;
    private static final int DEVICE_TOUCHSCREEN = 4;
    // Kept until the user revokes the permission
    private static final int PERSIST_PERMANENT = 2;

    static final FunctionDescriptor RESPONSE_CALLBACK = Gtk4SignalHelper.buildDescriptor(void.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            GVariant.class, MemorySegment.class);

    private static final AtomicInteger tokens = new AtomicInteger();

    private static GDBusConnection bus;
    // The session lives as long as the application: closing it ends the input
    private static String session;

    private Gtk4RemoteDesktopPortal() {
    }

    /**
     * Starts a remote desktop session and connects it to EIS.
     *
     * @return the file descriptor of the EIS connection
     * @throws UnsupportedOperationException when the portal has no EIS or the
     *         user does not allow the session
     */
    static int connectToEis() {
        bus();
        if (portalVersion(REMOTE_DESKTOP) < 2) {
            throw new UnsupportedOperationException(
                    "The robot needs the RemoteDesktop portal, version 2 (ConnectToEIS)");
        }
        if (session != null) {
            try {
                return connect(session);
            } catch (RuntimeException e) {
                // The compositor ended the session, as when it disconnected the robot
                Gtk4Log.log(LOG, "remote desktop session %s ended: %s", session, e);
                session = null;
            }
        }
        session = start();
        return connect(session);
    }

    private static int connect(String session) {
        GVariant options = array("{sv}", new GVariant[0]);
        GUnixFDList[] fds = new GUnixFDList[1];
        GVariant reply = bus.callWithUnixFdListSync(PORTAL, PORTAL_PATH, REMOTE_DESKTOP, "ConnectToEIS",
                GVariant.createTuple(new GVariant[] {GVariant.createObjectPath(session), options}),
                null, Gio.G_DBUS_CALL_FLAGS_NONE, -1, null, fds, null);
        try {
            GVariant handle = reply.getChildValue(0);
            int index = handle.getHandle();
            handle.unref();
            if (fds[0] == null || fds[0].isNull()) {
                throw new IllegalStateException("ConnectToEIS returned no file descriptor");
            }
            int fd = fds[0].get(index);
            fds[0].unref();
            Gtk4Log.log(LOG, "remote desktop portal session %s connected to EIS", session);
            return fd;
        } finally {
            reply.unref();
        }
    }

    /**
     * The session bus, shared by the clients of the portal.
     */
    static GDBusConnection bus() {
        if (bus == null) {
            bus = Gio.busGetSync(Gio.G_BUS_TYPE_SESSION, null);
        }
        return bus;
    }

    /**
     * The version of an interface of the portal, or 0 when it has none.
     */
    static int portalVersion(String iface) {
        try {
            GVariant reply = bus().callSync(PORTAL, PORTAL_PATH, "org.freedesktop.DBus.Properties", "Get",
                    GVariant.createTuple(new GVariant[] {GVariant.createString(iface),
                        GVariant.createString("version")}), null, Gio.G_DBUS_CALL_FLAGS_NONE, -1, null);
            GVariant boxed = reply.getChildValue(0);
            GVariant version = boxed.getVariant();
            int v = version.getUint32();
            version.unref();
            boxed.unref();
            reply.unref();
            return v;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String start() {
        String sessionToken = "jfx_session_" + ProcessHandle.current().pid();
        GVariant created = request("CreateSession", null, null,
                entry("session_handle_token", GVariant.createString(sessionToken)));
        String handle = stringOf(created, "session_handle");
        created.unref();
        if (handle == null) {
            throw new IllegalStateException("CreateSession returned no session");
        }

        List<GVariant> select = new ArrayList<>();
        select.add(entry("types", GVariant.createUint32(DEVICE_KEYBOARD | DEVICE_POINTER | DEVICE_TOUCHSCREEN)));
        select.add(entry("persist_mode", GVariant.createUint32(PERSIST_PERMANENT)));
        String restore = readRestoreToken();
        if (restore != null) {
            select.add(entry("restore_token", GVariant.createString(restore)));
        }
        request("SelectDevices", handle, null, select.toArray(new GVariant[0])).unref();

        // The desktop asks the user, unless the restore token is still valid
        GVariant started = request("Start", handle, "", new GVariant[0]);
        String token = stringOf(started, "restore_token");
        started.unref();
        if (token != null) {
            writeRestoreToken(token);
        }
        return handle;
    }

    private static String stringOf(GVariant results, String key) {
        GVariantType type = GVariantType.create("s");
        GVariant value = results.lookupValue(key, type);
        type.free();
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.getString(null);
        value.unref();
        return s;
    }

    private static Path tokenFile() {
        String state = System.getenv("XDG_STATE_HOME");
        Path base = state != null && !state.isEmpty() ? Path.of(state)
                : Path.of(System.getProperty("user.home"), ".local", "state");
        return base.resolve("jfx-wayland").resolve("remote-desktop");
    }

    private static String readRestoreToken() {
        try {
            String token = Files.readString(tokenFile(), StandardCharsets.UTF_8).trim();
            return token.isEmpty() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeRestoreToken(String token) {
        try {
            Path file = tokenFile();
            Files.createDirectories(file.getParent());
            Files.writeString(file, token, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Gtk4Log.log(LOG, "cannot keep the remote desktop restore token: %s", e);
        }
    }

    /**
     * Calls a method of the portal that answers with a Request, and waits for
     * its response, iterating the main context of the calling thread (that of
     * the robot), where the response is subscribed.
     *
     * @param session the session handle, the first argument of the method, or null
     * @param parent the parent window, the second argument of the method, or null
     * @return the results of the response
     */
    private static GVariant request(String method, String session, String parent, GVariant... options) {
        String token = "jfx_" + ProcessHandle.current().pid() + "_" + tokens.incrementAndGet();
        String sender = bus.getUniqueName().substring(1).replace('.', '_');
        String expected = PORTAL_PATH + "/request/" + sender + "/" + token;
        List<GVariant> all = new ArrayList<>(List.of(options));
        all.add(entry("handle_token", GVariant.createString(token)));

        List<GVariant> args = new ArrayList<>();
        if (session != null) {
            args.add(GVariant.createObjectPath(session));
        }
        if (parent != null) {
            args.add(GVariant.createString(parent));
        }
        args.add(array("{sv}", all.toArray(new GVariant[0])));

        try (Arena arena = Arena.ofConfined()) {
            Waiter waiter = new Waiter();
            MemorySegment callback = Gtk4SignalHelper.upcall(MethodHandles.lookup(),
                    MethodHandles.lookup().bind(waiter, "response", MethodType.methodType(void.class,
                            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class, GVariant.class, MemorySegment.class)),
                    RESPONSE_CALLBACK, arena);
            int subscription = subscribe(expected, callback);
            try {
                GVariant reply = bus.callSync(PORTAL, PORTAL_PATH, REMOTE_DESKTOP, method,
                        GVariant.createTuple(args.toArray(new GVariant[0])), null,
                        Gio.G_DBUS_CALL_FLAGS_NONE, -1, null);
                GVariant handle = reply.getChildValue(0);
                String path = handle.getString(null);
                handle.unref();
                reply.unref();
                if (!expected.equals(path)) {
                    bus.signalUnsubscribe(subscription);
                    subscription = subscribe(path, callback);
                }
                waiter.await();
            } finally {
                bus.signalUnsubscribe(subscription);
            }
            if (waiter.code != RESPONSE_SUCCESS) {
                if (waiter.results != null) {
                    waiter.results.unref();
                }
                String message = "The remote desktop portal refused " + method + " (response " + waiter.code
                        + "): the robot needs the permission to control the desktop";
                // The user said no: the robot does not ask again. Otherwise,
                // as when the dialog went away, it asks on its next call
                throw waiter.code == RESPONSE_CANCELLED
                        ? new UnsupportedOperationException(message) : new IllegalStateException(message);
            }
            return waiter.results;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int subscribe(String path, MemorySegment callback) {
        return bus.signalSubscribe(PORTAL, REQUEST, "Response", path, null,
                Gio.G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE, callback, null, null);
    }

    static GVariant entry(String key, GVariant value) {
        return GVariant.createDictEntry(GVariant.createString(key), GVariant.createVariant(value));
    }

    static GVariant array(String elementType, GVariant[] children) {
        GVariantType type = GVariantType.create(elementType);
        try {
            return GVariant.createArray(type, children);
        } finally {
            type.free();
        }
    }

    private static final class Waiter {
        private int code = -1;
        private GVariant results;
        private boolean done;

        void await() {
            GMainContext context = Gtk4RobotThread.context();
            while (!done) {
                // The user may take a while to answer the dialog of the desktop
                context.iteration(true);
            }
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
