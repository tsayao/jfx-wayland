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
import com.sun.glass.ui.EventLoop;
import com.sun.glass.ui.gtk4.wrappers.GDBusConnection;
import com.sun.glass.ui.gtk4.wrappers.GMainContext;
import com.sun.glass.ui.gtk4.wrappers.GVariant;
import com.sun.glass.ui.gtk4.wrappers.Gio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * A call of a method of the XDG desktop portal that answers with a Request
 * object, whose Response signal carries the result.
 * <p>
 * The Response is subscribed before the call, at the path that the token of
 * the call gives, and again at the path that the call returns when it
 * differs (older portals). The caller waits for the Response in one of three
 * ways: in a nested event loop of the application, blocking in the main
 * context of its thread, or iterating that context with a timeout. The
 * request is used and closed on one thread.
 */
final class Gtk4PortalRequest implements AutoCloseable {

    static final String PORTAL = "org.freedesktop.portal.Desktop";
    static final String PORTAL_PATH = "/org/freedesktop/portal/desktop";
    private static final String REQUEST = "org.freedesktop.portal.Request";

    static final int RESPONSE_SUCCESS = 0;
    static final int RESPONSE_CANCELLED = 1;

    private static final FunctionDescriptor RESPONSE_CALLBACK = Gtk4SignalHelper.buildDescriptor(void.class,
            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
            GVariant.class, MemorySegment.class);

    private static final AtomicInteger tokens = new AtomicInteger();

    /** The response of a request: its code, and its results, which the caller unrefs. */
    record Response(int code, GVariant results) {
        boolean succeeded() {
            return code == RESPONSE_SUCCESS;
        }

        /** The results of a successful response, or null; the others are released. */
        GVariant successfulResults() {
            if (succeeded()) {
                return results;
            }
            if (results != null) {
                results.unref();
            }
            return null;
        }
    }

    private final GDBusConnection bus = Gtk4RemoteDesktopPortal.bus();
    private final String token;
    private final String expectedPath;
    private final Arena arena = Arena.ofConfined();
    private final MemorySegment callback;
    private String path;
    private int subscription;
    private int code = -1;
    private GVariant results;
    private boolean done;
    private EventLoop loop;

    /**
     * Subscribes to the Response of the next call.
     *
     * @param prefix the prefix of the token of the call, which names the request
     */
    Gtk4PortalRequest(String prefix) {
        token = prefix + "_" + ProcessHandle.current().pid() + "_" + tokens.incrementAndGet();
        String sender = bus.getUniqueName().substring(1).replace('.', '_');
        expectedPath = PORTAL_PATH + "/request/" + sender + "/" + token;
        path = expectedPath;
        callback = responseCallback();
        subscription = subscribe(expectedPath);
    }

    /** The option that gives the token of the call, which every call needs. */
    GVariant tokenOption() {
        return Gtk4RemoteDesktopPortal.entry("handle_token", GVariant.createString(token));
    }

    /**
     * Calls a method of an interface of the portal, whose reply is the path of
     * the Request.
     */
    void call(String iface, String method, GVariant args) {
        GVariant reply = bus.callSync(PORTAL, PORTAL_PATH, iface, method, args, null,
                Gio.G_DBUS_CALL_FLAGS_NONE, -1, null);
        GVariant handle = reply.getChildValue(0);
        path = handle.getString(null);
        handle.unref();
        reply.unref();
        if (!expectedPath.equals(path)) {
            bus.signalUnsubscribe(subscription);
            subscription = subscribe(path);
        }
    }

    /** Waits for the response in a nested event loop of the application, on the event thread. */
    Response awaitInEventLoop() {
        if (!done) {
            loop = Application.GetApplication().createEventLoop();
            loop.enter();
        }
        return response();
    }

    /**
     * Waits for the response in the main context of the calling thread, as
     * long as it takes: the user may take a while to answer a dialog.
     */
    Response awaitBlocking() {
        GMainContext context = Gtk4RobotThread.context();
        while (!done) {
            context.iteration(true);
        }
        return response();
    }

    /**
     * Iterates the main context of the calling thread until the response
     * arrives, calling tick with the milliseconds since the start meanwhile.
     *
     * @return the response, or null when the time is over, after closing the request
     */
    Response awaitIterating(long timeoutMs, LongConsumer tick) {
        GMainContext context = Gtk4RobotThread.context();
        long start = System.nanoTime();
        long deadline = start + timeoutMs * 1_000_000L;
        while (!done && System.nanoTime() <= deadline) {
            if (!context.iteration(false)) {
                Gtk4EiInput.sleep(1);
            }
            if (tick != null && !done) {
                tick.accept((System.nanoTime() - start) / 1_000_000L);
            }
        }
        if (!done) {
            cancel();
            return null;
        }
        return response();
    }

    private Response response() {
        return new Response(code, results);
    }

    /** Whether the response arrived. */
    boolean isDone() {
        return done;
    }

    /** Ends the request, and what the desktop shows for it, such as a picker. */
    void cancel() {
        try {
            bus.callSync(PORTAL, path, REQUEST, "Close", GVariant.createTuple(new GVariant[0]), null,
                    Gio.G_DBUS_CALL_FLAGS_NONE, -1, null).unref();
        } catch (RuntimeException e) {
            Gtk4Log.log(Gtk4Log.Category.FOCUS, "cannot close the portal request %s: %s", path, e);
        }
    }

    @Override
    public void close() {
        bus.signalUnsubscribe(subscription);
        arena.close();
    }

    private int subscribe(String requestPath) {
        return bus.signalSubscribe(PORTAL, REQUEST, "Response", requestPath, null,
                Gio.G_DBUS_SIGNAL_FLAGS_NO_MATCH_RULE, callback, null, null);
    }

    private MemorySegment responseCallback() {
        try {
            return Gtk4SignalHelper.upcall(MethodHandles.lookup(),
                    MethodHandles.lookup().bind(this, "onResponse", MethodType.methodType(void.class,
                            MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class, GVariant.class, MemorySegment.class)),
                    RESPONSE_CALLBACK, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private void onResponse(MemorySegment connection, MemorySegment sender, MemorySegment objectPath,
                            MemorySegment iface, MemorySegment signal, GVariant parameters, MemorySegment data) {
        if (done) {
            return;
        }
        GVariant value = parameters.getChildValue(0);
        code = value.getUint32();
        value.unref();
        results = parameters.getChildValue(1);
        done = true;
        if (loop != null) {
            loop.leave(null);
        }
    }
}
