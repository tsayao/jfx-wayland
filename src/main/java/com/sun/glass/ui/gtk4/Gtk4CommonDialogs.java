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
import com.sun.glass.ui.CommonDialogs.ExtensionFilter;
import com.sun.glass.ui.CommonDialogs.FileChooserResult;
import com.sun.glass.ui.CommonDialogs.Type;
import com.sun.glass.ui.EventLoop;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk4.wrappers.GErrorException;
import com.sun.glass.ui.gtk4.wrappers.GFile;
import com.sun.glass.ui.gtk4.wrappers.GListModel;
import com.sun.glass.ui.gtk4.wrappers.GListStore;
import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GObjectFunctions;
import com.sun.glass.ui.gtk4.wrappers.GVariant;
import com.sun.glass.ui.gtk4.wrappers.GVariantType;
import com.sun.glass.ui.gtk4.wrappers.GdkSurface;
import com.sun.glass.ui.gtk4.wrappers.GdkWaylandToplevel;
import com.sun.glass.ui.gtk4.wrappers.GtkFileDialog;
import com.sun.glass.ui.gtk4.wrappers.GtkFileFilter;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;
import com.sun.glass.ui.gtk4.wrappers.GtkWindow;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * File and folder choosers. They use the FileChooser interface of the XDG
 * desktop portal, so that the dialog of the desktop is shown, and fall back to
 * {@code GtkFileDialog} when no portal is running.
 */
final class Gtk4CommonDialogs {

    private static final String FILE_CHOOSER = "org.freedesktop.portal.FileChooser";
    private static final int GLOB = 0;
    private static final boolean USE_PORTAL = !"false".equals(System.getProperty("glass.gtk4.portal"));

