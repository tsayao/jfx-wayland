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

import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GSignal;
import com.sun.glass.ui.gtk4.wrappers.GtkEventController;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Gtk4SignalHelper {

    private final Arena arena;
    private final MethodHandles.Lookup lookup;
    private final List<SignalConnection> signalConnections = new ArrayList<>();
    private final List<ControllerEntry> installedControllers = new ArrayList<>();

    Gtk4SignalHelper(Arena arena, MethodHandles.Lookup lookup) {
        this.arena = arena;
        this.lookup = lookup;
    }

    MethodHandle typedHandle(Object receiver, String name, Class<?> returnType, Class<?>... paramTypes) {
        try {
            return lookup.bind(receiver, name, MethodType.methodType(returnType, paramTypes));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot find method " + name, e);
        }
    }

    /**
     * Connects {@code receiver.methodName} to a signal. The native signature is
     * derived from the Java parameter types: wrapper types are passed as pointers
     * and converted back to wrappers, and trailing native arguments that the
     * method does not declare (such as {@code user_data}) are dropped.
     */
    void connect(GObject source, String signal, Object receiver, String methodName,
                 Class<?> returnType, Class<?>... paramTypes) {
        MethodHandle mh = typedHandle(receiver, methodName, returnType, paramTypes);
        connectTyped(source, signal, mh, buildDescriptor(returnType, paramTypes));
    }

    void connectTyped(GObject instance, String signal,
                      MethodHandle typedHandle,
                      FunctionDescriptor descriptor) {
        MemorySegment cb = upcall(lookup, typedHandle, descriptor, arena);
        long handlerId = GSignal.connect(instance, signal, cb);
        // Keep the instance alive until the handler is disconnected
        instance.ref();
        signalConnections.add(new SignalConnection(instance, handlerId));
    }

    /**
     * Creates an upcall stub for {@code typedHandle}, adapting wrapper-typed
     * parameters from native pointers.
     */
    static MemorySegment upcall(MethodHandles.Lookup lookup, MethodHandle typedHandle,
                                FunctionDescriptor descriptor, Arena arena) {
        MethodType typedType = typedHandle.type();
        List<MemoryLayout> nativeLayouts = descriptor.argumentLayouts();
        int nTyped = typedType.parameterCount();
        int nNative = nativeLayouts.size();

        MethodHandle mh = typedHandle;
        if (nNative > nTyped) {
            Class<?>[] extras = new Class<?>[nNative - nTyped];
            Arrays.fill(extras, MemorySegment.class);
            mh = MethodHandles.dropArguments(mh, nTyped, extras);
        }

        MethodHandle[] filters = new MethodHandle[nNative];
        boolean hasFilters = false;
        for (int i = 0; i < Math.min(nTyped, nNative); i++) {
            Class<?> paramType = typedType.parameterType(i);
            if (paramType == MemorySegment.class || paramType.isPrimitive()) {
                continue;
            }
            filters[i] = wrapperFactory(lookup, paramType);
            hasFilters = true;
        }
        if (hasFilters) {
            mh = MethodHandles.filterArguments(mh, 0, filters);
        }

        return Gtk4Upcalls.stub(mh, descriptor, arena);
    }

    private static MethodHandle wrapperFactory(MethodHandles.Lookup lookup, Class<?> type) {
        try {
            if (type == String.class) {
                return MethodHandles.lookup().findStatic(Gtk4SignalHelper.class, "toJavaString",
                        MethodType.methodType(String.class, MemorySegment.class));
            }
            if (type.isInterface()) {
                return lookup.findStatic(type, "wrap", MethodType.methodType(type, MemorySegment.class));
            }
            return lookup.findConstructor(type, MethodType.methodType(void.class, MemorySegment.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot wrap native pointers as " + type.getName(), e);
        }
    }

    /**
     * Converts a {@code const char*} signal argument.
     */
    private static String toJavaString(MemorySegment str) {
        return str.address() == 0 ? null : str.reinterpret(Long.MAX_VALUE).getString(0);
    }

    void addController(GtkWidget widget, GtkEventController controller) {
        // gtk_widget_add_controller takes ownership; keep a reference so the
        // controller can still be removed after the widget released it
        controller.ref();
        widget.addController(controller);
        installedControllers.add(new ControllerEntry(widget, controller));
    }

    /**
     * Removes a controller installed through this helper.
     */
    void removeController(GtkEventController controller) {
        // Its handlers keep a reference to it, and to their receiver
        disconnectSignals(controller);
        for (var it = installedControllers.iterator(); it.hasNext(); ) {
            ControllerEntry ce = it.next();
            if (ce.controller().equals(controller)) {
                it.remove();
                release(ce);
            }
        }
    }

    private static void release(ControllerEntry ce) {
        GtkWidget widget = ce.controller().getWidget();
        if (!widget.isNull()) {
            widget.removeController(ce.controller());
        }
        ce.controller().unref();
    }

    /** Disconnects the handlers connected to the signals of an instance. */
    void disconnectSignals(GObject instance) {
        for (var it = signalConnections.iterator(); it.hasNext(); ) {
            SignalConnection sc = it.next();
            if (sc.instance().equals(instance)) {
                it.remove();
                GSignal.disconnect(sc.instance(), sc.handlerId());
                sc.instance().unref();
            }
        }
    }

    void disconnectAllSignals() {
        for (SignalConnection sc : signalConnections) {
            GSignal.disconnect(sc.instance(), sc.handlerId());
            sc.instance().unref();
        }
        signalConnections.clear();
    }

    void removeAllControllers() {
        for (ControllerEntry ce : installedControllers) {
            release(ce);
        }
        installedControllers.clear();
    }

    private record SignalConnection(GObject instance, long handlerId) {}

    private record ControllerEntry(GtkWidget widget, GtkEventController controller) {}

    static FunctionDescriptor buildDescriptor(Class<?> returnType, Class<?>... paramTypes) {
        MemoryLayout[] argLayouts = new MemoryLayout[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            argLayouts[i] = layoutFor(paramTypes[i]);
        }
        if (returnType == void.class) {
            return FunctionDescriptor.ofVoid(argLayouts);
        }
        return FunctionDescriptor.of(layoutFor(returnType), argLayouts);
    }

    private static MemoryLayout layoutFor(Class<?> type) {
        if (type == int.class) return ValueLayout.JAVA_INT;
        if (type == long.class) return ValueLayout.JAVA_LONG;
        if (type == double.class) return ValueLayout.JAVA_DOUBLE;
        if (type == float.class) return ValueLayout.JAVA_FLOAT;
        if (type == boolean.class) return ValueLayout.JAVA_INT;
        return ValueLayout.ADDRESS;
    }
}
