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
import com.sun.glass.ui.gtk4.wrappers.GMainContext;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The thread of the robot, where libei and the requests of the portal run,
 * as in the libei robot of jfx-tester.
 * <p>
 * The thread has a GLib main context of its own, its thread default, which
 * receives the answers of the portal. So the robot never runs the main loop
 * of the application inside its calls: no event or handler of the
 * application runs in the middle of a call of the robot, which the event
 * thread only waits for. The calls that may show a dialog of the desktop (the
 * connection and the screenshots, which ask for a permission) wait in a
 * nested event loop instead, so that the application keeps responding.
 */
final class Gtk4RobotThread {

    private static volatile Thread thread;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            // Receives the signals the robot subscribes to on this thread
            GMainContext.create().pushThreadDefault();
            r.run();
        }, "Gtk4 robot");
        t.setDaemon(true);
        thread = t;
        return t;
    });

    private Gtk4RobotThread() {
    }

    static boolean isRobotThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * The main context of the calling thread, which the robot thread
     * iterates while it waits for the portal.
     */
    static GMainContext context() {
        GMainContext context = GMainContext.getThreadDefault();
        return context == null || context.isNull() ? GMainContext.default_() : context;
    }

    /**
     * Runs a task on the robot thread and waits for it.
     */
    static <T> T call(Callable<T> task) {
        if (isRobotThread()) {
            return run(task);
        }
        Future<T> result = EXECUTOR.submit(task);
        try {
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw rethrow(e.getCause());
        }
    }

    static void execute(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Runs a task on the robot thread, which may show a dialog of the
     * desktop; the event thread waits in a nested event loop.
     */
    static <T> T callResponsive(Callable<T> task) {
        if (isRobotThread() || !Application.isEventThread()) {
            return call(task);
        }
        CompletableFuture<T> result = CompletableFuture.supplyAsync(() -> run(task), EXECUTOR);
        EventLoop loop = Application.GetApplication().createEventLoop();
        result.whenComplete((value, error) -> Application.invokeLater(() -> loop.leave(null)));
        loop.enter();
        try {
            return result.join();
        } catch (CompletionException e) {
            throw rethrow(e.getCause());
        }
    }

    private static <T> T run(Callable<T> task) {
        try {
            return task.call();
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) {
            return e;
        }
        if (t instanceof Error e) {
            throw e;
        }
        return new IllegalStateException(t);
    }
}