    private static final FunctionDescriptor EXPORTED_CALLBACK =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);

    private static int portalVersion = -1;

    private Gtk4CommonDialogs() {
    }

    static FileChooserResult showFileChooser(Window owner, String folder, String filename, String title,
                                             int type, boolean multipleMode,
                                             ExtensionFilter[] extensionFilters, int defaultFilterIndex) {
        if (portalVersion() > 0) {
            try {
                return portalFileChooser(owner, folder, filename, title, type, multipleMode,
                        extensionFilters, defaultFilterIndex);
            } catch (RuntimeException e) {
                System.err.println("Gtk4CommonDialogs: file chooser portal failed: " + e.getMessage());
            }
        }
        return gtkFileChooser(owner, folder, filename, title, type, multipleMode, extensionFilters,
                defaultFilterIndex);
    }

    static File showFolderChooser(Window owner, String folder, String title) {
        if (portalVersion() >= 3) {
            try {
                return portalFolderChooser(owner, folder, title);
            } catch (RuntimeException e) {
                System.err.println("Gtk4CommonDialogs: file chooser portal failed: " + e.getMessage());
            }
        }
        return gtkFolderChooser(owner, folder, title);
    }

    /** The version of the FileChooser portal, or 0 when there is none or it is not to be used. */
    private static int portalVersion() {
        if (portalVersion < 0) {
            portalVersion = USE_PORTAL ? Gtk4RemoteDesktopPortal.portalVersion(FILE_CHOOSER) : 0;
        }
        return portalVersion;
    }

    private static FileChooserResult portalFileChooser(Window owner, String folder, String filename, String title,
                                                       int type, boolean multipleMode,
                                                       ExtensionFilter[] extensionFilters, int defaultFilterIndex) {
        boolean save = type == Type.SAVE;
        List<GVariant> options = new ArrayList<>();
        options.add(entry("modal", GVariant.createBoolean_(true)));
        if (!save && multipleMode) {
            options.add(entry("multiple", GVariant.createBoolean_(true)));
        }
        if (save && filename != null && !filename.isEmpty()) {
            options.add(entry("current_name", GVariant.createString(filename)));
        }
        if (extensionFilters != null && extensionFilters.length > 0) {
            addFilterOptions(options, extensionFilters, defaultFilterIndex);
        }
        GVariant results = request(save ? "SaveFile" : "OpenFile", owner, title, folder, options);
        List<File> files = new ArrayList<>();
        ExtensionFilter selected = null;
        if (results != null) {
            try {
                addUris(files, results);
                selected = selectedFilter(currentFilterName(results), extensionFilters);
            } finally {
                results.unref();
            }
        }
        return new FileChooserResult(files, selected);
    }

    /** The filters of the dialog, and the one it starts with. */
    private static void addFilterOptions(List<GVariant> options, ExtensionFilter[] extensionFilters,
                                         int defaultFilterIndex) {
        GVariant[] filters = new GVariant[extensionFilters.length];
        for (int i = 0; i < filters.length; i++) {
            filters[i] = filter(extensionFilters[i]);
        }
        options.add(entry("filters", array("(sa(us))", filters)));
        if (defaultFilterIndex >= 0 && defaultFilterIndex < extensionFilters.length) {
            options.add(entry("current_filter", filter(extensionFilters[defaultFilterIndex])));
        }
    }

    /** The extension filter of a name, as the portal answers it, or null. */
    private static ExtensionFilter selectedFilter(String name, ExtensionFilter[] extensionFilters) {
        if (name == null || extensionFilters == null) {
            return null;
        }
        for (ExtensionFilter f : extensionFilters) {
            if (name.equals(f.getDescription())) {
                return f;
            }
        }
        return null;
    }

    private static File portalFolderChooser(Window owner, String folder, String title) {
        List<GVariant> options = new ArrayList<>();
        options.add(entry("modal", GVariant.createBoolean_(true)));
        options.add(entry("directory", GVariant.createBoolean_(true)));
        GVariant results = request("OpenFile", owner, title, folder, options);
        if (results == null) {
            return null;
        }
        try {
            List<File> files = new ArrayList<>();
            addUris(files, results);
            return files.isEmpty() ? null : files.getFirst();
        } finally {
            results.unref();
        }
    }

    private static GVariant entry(String key, GVariant value) {
        return GVariant.createDictEntry(GVariant.createString(key), GVariant.createVariant(value));
    }

    private static GVariant array(String elementType, GVariant[] children) {
        GVariantType type = GVariantType.create(elementType);
        try {
            return GVariant.createArray(type, children);
        } finally {
            type.free();
        }
    }

    private static GVariant filter(ExtensionFilter extensionFilter) {
        List<String> extensions = extensionFilter.getExtensions();
        GVariant[] patterns = new GVariant[extensions.size()];
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = GVariant.createTuple(new GVariant[] {
                GVariant.createUint32(GLOB), GVariant.createString(extensions.get(i))
            });
        }
        return GVariant.createTuple(new GVariant[] {
            GVariant.createString(extensionFilter.getDescription()), array("(us)", patterns)
        });
    }

    private static void addUris(List<File> files, GVariant results) {
        GVariantType type = GVariantType.create("as");
        GVariant uris = results.lookupValue("uris", type);
        type.free();
        if (uris == null || uris.isNull()) {
            return;
        }
        try {
            for (String uri : uris.getStrv(null)) {
                try {
                    URI parsed = URI.create(uri);
                    if ("file".equals(parsed.getScheme())) {
                        files.add(new File(parsed));
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Gtk4CommonDialogs: ignoring " + uri + ": " + e.getMessage());
                }
            }
        } finally {
            uris.unref();
        }
    }

    private static String currentFilterName(GVariant results) {
        GVariantType type = GVariantType.create("(sa(us))");
        GVariant filter = results.lookupValue("current_filter", type);
        type.free();
        if (filter == null || filter.isNull()) {
            return null;
        }
        try {
            GVariant name = filter.getChildValue(0);
            String value = name.getString(null);
            name.unref();
            return value;
        } finally {
            filter.unref();
        }
    }

    /**
     * Calls a method of the FileChooser portal, with the window of the owner
     * as the parent of the dialog, and waits for the answer in a nested event
     * loop.
     *
     * @return the results, or null when the user cancelled the dialog
     */
    private static GVariant request(String method, Window owner, String title, String folder,
                                    List<GVariant> options) {
        try (var request = new Gtk4PortalRequest("jfx"); Arena arena = Arena.ofConfined()) {
            options.add(request.tokenOption());
            if (folder != null && !folder.isEmpty()) {
                options.add(entry("current_folder", GVariant.createBytestring(arena.allocateFrom(folder))));
            }
            Exported parent = exportParent(owner, arena);
            Gtk4PortalRequest.Response response;
            try {
                request.call(FILE_CHOOSER, method, GVariant.createTuple(new GVariant[] {
                    GVariant.createString(parent.identifier()),
                    GVariant.createString(title != null ? title : ""),
                    array("{sv}", options.toArray(new GVariant[0]))
                }));
                response = request.awaitInEventLoop();
            } finally {
                parent.drop();
            }
            GVariant results = response.successfulResults();
            if (results == null && response.code() != Gtk4PortalRequest.RESPONSE_CANCELLED) {
                throw new IllegalStateException(method + " returned " + response.code());
            }
            return results;
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits for the handle of an exported toplevel, in a nested event loop. */
    private static final class ExportWaiter {
        private String exportedHandle;
        private boolean done;
        private EventLoop loop;

        void await() {
            if (!done) {
                loop = Application.GetApplication().createEventLoop();
                loop.enter();
            }
        }

        @SuppressWarnings("unused")
        void exported(MemorySegment toplevel, MemorySegment handle, MemorySegment data) {
            if (handle.address() != 0) {
                exportedHandle = handle.reinterpret(Long.MAX_VALUE).getString(0);
            }
            done = true;
            if (loop != null) {
                loop.leave(null);
            }
        }
    }

    private record Exported(GdkWaylandToplevel toplevel, String handle) {
        String identifier() {
            return handle != null ? "wayland:" + handle : "";
        }

        void drop() {
            if (handle != null) {
                toplevel.dropExportedHandle(handle);
            }
        }
    }

    private static Exported exportParent(Window owner, Arena arena)
            throws NoSuchMethodException, IllegalAccessException {
        if (!(owner instanceof Gtk4Window window) || !GdkWaylandToplevel.isAvailable()) {
            return new Exported(null, null);
        }
        GdkSurface surface = window.getSurface();
        if (surface == null || surface.isNull() || !GObjectFunctions.typeIsA(typeOf(surface.getPointer()),
                GdkWaylandToplevel.getGType())) {
            return new Exported(null, null);
        }
        GdkWaylandToplevel toplevel = surface.cast(GdkWaylandToplevel::new);
        ExportWaiter waiter = new ExportWaiter();
        MemorySegment callback = Gtk4SignalHelper.upcall(MethodHandles.lookup(),
                MethodHandles.lookup().bind(waiter, "exported", MethodType.methodType(void.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class)),
                EXPORTED_CALLBACK, arena);
        if (!toplevel.exportHandle(callback, null, null)) {
            return new Exported(null, null);
        }
        waiter.await();
        return new Exported(toplevel, waiter.exportedHandle);
    }

    private static long typeOf(MemorySegment instance) {
        MemorySegment klass = instance.reinterpret(ValueLayout.ADDRESS.byteSize()).get(ValueLayout.ADDRESS, 0);
        return klass.reinterpret(Long.BYTES).get(ValueLayout.JAVA_LONG, 0);
    }

    private static FileChooserResult gtkFileChooser(Window owner, String folder, String filename, String title,
                                                    int type, boolean multipleMode,
                                                    ExtensionFilter[] extensionFilters, int defaultFilterIndex) {
        GtkFileDialog dialog = GtkFileDialog.create();
        List<GtkFileFilter> filters = List.of();
        try {
            configure(dialog, title, folder);
            if (type == Type.SAVE && filename != null && !filename.isEmpty()) {
                dialog.setInitialName(filename);
            }
            if (extensionFilters != null && extensionFilters.length > 0) {
                filters = setFilters(dialog, extensionFilters, defaultFilterIndex);
            }
            List<File> files = chooseFiles(dialog, parentOf(owner), type, multipleMode);
            return new FileChooserResult(files, selectedFilter(dialog, filters, extensionFilters));
        } finally {
            filters.forEach(GtkFileFilter::unref);
            dialog.unref();
        }
    }

    /** A filter of the dialog for each extension filter; returns them, to be unreffed. */
    private static List<GtkFileFilter> setFilters(GtkFileDialog dialog, ExtensionFilter[] extensionFilters,
                                                  int defaultFilterIndex) {
        List<GtkFileFilter> filters = new ArrayList<>();
        GListStore store = GListStore.create(GtkFileFilter.getGType());
        for (ExtensionFilter extensionFilter : extensionFilters) {
            GtkFileFilter filter = GtkFileFilter.create();
            filter.setName(extensionFilter.getDescription());
            extensionFilter.getExtensions().forEach(filter::addPattern);
            store.append(filter);
            filters.add(filter);
        }
        dialog.setFilters(store);
        if (defaultFilterIndex >= 0 && defaultFilterIndex < filters.size()) {
            dialog.setDefaultFilter(filters.get(defaultFilterIndex));
        }
        store.unref();
        return filters;
    }

    /** Shows the dialog and waits for the files chosen; none when it is cancelled. */
    private static List<File> chooseFiles(GtkFileDialog dialog, GtkWindow parent, int type, boolean multipleMode) {
        List<File> files = new ArrayList<>();
        try {
            if (type == Type.SAVE) {
                addFile(files, Gtk4Async.await(cb -> dialog.save(parent, null, cb, null), dialog::saveFinish));
            } else if (multipleMode) {
                addFiles(files, Gtk4Async.await(cb -> dialog.openMultiple(parent, null, cb, null),
                        dialog::openMultipleFinish));
            } else {
                addFile(files, Gtk4Async.await(cb -> dialog.open(parent, null, cb, null), dialog::openFinish));
            }
        } catch (GErrorException e) {
            if (!e.isCancelled()) {
                System.err.println("Gtk4CommonDialogs: " + e.getMessage());
            }
        }
        return files;
    }

    /** The extension filter of the filter the dialog ended with, or null. */
    private static ExtensionFilter selectedFilter(GtkFileDialog dialog, List<GtkFileFilter> filters,
                                                  ExtensionFilter[] extensionFilters) {
        int index = filters.indexOf(dialog.getDefaultFilter());
        return index >= 0 ? extensionFilters[index] : null;
    }

    private static File gtkFolderChooser(Window owner, String folder, String title) {
        GtkFileDialog dialog = GtkFileDialog.create();
        try {
            configure(dialog, title, folder);
            GtkWindow parent = parentOf(owner);
            GFile file = Gtk4Async.await(cb -> dialog.selectFolder(parent, null, cb, null),
                    dialog::selectFolderFinish);
            List<File> files = new ArrayList<>();
            addFile(files, file);
            return files.isEmpty() ? null : files.getFirst();
        } catch (GErrorException e) {
            if (!e.isCancelled()) {
                System.err.println("Gtk4CommonDialogs: " + e.getMessage());
            }
            return null;
        } finally {
            dialog.unref();
        }
    }

    private static void configure(GtkFileDialog dialog, String title, String folder) {
        dialog.setModal(true);
        if (title != null) {
            dialog.setTitle(title);
        }
        if (folder != null && !folder.isEmpty()) {
            GFile initialFolder = GFile.newForPath(folder);
            dialog.setInitialFolder(initialFolder);
            initialFolder.cast(GObject::new).unref();
        }
    }

    private static GtkWindow parentOf(Window owner) {
        if (owner instanceof Gtk4Window window) {
            GtkWidget keyWidget = window.getKeyWidget();
            return keyWidget != null ? keyWidget.cast(GtkWindow::new) : null;
        }
        return null;
    }

    private static void addFile(List<File> files, GFile file) {
        if (file == null || file.isNull()) {
            return;
        }
        String path = file.getPath();
        if (path != null && !path.isEmpty()) {
            files.add(new File(path));
        }
        file.cast(GObject::new).unref();
    }

    private static void addFiles(List<File> files, GListModel list) {
        if (list == null || list.isNull()) {
            return;
        }
        int count = list.getNItems();
        for (int i = 0; i < count; i++) {
            addFile(files, list.getObject(i).cast(GFile::wrap));
        }
        list.cast(GObject::new).unref();
    }
}
