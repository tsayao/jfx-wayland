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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The upcall stubs of the glass, through which GTK calls Java.
 * <p>
 * An exception that leaves an upcall stub ends the JVM. Every handler is so
 * guarded: an exception that escapes it, from the glass, from JavaFX or from
 * the application (an event handler, a listener), is reported as the GTK 3
 * glass reports the exceptions of its callbacks, through
 * {@link Application#reportException}, and the upcall returns the default
 * value of its type (0, false or NULL) to GTK.
 */
final class Gtk4Upcalls {

    private static final MethodHandle REPORT;

    static {
        try {
            REPORT = MethodHandles.lookup().findStatic(Gtk4Upcalls.class, "report",
                    MethodType.methodType(void.class, Throwable.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Gtk4Upcalls() {
    }

    /** An upcall stub of a handler, guarded against the exceptions that escape it. */
    static MemorySegment stub(MethodHandle handler, FunctionDescriptor descriptor, Arena arena) {
        return Linker.nativeLinker().upcallStub(guard(handler), descriptor, arena);
    }

    /** The handler, catching what it throws, which is reported, to return the default value. */
    static MethodHandle guard(MethodHandle handler) {
        Class<?> returnType = handler.type().returnType();
        MethodHandle fallback = returnType == void.class
                ? REPORT : MethodHandles.filterReturnValue(REPORT, MethodHandles.zero(returnType));
        MethodHandle recovery = MethodHandles.dropArguments(fallback, 1, handler.type().parameterList());
        return MethodHandles.catchException(handler, Throwable.class, recovery);
    }

    @SuppressWarnings("unused")
    private static void report(Throwable t) {
        try {
            Application.reportException(t);
        } catch (Throwable reportFailure) {
            // Even the report must not leave the upcall
            t.printStackTrace();
        }
    }
}
