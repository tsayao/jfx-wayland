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

import com.sun.glass.ui.Screen;
import com.sun.glass.ui.Timer;
import com.sun.glass.ui.gtk4.wrappers.GLib;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

final class Gtk4Timer extends Timer {

    private int sourceId;
    private Arena callbackArena;

    Gtk4Timer(Runnable runnable) {
        super(runnable);
    }

    @SuppressWarnings("unused")
    private static int timerTick(Object runnable, MemorySegment data) {
        try {
            ((Runnable) runnable).run();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return 1; // G_SOURCE_CONTINUE
    }

    @Override
    protected long _start(Runnable runnable) {
        double period = Screen.getVideoRefreshPeriod();
        if (period <= 0.0) {
            period = 16.0;
        }
        return _start(runnable, Math.max(1, (int) Math.round(period)));
    }

    @Override
    protected long _start(Runnable runnable, int period) {
        callbackArena = Arena.ofShared();

        try {
            MethodHandle timerCallback = MethodHandles.lookup()
                    .findStatic(Gtk4Timer.class, "timerTick",
                            MethodType.methodType(int.class, Object.class, MemorySegment.class));

            MemorySegment stub = Gtk4Upcalls.stub(
                    timerCallback.bindTo(runnable),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    callbackArena);

            sourceId = GLib.timeoutAddFull(
                    GLib.PRIORITY_HIGH_IDLE, period, stub, MemorySegment.NULL, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to start GTK4 timer", t);
        }

        return sourceId;
    }

    @Override
    protected void _stop(long timerId) {
        if (sourceId != 0) {
            GLib.sourceRemove(sourceId);
            sourceId = 0;
        }
        if (callbackArena != null) {
            Gtk4Application.closeLater(callbackArena);
            callbackArena = null;
        }
    }

    @Override
    protected void _pause(long timerId) {
        // Pause not supported — stop and restart
    }

    @Override
    protected void _resume(long timerId) {
        // Resume not supported — stop and restart
    }
}

