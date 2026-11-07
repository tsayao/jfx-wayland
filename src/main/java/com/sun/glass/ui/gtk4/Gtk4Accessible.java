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

import com.sun.glass.ui.Accessible;
import com.sun.glass.ui.View;
import com.sun.glass.ui.gtk4.wrappers.GBytes;
import com.sun.glass.ui.gtk4.wrappers.GLib;
import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GObjectClass;
import com.sun.glass.ui.gtk4.wrappers.GObjectFunctions;
import com.sun.glass.ui.gtk4.wrappers.GSubclass;
import com.sun.glass.ui.gtk4.wrappers.GValue;
import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkATContext;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessible;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleInterface;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleRange;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleRangeInterface;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleText;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleTextInterface;
import com.sun.glass.ui.gtk4.wrappers.GtkAccessibleTextRange;
import com.sun.glass.ui.gtk4.wrappers.GtkBox;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static javafx.scene.AccessibleAttribute.*;

final class Gtk4Accessible extends Accessible {

    private static final Gtk4Log.Category LOG = Gtk4Log.Category.STATE;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE;
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT;
    private static final AddressLayout P = ValueLayout.ADDRESS;

    private static final int PROP_ACCESSIBLE_ROLE = 1;
    private static final int TEXT_CONTENT_INSERT = 0;
    private static final int TEXT_CONTENT_REMOVE = 1;
    private static final int GRANULARITY_CHARACTER = 0;
    private static final int GRANULARITY_WORD = 1;
    private static final int GRANULARITY_SENTENCE = 2;
    private static final int GRANULARITY_LINE = 3;

    // JavaFX notifies few attribute changes, such as the text of a Labeled,
    // as the other platforms read the attributes when the assistive
    // technology asks; GTK keeps the values it was given, so the accessibles
    // in use are read again at this interval, in milliseconds
    private static final int REFRESH_INTERVAL = 500;

    private static final Map<Long, Gtk4Accessible> ACCESSIBLES = new HashMap<>();
    private static int refreshSource;
    private static MemorySegment refreshCallback;
    private static final Map<Long, Gtk4Window> HOSTS = new HashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);
    private static Gtk4Accessible focusOwner;
    // The node that has the focus, whose focus item can change without a notification of it
    private static Node focusNode;
    private String lastSpinnerText;
    private int spinnerChanges;

    private final long id = IDS.getAndIncrement();
    private MemorySegment peer;
    private GtkATContext atContext;
    private boolean disposed;
    private String text;
    private final Map<String, Object> pushed = new HashMap<>();

    Gtk4Accessible() {
    }

    static boolean isAvailable() {
        return Native.INSTANCE != null;
    }

    static GtkBox createHost(Gtk4Window window) {
        Native n = Native.INSTANCE;
        if (n == null) {
            return null;
        }
        try {
            MemorySegment host = n.host.create().getPointer();
            HOSTS.put(host.address(), window);
            return new GtkBox(host);
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "Cannot create the accessible host: %s", t);
            return null;
        }
    }

    /**
     * Makes the accessible of the scene of a window that shows, such as a
     * popup menu, while an assistive technology reads the application: its
     * scene notifies the focus only once it has one.
     */
    static void windowShown(Gtk4Window window) {
        if (Native.INSTANCE == null || ACCESSIBLES.isEmpty()) {
            return;
        }
        window.getCurrentView().ifPresent(view -> {
            View.EventHandler handler = view.getEventHandler();
            // A popup whose scene has no focused node, such as the list of a combo
            // box, leaves the focus to its owner
            if (handler != null && handler.getSceneAccessible() instanceof Gtk4Accessible acc
                    && acc.getAttribute(FOCUS_NODE) != null) {
                acc.sendNotification(FOCUS_NODE);
            }
        });
    }

    static void removeHost(GtkWidget host) {
        if (host != null) {
            HOSTS.remove(host.getPointer().address());
        }
    }

    static void hideFromAccessibility(GtkWidget widget) {
        Native n = Native.INSTANCE;
        if (n != null && widget != null) {
            n.updateState(widget.getPointer(), Gtk.ACCESSIBLE_STATE_HIDDEN, 1);
        }
    }

    // ---------------------------------------------------------------------
    // Glass

    @Override
    protected long getNativeAccessible() {
        MemorySegment p = peer();
        return p != null ? p.address() : 0L;
    }

    @Override
    public void dispose() {
        if (focusOwner == this) {
            focusOwner = null;
        }
        disposed = true;
        ACCESSIBLES.remove(id);
        Native n = Native.INSTANCE;
        if (n != null) {
            if (atContext != null) {
                n.unref(atContext.getPointer());
            }
            if (peer != null) {
                n.unref(peer);
            }
        }
        atContext = null;
        peer = null;
        super.dispose();
    }

    @Override
    public void sendNotification(AccessibleAttribute notification) {
        if (disposed || getEventHandler() == null) {
            return;
        }
        switch (notification) {
            case FOCUS_NODE -> focusNodeChanged();
            case FOCUS_ITEM -> focusItemChanged();
            case EXPANDED -> {
                pushAll();
                refocus();
            }
            case TEXT, VALUE_STRING -> {
                updateTextContents();
                pushAll();
            }
            case CARET_OFFSET -> caretChanged();
            case SELECTION_START, SELECTION_END -> selectionChanged();
            default -> pushAll();
        }
    }

    /** The focus moved to another node; a node out of a view forwards it to its scene. */
    private void focusNodeChanged() {
        Node node = (Node) getAttribute(FOCUS_NODE);
        if (node == null && getView() == null) {
            Scene scene = (Scene) getAttribute(SCENE);
            if (getAccessible(scene) instanceof Gtk4Accessible acc && acc != this) {
                acc.sendNotification(FOCUS_NODE);
            }
            return;
        }
        focusNode = node;
        setFocusOwner(focusTarget(node));
    }

    /**
     * The focused item of a control changed. The list of a combo box
     * notifies its focused item, which the combo box, which has the focus,
     * reports as its own while its popup shows.
     */
    private void focusItemChanged() {
        if (focusOwner == this || (focusOwner != null && isAncestorOf(focusOwner))) {
            Node item = (Node) getAttribute(FOCUS_ITEM);
            Gtk4Accessible acc = item != null ? accessibleOf(item) : null;
            setFocusOwner(acc != null ? acc : this);
        } else {
            refocus();
        }
    }

    // ---------------------------------------------------------------------
    // Tree

    private MemorySegment peer() {
        if (disposed) {
            return null;
        }
        if (peer == null) {
            Native n = Native.INSTANCE;
            if (n == null || getEventHandler() == null) {
                return null;
            }
            try {
                AccessibleRole role = role();
                GSubclass type = role == AccessibleRole.SPINNER ? n.textRange
                        : isTextRole(role) ? n.text : isRangeRole(role) ? n.range : n.base;
                MemorySegment obj = type.create().getPointer();
                type.setKey(obj, id);
                peer = obj;
                ACCESSIBLES.put(id, this);
            } catch (Throwable t) {
                Gtk4Log.log(LOG, "Cannot create an accessible: %s", t);
            }
        }
        return peer;
    }

    private AccessibleRole role() {
        Object role = getAttribute(ROLE);
        return role instanceof AccessibleRole r ? r : AccessibleRole.NODE;
    }

    private boolean isSceneAccessible() {
        return getView() != null;
    }

    private Gtk4Accessible accessibleOf(Node node) {
        return node != null && getAccessible(node) instanceof Gtk4Accessible acc && !acc.disposed ? acc : null;
    }

    private Gtk4Accessible sceneAccessible() {
        if (isSceneAccessible()) {
            return this;
        }
        Scene scene = (Scene) getAttribute(SCENE);
        return scene != null && getAccessible(scene) instanceof Gtk4Accessible acc ? acc : null;
    }

    private boolean isShown() {
        return !Boolean.FALSE.equals(getAttribute(VISIBLE));
    }

    private List<Gtk4Accessible> accessibleChildren() {
        List<Gtk4Accessible> result = new ArrayList<>();
        collectChildren(this, result);
        return result;
    }

    private static void collectChildren(Gtk4Accessible acc, List<Gtk4Accessible> result) {
        collectChildren(acc, result, -1);
    }

    private static void collectChildren(Gtk4Accessible acc, List<Gtk4Accessible> result, int inheritedCount) {
        AccessibleRole role = acc.role();
        if (!acc.isSceneAccessible() && isLeafRole(role)) {
            return;
        }
        if (!(acc.getAttribute(CHILDREN) instanceof List<?> list)) {
            return;
        }
        int itemCount = itemCount(acc, role, inheritedCount);
        for (Object o : list) {
            Gtk4Accessible child = o instanceof Node node ? acc.accessibleOf(node) : null;
            if (child == null || !child.isShown()) {
                continue;
            }
            if (child.isIgnored()) {
                collectChildren(child, result, itemCount);
            } else if (itemCount < 0 || child.isItemWithin(itemCount)) {
                result.add(child);
            }
        }
    }

    /**
     * The number of items of a list, table or tree, whose cells beyond it
     * are left out, or the count of the parent for the nodes in between.
     */
    private static int itemCount(Gtk4Accessible acc, AccessibleRole role, int inheritedCount) {
        return switch (role) {
            case LIST_VIEW -> acc.getAttribute(ITEM_COUNT) instanceof Integer c ? c : -1;
            case TABLE_VIEW -> acc.getAttribute(ROW_COUNT) instanceof Integer c ? c : -1;
            case TREE_VIEW, TREE_TABLE_VIEW -> acc.getAttribute(TREE_ITEM_COUNT) instanceof Integer c ? c
                    : acc.getAttribute(ROW_COUNT) instanceof Integer c2 ? c2 : -1;
            default -> inheritedCount;
        };
    }

    private boolean isItemWithin(int itemCount) {
        return switch (role()) {
            case LIST_ITEM, TABLE_ROW, TREE_ITEM, CHECK_BOX_TREE_ITEM, TREE_TABLE_ROW ->
                getAttribute(INDEX) instanceof Integer index && index >= 0 && index < itemCount;
            default -> true;
        };
    }

    private Gtk4Accessible accessibleParent() {
        if (isSceneAccessible()) {
            return null;
        }
        Node node = (Node) getAttribute(PARENT);
        while (node != null) {
            Gtk4Accessible acc = accessibleOf(node);
            if (acc == null) {
                return null;
            }
            if (!acc.isIgnored()) {
                return acc;
            }
            node = (Node) acc.getAttribute(PARENT);
        }
        return null;
    }

    private boolean isAncestorOf(Gtk4Accessible other) {
        for (Gtk4Accessible a = other.accessibleParent(); a != null; a = a.accessibleParent()) {
            if (a == this) {
                return true;
            }
        }
        return false;
    }

    private MemorySegment hostWidget() {
        Gtk4Accessible scene = sceneAccessible();
        if (scene == null) {
            return null;
        }
        // The glass gets the accessible of a scene from its event handler, which
        // does not give it the view
        for (Map.Entry<Long, Gtk4Window> e : HOSTS.entrySet()) {
            Gtk4View current = e.getValue().getCurrentView().orElse(null);
            View.EventHandler handler = current != null ? current.getEventHandler() : null;
            if (handler != null && handler.getSceneAccessible() == scene) {
                return MemorySegment.ofAddress(e.getKey());
            }
        }
        return null;
    }

    private List<Gtk4Accessible> siblings() {
        Gtk4Accessible parent = accessibleParent();
        if (parent != null) {
            return parent.accessibleChildren();
        }
        Gtk4Accessible scene = sceneAccessible();
        return scene != null ? scene.accessibleChildren() : List.of();
    }

    private Gtk4Accessible focusTarget(Node node) {
        Gtk4Accessible acc = accessibleOf(node);
        if (acc == null) {
            return null;
        }
        Node item = (Node) acc.getAttribute(FOCUS_ITEM);
        Gtk4Accessible itemAcc = item != null ? accessibleOf(item) : null;
        return itemAcc != null ? itemAcc : acc;
    }

    /**
     * Moves the focus to the current focus item of the focused node, such as
     * the item of the popup list of a combo box.
     */
    private void refocus() {
        if (focusNode == null) {
            return;
        }
        Gtk4Accessible target = focusTarget(focusNode);
        if (target != null && target != focusOwner) {
            setFocusOwner(target);
        }
    }

    private static void setFocusOwner(Gtk4Accessible acc) {
        Gtk4Accessible old = focusOwner;
        focusOwner = acc;
        Native n = Native.INSTANCE;
        if (n == null) {
            return;
        }
        if (old != null && old != acc && old.peer != null) {
            n.updatePlatformState(old.peer, Gtk.ACCESSIBLE_PLATFORM_STATE_FOCUSED);
        }
        if (acc != null && acc.peer() != null) {
            acc.ensureContext();
            n.updatePlatformState(acc.peer, Gtk.ACCESSIBLE_PLATFORM_STATE_FOCUSED);
        }
    }

    private Bounds screenBounds() {
        Object b = getAttribute(BOUNDS);
        return b instanceof Bounds bounds ? bounds : null;
    }

    private double[] sceneOrigin() {
        Object s = getAttribute(SCENE);
        if (s instanceof Scene scene && scene.getWindow() != null) {
            return new double[] {scene.getWindow().getX() + scene.getX(), scene.getWindow().getY() + scene.getY()};
        }
        return new double[] {0, 0};
    }

    // ---------------------------------------------------------------------
    // State

    private static int gtkRole(AccessibleRole role) {
        return switch (role) {
            case BUTTON, INCREMENT_BUTTON, DECREMENT_BUTTON, MENU_BUTTON, SPLIT_MENU_BUTTON, PAGE_ITEM ->
                Gtk.ACCESSIBLE_ROLE_BUTTON;
            case TOGGLE_BUTTON -> Gtk.ACCESSIBLE_ROLE_TOGGLE_BUTTON;
            case CHECK_BOX -> Gtk.ACCESSIBLE_ROLE_CHECKBOX;
            case RADIO_BUTTON -> Gtk.ACCESSIBLE_ROLE_RADIO;
            case HYPERLINK -> Gtk.ACCESSIBLE_ROLE_LINK;
            case COMBO_BOX, DATE_PICKER -> Gtk.ACCESSIBLE_ROLE_COMBO_BOX;
            case IMAGE_VIEW -> Gtk.ACCESSIBLE_ROLE_IMG;
            case LIST_VIEW -> Gtk.ACCESSIBLE_ROLE_LIST;
            case LIST_ITEM -> Gtk.ACCESSIBLE_ROLE_LIST_ITEM;
            case MENU, CONTEXT_MENU -> Gtk.ACCESSIBLE_ROLE_MENU;
            case MENU_BAR -> Gtk.ACCESSIBLE_ROLE_MENU_BAR;
            case MENU_ITEM -> Gtk.ACCESSIBLE_ROLE_MENU_ITEM;
            case CHECK_MENU_ITEM -> Gtk.ACCESSIBLE_ROLE_MENU_ITEM_CHECKBOX;
            case RADIO_MENU_ITEM -> Gtk.ACCESSIBLE_ROLE_MENU_ITEM_RADIO;
            case PAGINATION -> Gtk.ACCESSIBLE_ROLE_GROUP;
            case PASSWORD_FIELD, TEXT_FIELD, TEXT_AREA -> Gtk.ACCESSIBLE_ROLE_TEXT_BOX;
            case PROGRESS_INDICATOR -> Gtk.ACCESSIBLE_ROLE_PROGRESS_BAR;
            case SLIDER -> Gtk.ACCESSIBLE_ROLE_SLIDER;
            case SPINNER -> Gtk.ACCESSIBLE_ROLE_SPIN_BUTTON;
            case TEXT -> Gtk.ACCESSIBLE_ROLE_LABEL;
            case TOOLTIP -> Gtk.ACCESSIBLE_ROLE_TOOLTIP;
            case SCROLL_BAR -> Gtk.ACCESSIBLE_ROLE_SCROLLBAR;
            case SCROLL_PANE, TITLED_PANE -> Gtk.ACCESSIBLE_ROLE_GROUP;
            case TAB_ITEM -> Gtk.ACCESSIBLE_ROLE_TAB;
            case TAB_PANE -> Gtk.ACCESSIBLE_ROLE_TAB_LIST;
            case TABLE_VIEW -> Gtk.ACCESSIBLE_ROLE_TABLE;
            case TABLE_ROW, TREE_TABLE_ROW -> Gtk.ACCESSIBLE_ROLE_ROW;
            case TABLE_CELL, TREE_TABLE_CELL -> Gtk.ACCESSIBLE_ROLE_CELL;
            case TABLE_COLUMN -> Gtk.ACCESSIBLE_ROLE_COLUMN_HEADER;
            case TOOL_BAR -> Gtk.ACCESSIBLE_ROLE_TOOLBAR;
            case TREE_VIEW -> Gtk.ACCESSIBLE_ROLE_TREE;
            case TREE_ITEM, CHECK_BOX_TREE_ITEM -> Gtk.ACCESSIBLE_ROLE_TREE_ITEM;
            case TREE_TABLE_VIEW -> Gtk.ACCESSIBLE_ROLE_TREE_GRID;
            default -> Gtk.ACCESSIBLE_ROLE_GENERIC;
        };
    }

    private static boolean isTextRole(AccessibleRole role) {
        return switch (role) {
            case TEXT, TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, COMBO_BOX, SPINNER, DATE_PICKER -> true;
            default -> false;
        };
    }

    private static boolean isRangeRole(AccessibleRole role) {
        return switch (role) {
            case SLIDER, SCROLL_BAR, PROGRESS_INDICATOR -> true;
            default -> false;
        };
    }

    private static boolean isLeafRole(AccessibleRole role) {
        return switch (role) {
            case BUTTON, TOGGLE_BUTTON, CHECK_BOX, RADIO_BUTTON, HYPERLINK, TEXT, TEXT_FIELD, TEXT_AREA,
                 PASSWORD_FIELD, SLIDER, PROGRESS_INDICATOR, IMAGE_VIEW, INCREMENT_BUTTON, DECREMENT_BUTTON,
                 MENU_ITEM, CHECK_MENU_ITEM, RADIO_MENU_ITEM, LIST_ITEM, TABLE_CELL, TREE_TABLE_CELL, TREE_ITEM,
                 CHECK_BOX_TREE_ITEM, TAB_ITEM, PAGE_ITEM, TOOLTIP -> true;
            default -> false;
        };
    }

    private static boolean isFocusableRole(AccessibleRole role) {
        return switch (role) {
            case NODE, PARENT, TEXT, IMAGE_VIEW, TOOLTIP, SCROLL_PANE, TITLED_PANE, TAB_PANE, THUMB,
                 TABLE_COLUMN, TOOL_BAR, PAGINATION, MENU_BAR, CONTEXT_MENU, MENU -> false;
            default -> true;
        };
    }

    private String accessibleName(AccessibleRole role) {
        String name = switch (role) {
            case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD, COMBO_BOX, SPINNER, DATE_PICKER -> null;
            default -> (String) getAttribute(TEXT);
        };
        if ((role == AccessibleRole.INCREMENT_BUTTON || role == AccessibleRole.DECREMENT_BUTTON)
                && (name == null || name.isEmpty())) {
            name = role == AccessibleRole.INCREMENT_BUTTON ? "increment" : "decrement";
        }
        if (name == null || name.isEmpty()) {
            Node label = (Node) getAttribute(LABELED_BY);
            Gtk4Accessible acc = label != null ? accessibleOf(label) : null;
            if (acc != null) {
                name = (String) acc.getAttribute(TEXT);
            }
        }
        return name == null || name.isEmpty() ? null : name;
    }

    private void ensureContext() {
        Native n = Native.INSTANCE;
        if (n != null && atContext == null && peer() != null) {
            atContext = n.createContext(gtkRole(role()), peer);
            pushAll();
            scheduleRefresh();
        }
    }

    private static void scheduleRefresh() {
        if (refreshSource == 0) {
            refreshSource = GLib.timeoutAddFull(GLib.PRIORITY_DEFAULT, REFRESH_INTERVAL, refreshCallback(),
                    MemorySegment.NULL, MemorySegment.NULL);
        }
    }

    @SuppressWarnings("unused")
    private static int refresh(MemorySegment data) {
        try {
            for (Gtk4Accessible acc : List.copyOf(ACCESSIBLES.values())) {
                if (!acc.disposed && acc.atContext != null) {
                    acc.pushAll();
                }
            }
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "Cannot refresh the accessibles: %s", t);
        }
        if (ACCESSIBLES.isEmpty()) {
            refreshSource = 0;
            return 0;
        }
        return 1;
    }

    private static MemorySegment refreshCallback() {
        if (refreshCallback == null) {
            try {
                refreshCallback = Linker.nativeLinker().upcallStub(
                        MethodHandles.lookup().findStatic(Gtk4Accessible.class, "refresh",
                                MethodType.methodType(int.class, MemorySegment.class)),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                        Arena.global());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return refreshCallback;
    }

    private void pushAll() {
        Native n = Native.INSTANCE;
        if (n == null || atContext == null || peer == null || getEventHandler() == null) {
            return;
        }
        AccessibleRole role = role();
        pushString(n, Gtk.ACCESSIBLE_PROPERTY_LABEL, accessibleName(role));
        pushString(n, Gtk.ACCESSIBLE_PROPERTY_DESCRIPTION, (String) getAttribute(HELP));
        pushString(n, Gtk.ACCESSIBLE_PROPERTY_ROLE_DESCRIPTION, (String) getAttribute(ROLE_DESCRIPTION));
        pushState(n, Gtk.ACCESSIBLE_STATE_DISABLED, isTrue(DISABLED) ? 1 : 0);
        pushRoleStates(n, role);
        pushExpanded(n, role);
        if (role == AccessibleRole.TREE_ITEM || role == AccessibleRole.CHECK_BOX_TREE_ITEM) {
            Object level = getAttribute(DISCLOSURE_LEVEL);
            if (level instanceof Integer l) {
                pushInt(n, Gtk.ACCESSIBLE_PROPERTY_LEVEL, l + 1);
            }
        }
    }

    private boolean isTrue(AccessibleAttribute attribute) {
        return Boolean.TRUE.equals(getAttribute(attribute));
    }

    private static int tristate(boolean value) {
        return value ? Gtk.ACCESSIBLE_TRISTATE_TRUE : Gtk.ACCESSIBLE_TRISTATE_FALSE;
    }

    /** The states and properties that depend on the role: checked, pressed, selected, range, text. */
    private void pushRoleStates(Native n, AccessibleRole role) {
        switch (role) {
            case CHECK_BOX, CHECK_BOX_TREE_ITEM -> pushState(n, Gtk.ACCESSIBLE_STATE_CHECKED, checkBoxState());
            case RADIO_BUTTON, CHECK_MENU_ITEM, RADIO_MENU_ITEM ->
                pushState(n, Gtk.ACCESSIBLE_STATE_CHECKED, tristate(isTrue(SELECTED)));
            case TOGGLE_BUTTON -> pushState(n, Gtk.ACCESSIBLE_STATE_PRESSED, tristate(isTrue(SELECTED)));
            case LIST_ITEM, TAB_ITEM, TABLE_ROW, TABLE_CELL, TREE_ITEM, TREE_TABLE_ROW, TREE_TABLE_CELL,
                 PAGE_ITEM -> pushState(n, Gtk.ACCESSIBLE_STATE_SELECTED, isTrue(SELECTED) ? 1 : 0);
            case SLIDER, SCROLL_BAR, PROGRESS_INDICATOR -> pushRangeState(n, role);
            case SPINNER -> pushSpinnerValue(n);
            case TEXT_FIELD, TEXT_AREA, PASSWORD_FIELD -> {
                pushInt(n, Gtk.ACCESSIBLE_PROPERTY_READ_ONLY, Boolean.FALSE.equals(getAttribute(EDITABLE)) ? 1 : 0);
                pushInt(n, Gtk.ACCESSIBLE_PROPERTY_MULTI_LINE, role == AccessibleRole.TEXT_AREA ? 1 : 0);
            }
            default -> {
            }
        }
    }

    /** Checked, unchecked or mixed, from the toggle state, or else from the selection. */
    private int checkBoxState() {
        Object toggle = getAttribute(TOGGLE_STATE);
        boolean mixed = toggle != null ? toggle == ToggleState.INDETERMINATE : isTrue(INDETERMINATE);
        boolean checked = toggle != null ? toggle == ToggleState.CHECKED : isTrue(SELECTED);
        return mixed ? Gtk.ACCESSIBLE_TRISTATE_MIXED : tristate(checked);
    }

    /** The range, the orientation and, for a progress indicator, whether it is busy. */
    private void pushRangeState(Native n, AccessibleRole role) {
        pushRange(n);
        if (getAttribute(ORIENTATION) instanceof Orientation o) {
            pushInt(n, Gtk.ACCESSIBLE_PROPERTY_ORIENTATION, o == Orientation.HORIZONTAL
                    ? Gtk.ORIENTATION_HORIZONTAL : Gtk.ORIENTATION_VERTICAL);
        }
        if (role == AccessibleRole.PROGRESS_INDICATOR) {
            pushState(n, Gtk.ACCESSIBLE_STATE_BUSY, isTrue(INDETERMINATE) ? 1 : 0);
        }
    }

    private void pushSpinnerValue(Native n) {
        String value = (String) getAttribute(VALUE_STRING);
        pushString(n, Gtk.ACCESSIBLE_PROPERTY_VALUE_TEXT, value);
        // AT-SPI tells the change of a value by its number: a text that is not a
        // number gets one that changes with it
        pushDouble(n, Gtk.ACCESSIBLE_PROPERTY_VALUE_NOW, spinnerNumber(value));
    }

    /** Whether a node that can expand is expanded; tree leaves cannot. */
    private void pushExpanded(Native n, AccessibleRole role) {
        switch (role) {
            case TREE_ITEM, CHECK_BOX_TREE_ITEM, TITLED_PANE, COMBO_BOX, DATE_PICKER, MENU_BUTTON,
                 SPLIT_MENU_BUTTON, TREE_TABLE_ROW -> {
                boolean leaf = role.name().startsWith("TREE") && isTrue(LEAF);
                if (getAttribute(EXPANDED) instanceof Boolean b && !leaf) {
                    pushState(n, Gtk.ACCESSIBLE_STATE_EXPANDED, b ? 1 : 0);
                }
            }
            default -> {
            }
        }
    }

    private double spinnerNumber(String value) {
        if (value != null) {
            try {
                return Double.parseDouble(value.trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                // Not a number
            }
        }
        if (!Objects.equals(value, lastSpinnerText)) {
            lastSpinnerText = value;
            spinnerChanges++;
        }
        return spinnerChanges;
    }

    private void pushRange(Native n) {
        Object min = getAttribute(MIN_VALUE);
        Object max = getAttribute(MAX_VALUE);
        Object value = getAttribute(VALUE);
        if (min instanceof Double d) {
            pushDouble(n, Gtk.ACCESSIBLE_PROPERTY_VALUE_MIN, d);
        }
        if (max instanceof Double d) {
            pushDouble(n, Gtk.ACCESSIBLE_PROPERTY_VALUE_MAX, d);
        }
        if (value instanceof Double d) {
            pushDouble(n, Gtk.ACCESSIBLE_PROPERTY_VALUE_NOW, d);
        }
    }

    private boolean changed(String key, Object value) {
        if (pushed.containsKey(key) && Objects.equals(pushed.get(key), value)) {
            return false;
        }
        pushed.put(key, value);
        return true;
    }

    private void pushString(Native n, int property, String value) {
        if (changed("p" + property, value)) {
            if (value == null) {
                n.resetProperty(peer, property);
            } else {
                n.updateProperty(peer, property, value);
            }
        }
    }

    private void pushInt(Native n, int property, int value) {
        if (changed("p" + property, value)) {
            n.updateProperty(peer, property, value);
        }
    }

    private void pushDouble(Native n, int property, double value) {
        if (changed("p" + property, value)) {
            n.updateProperty(peer, property, value);
        }
    }

    private void pushState(Native n, int state, int value) {
        if (changed("s" + state, value)) {
            n.updateState(peer, state, value);
        }
    }

    // ---------------------------------------------------------------------
    // Text

    private String currentText() {
        // The text of a spinner is the accessible text of the control, its value is the text read
        Object t = getAttribute(role() == AccessibleRole.SPINNER ? VALUE_STRING : TEXT);
        return t instanceof String s ? s : "";
    }

    private static int toCodePoints(String s, int index) {
        index = Math.max(0, Math.min(index, s.length()));
        return s.codePointCount(0, index);
    }

    private static int toChars(String s, int codePoints) {
        int count = s.codePointCount(0, s.length());
        return s.offsetByCodePoints(0, Math.max(0, Math.min(codePoints, count)));
    }

    private void updateTextContents() {
        Native n = Native.INSTANCE;
        if (n == null || atContext == null || !isTextRole(role())) {
            text = null;
            return;
        }
        String newText = currentText();
        String oldText = text != null ? text : "";
        text = newText;
        if (oldText.equals(newText)) {
            return;
        }
        int prefix = commonPrefix(oldText, newText);
        int suffix = commonSuffix(oldText, newText, prefix);
        int start = toCodePoints(oldText, prefix);
        int removedEnd = toCodePoints(oldText, oldText.length() - suffix);
        int insertedEnd = toCodePoints(newText, newText.length() - suffix);
        if (removedEnd > start) {
            n.textUpdateContents(peer, TEXT_CONTENT_REMOVE, start, removedEnd);
        }
        if (insertedEnd > start) {
            n.textUpdateContents(peer, TEXT_CONTENT_INSERT, start, insertedEnd);
        }
    }

    private static int commonPrefix(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int prefix = 0;
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return prefix;
    }

    /** The length of the common end of two texts, after their common start. */
    private static int commonSuffix(String a, String b, int prefix) {
        int max = Math.min(a.length(), b.length()) - prefix;
        int suffix = 0;
        while (suffix < max && a.charAt(a.length() - 1 - suffix) == b.charAt(b.length() - 1 - suffix)) {
            suffix++;
        }
        return suffix;
    }

    private void caretChanged() {
        Native n = Native.INSTANCE;
        if (n != null && atContext != null && isTextRole(role())) {
            n.textUpdateCaret(peer);
        }
    }

    private void selectionChanged() {
        Native n = Native.INSTANCE;
        if (n != null && atContext != null && isTextRole(role())) {
            n.textUpdateSelection(peer);
        }
    }

    private int[] boundary(String s, int offset, int granularity) {
        int length = s.length();
        int pos = Math.max(0, Math.min(offset, length));
        return switch (granularity) {
            case GRANULARITY_CHARACTER -> new int[] {pos, pos < length ? s.offsetByCodePoints(pos, 1) : pos};
            case GRANULARITY_WORD -> breakBoundary(BreakIterator.getWordInstance(), s, pos);
            case GRANULARITY_SENTENCE -> breakBoundary(BreakIterator.getSentenceInstance(), s, pos);
            case GRANULARITY_LINE -> lineBoundary(s, pos);
            default -> paragraph(s, pos);
        };
    }

    /** The word or sentence around a position. */
    private static int[] breakBoundary(BreakIterator it, String s, int pos) {
        it.setText(s);
        int start = it.isBoundary(pos) ? pos : it.preceding(pos);
        int end = it.following(pos);
        return new int[] {start == BreakIterator.DONE ? 0 : start, end == BreakIterator.DONE ? s.length() : end};
    }

    /** The line of a position, as the control lays it out, or else its paragraph. */
    private int[] lineBoundary(String s, int pos) {
        if (getAttribute(LINE_FOR_OFFSET, pos) instanceof Integer line
                && getAttribute(LINE_START, line) instanceof Integer start
                && getAttribute(LINE_END, line) instanceof Integer end) {
            return new int[] {start, end};
        }
        return paragraph(s, pos);
    }

    private static int[] paragraph(String s, int pos) {
        int start = pos > 0 ? s.lastIndexOf('\n', pos - 1) + 1 : 0;
        int end = s.indexOf('\n', pos);
        return new int[] {start, end < 0 ? s.length() : end + 1};
    }

    // ---------------------------------------------------------------------
    // Upcalls

    private static void putInt(MemorySegment out, int value) {
        if (out.address() != 0) {
            out.reinterpret(I32.byteSize()).set(I32, 0, value);
        }
    }

    private static void putLong(MemorySegment out, long value) {
        if (out.address() != 0) {
            out.reinterpret(I64.byteSize()).set(I64, 0, value);
        }
    }

    private static void putPointer(MemorySegment out, MemorySegment value) {
        if (out.address() != 0) {
            out.reinterpret(P.byteSize()).set(P, 0, value);
        }
    }

    private static Gtk4Accessible from(MemorySegment self) {
        Native n = Native.INSTANCE;
        if (n == null || self == null || self.address() == 0) {
            return null;
        }
        long key = n.base.getKey(self);
        Gtk4Accessible acc = ACCESSIBLES.get(key);
        return acc != null && !acc.disposed && acc.getEventHandler() != null ? acc : null;
    }

    private static MemorySegment ref(Gtk4Accessible acc) {
        Native n = Native.INSTANCE;
        MemorySegment p = acc != null ? acc.peer() : null;
        return p != null ? n.ref(p) : MemorySegment.NULL;
    }

    static void classInit(MemorySegment klass, MemorySegment data) {
        Native n = Native.INSTANCE0;
        try {
            GObjectClass k = new GObjectClass(klass);
            k.setProperty(n.setPropertyStub);
            k.getProperty(n.getPropertyStub);
            k.overrideProperty(PROP_ACCESSIBLE_ROLE, "accessible-role");
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "classInit: %s", t);
        }
    }

    static void setProperty(MemorySegment obj, int propId, MemorySegment value, MemorySegment pspec) {
    }

    static void getProperty(MemorySegment obj, int propId, MemorySegment value, MemorySegment pspec) {
        try {
            if (propId == PROP_ACCESSIBLE_ROLE) {
                Gtk4Accessible acc = from(obj);
                int role = acc != null ? gtkRole(acc.role()) : Gtk.ACCESSIBLE_ROLE_GENERIC;
                new GValue(value).setEnum(role);
            }
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getProperty: %s", t);
        }
    }

    static void accessibleInit(MemorySegment iface, MemorySegment data) {
        Native n = Native.INSTANCE0;
        GtkAccessibleInterface v = new GtkAccessibleInterface(iface);
        v.getAtContext(n.getAtContextStub);
        v.getPlatformState(n.getPlatformStateStub);
        v.getAccessibleParent(n.getParentStub);
        v.getFirstAccessibleChild(n.getFirstChildStub);
        v.getNextAccessibleSibling(n.getNextSiblingStub);
        v.getBounds(n.getBoundsStub);
    }

    static void rangeInit(MemorySegment iface, MemorySegment data) {
        Native n = Native.INSTANCE0;
        new GtkAccessibleRangeInterface(iface).setCurrentValue(n.setCurrentValueStub);
    }

    static void textInit(MemorySegment iface, MemorySegment data) {
        Native n = Native.INSTANCE0;
        GtkAccessibleTextInterface v = new GtkAccessibleTextInterface(iface);
        v.getContents(n.textContentsStub);
        v.getContentsAt(n.textContentsAtStub);
        v.getCaretPosition(n.textCaretStub);
        v.getSelection(n.textSelectionStub);
        v.getAttributes(n.textAttributesStub);
        v.getDefaultAttributes(n.textDefaultAttributesStub);
        if (n.minorVersion >= 16) {
            v.getExtents(n.textExtentsStub);
            v.getOffset(n.textOffsetStub);
        }
        if (n.minorVersion >= 22) {
            v.setCaretPosition(n.textSetCaretStub);
            v.setSelection(n.textSetSelectionStub);
        }
    }

    static void hostInit(MemorySegment iface, MemorySegment data) {
        Native n = Native.INSTANCE0;
        GtkAccessibleInterface v = new GtkAccessibleInterface(iface);
        n.hostParentFirstChild = v.getFirstAccessibleChild();
        v.getFirstAccessibleChild(n.hostFirstChildStub);
    }

    static MemorySegment getAtContext(MemorySegment self) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return MemorySegment.NULL;
            }
            acc.ensureContext();
            return acc.atContext != null ? Native.INSTANCE.ref(acc.atContext.getPointer()) : MemorySegment.NULL;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getAtContext: %s", t);
            return MemorySegment.NULL;
        }
    }

    static int getPlatformState(MemorySegment self, int state) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return 0;
            }
            return switch (state) {
                case Gtk.ACCESSIBLE_PLATFORM_STATE_FOCUSABLE -> isFocusableRole(acc.role()) ? 1 : 0;
                case Gtk.ACCESSIBLE_PLATFORM_STATE_FOCUSED -> focusOwner == acc ? 1 : 0;
                default -> 0;
            };
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getPlatformState: %s", t);
            return 0;
        }
    }

    static MemorySegment getParent(MemorySegment self) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return MemorySegment.NULL;
            }
            Gtk4Accessible parent = acc.accessibleParent();
            if (parent != null) {
                return ref(parent);
            }
            MemorySegment host = acc.hostWidget();
            return host != null ? Native.INSTANCE.ref(host) : MemorySegment.NULL;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getParent: %s", t);
            return MemorySegment.NULL;
        }
    }

    static MemorySegment getFirstChild(MemorySegment self) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return MemorySegment.NULL;
            }
            List<Gtk4Accessible> children = acc.accessibleChildren();
            return children.isEmpty() ? MemorySegment.NULL : ref(children.get(0));
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getFirstChild: %s", t);
            return MemorySegment.NULL;
        }
    }

    static MemorySegment getNextSibling(MemorySegment self) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return MemorySegment.NULL;
            }
            List<Gtk4Accessible> siblings = acc.siblings();
            int index = siblings.indexOf(acc);
            if (index >= 0 && index + 1 < siblings.size()) {
                return ref(siblings.get(index + 1));
            }
            // After the nodes of a window come its popups (menus, the lists of the combo
            // boxes), whose popovers are children of its host
            Gtk4Accessible parent = acc.accessibleParent();
            if (index >= 0 && (parent == null || parent.isSceneAccessible())) {
                Gtk4Accessible scene = parent != null ? parent : acc.sceneAccessible();
                MemorySegment host = scene != null ? scene.hostWidget() : null;
                Gtk4Window window = host != null ? HOSTS.get(host.address()) : null;
                MemorySegment popover = window != null ? window.firstShownPopover() : null;
                if (popover != null) {
                    return Native.INSTANCE.ref(popover);
                }
            }
            return MemorySegment.NULL;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getNextSibling: %s", t);
            return MemorySegment.NULL;
        }
    }

    static int getBounds(MemorySegment self, MemorySegment x, MemorySegment y, MemorySegment w, MemorySegment h) {
        try {
            Gtk4Accessible acc = from(self);
            Bounds b = acc != null ? acc.screenBounds() : null;
            if (b == null) {
                return 0;
            }
            double ox;
            double oy;
            Gtk4Accessible parent = acc.accessibleParent();
            Bounds pb = parent != null ? parent.screenBounds() : null;
            if (pb != null) {
                ox = pb.getMinX();
                oy = pb.getMinY();
            } else {
                double[] origin = acc.sceneOrigin();
                ox = origin[0];
                oy = origin[1];
            }
            putInt(x, (int) Math.round(b.getMinX() - ox));
            putInt(y, (int) Math.round(b.getMinY() - oy));
            putInt(w, (int) Math.ceil(b.getWidth()));
            putInt(h, (int) Math.ceil(b.getHeight()));
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "getBounds: %s", t);
            return 0;
        }
    }

    static int setCurrentValue(MemorySegment self, double value) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return 0;
            }
            acc.executeAction(AccessibleAction.SET_VALUE, value);
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "setCurrentValue: %s", t);
            return 0;
        }
    }

    static MemorySegment hostFirstChild(MemorySegment self) {
        Native n = Native.INSTANCE;
        try {
            Gtk4Window window = HOSTS.get(self.address());
            Gtk4View view = window != null ? window.getCurrentView().orElse(null) : null;
            View.EventHandler handler = view != null ? view.getEventHandler() : null;
            Accessible scene = handler != null ? handler.getSceneAccessible() : null;
            if (scene instanceof Gtk4Accessible acc && !acc.disposed) {
                List<Gtk4Accessible> children = acc.accessibleChildren();
                if (!children.isEmpty()) {
                    return ref(children.get(0));
                }
            }
            return n.hostParentFirstChild(self);
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "hostFirstChild: %s", t);
            return MemorySegment.NULL;
        }
    }

    static MemorySegment textContents(MemorySegment self, int start, int end) {
        try {
            Gtk4Accessible acc = from(self);
            String s = acc != null ? acc.currentText() : "";
            int a = toChars(s, start);
            int b = end < 0 ? s.length() : toChars(s, end);
            return Native.INSTANCE.bytes(s.substring(a, Math.max(a, b)));
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textContents: %s", t);
            return MemorySegment.NULL;
        }
    }

    static MemorySegment textContentsAt(MemorySegment self, int offset, int granularity,
                                        MemorySegment start, MemorySegment end) {
        try {
            Gtk4Accessible acc = from(self);
            String s = acc != null ? acc.currentText() : "";
            int[] range = acc != null ? acc.boundary(s, toChars(s, offset), granularity) : new int[] {0, 0};
            putInt(start, toCodePoints(s, range[0]));
            putInt(end, toCodePoints(s, range[1]));
            return Native.INSTANCE.bytes(s.substring(range[0], Math.max(range[0], range[1])));
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textContentsAt: %s", t);
            return MemorySegment.NULL;
        }
    }

    static int textCaret(MemorySegment self) {
        try {
            Gtk4Accessible acc = from(self);
            Object caret = acc != null ? acc.getAttribute(CARET_OFFSET) : null;
            return caret instanceof Integer c ? toCodePoints(acc.currentText(), c) : 0;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textCaret: %s", t);
            return 0;
        }
    }

    static int textSelection(MemorySegment self, MemorySegment nRanges, MemorySegment ranges) {
        try {
            Gtk4Accessible acc = from(self);
            putLong(nRanges, 0L);
            putPointer(ranges, MemorySegment.NULL);
            if (acc == null) {
                return 0;
            }
            Object a = acc.getAttribute(SELECTION_START);
            Object b = acc.getAttribute(SELECTION_END);
            if (!(a instanceof Integer s) || !(b instanceof Integer e) || s.equals(e) || s < 0 || e < 0) {
                return 0;
            }
            String text = acc.currentText();
            int start = toCodePoints(text, Math.min(s, e));
            int end = toCodePoints(text, Math.max(s, e));
            putLong(nRanges, 1L);
            if (ranges.address() != 0) {
                GtkAccessibleTextRange range = new GtkAccessibleTextRange(
                        Native.INSTANCE.malloc(GtkAccessibleTextRange.layout().byteSize()));
                range.start(start);
                range.length(end - start);
                putPointer(ranges, range.segment());
            }
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textSelection: %s", t);
            return 0;
        }
    }

    static int textAttributes(MemorySegment self, int offset, MemorySegment nRanges, MemorySegment ranges,
                              MemorySegment names, MemorySegment values) {
        try {
            Native n = Native.INSTANCE;
            putLong(nRanges, 0L);
            putPointer(ranges, MemorySegment.NULL);
            putPointer(names, n.malloc(8));
            putPointer(values, n.malloc(8));
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textAttributes: %s", t);
            return 0;
        }
    }

    static void textDefaultAttributes(MemorySegment self, MemorySegment names, MemorySegment values) {
        try {
            Native n = Native.INSTANCE;
            putPointer(names, n.malloc(8));
            putPointer(values, n.malloc(8));
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textDefaultAttributes: %s", t);
        }
    }

    static int textExtents(MemorySegment self, int start, int end, MemorySegment rect) {
        try {
            Gtk4Accessible acc = from(self);
            Bounds own = acc != null ? acc.screenBounds() : null;
            if (own == null) {
                return 0;
            }
            String s = acc.currentText();
            Object r = acc.getAttribute(BOUNDS_FOR_RANGE, toChars(s, start), Math.max(toChars(s, end) - 1, 0));
            if (!(r instanceof Bounds[] parts) || parts.length == 0) {
                return 0;
            }
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Bounds b : parts) {
                minX = Math.min(minX, b.getMinX());
                minY = Math.min(minY, b.getMinY());
                maxX = Math.max(maxX, b.getMaxX());
                maxY = Math.max(maxY, b.getMaxY());
            }
            MemorySegment out = rect.reinterpret(16);
            out.set(F32, 0, (float) (minX - own.getMinX()));
            out.set(F32, 4, (float) (minY - own.getMinY()));
            out.set(F32, 8, (float) (maxX - minX));
            out.set(F32, 12, (float) (maxY - minY));
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textExtents: %s", t);
            return 0;
        }
    }

    static int textOffset(MemorySegment self, MemorySegment point, MemorySegment offset) {
        try {
            Gtk4Accessible acc = from(self);
            Bounds own = acc != null ? acc.screenBounds() : null;
            if (own == null) {
                return 0;
            }
            MemorySegment p = point.reinterpret(8);
            Point2D screen = new Point2D(own.getMinX() + p.get(F32, 0), own.getMinY() + p.get(F32, 4));
            Object o = acc.getAttribute(OFFSET_AT_POINT, screen);
            if (!(o instanceof Integer i)) {
                return 0;
            }
            putInt(offset, toCodePoints(acc.currentText(), i));
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textOffset: %s", t);
            return 0;
        }
    }

    static int textSetCaret(MemorySegment self, int offset) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null) {
                return 0;
            }
            int index = toChars(acc.currentText(), offset);
            acc.executeAction(AccessibleAction.SET_TEXT_SELECTION, index, index);
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textSetCaret: %s", t);
            return 0;
        }
    }

    static int textSetSelection(MemorySegment self, long i, MemorySegment range) {
        try {
            Gtk4Accessible acc = from(self);
            if (acc == null || i != 0) {
                return 0;
            }
            GtkAccessibleTextRange r = new GtkAccessibleTextRange(range);
            String s = acc.currentText();
            int start = toChars(s, (int) r.start());
            int end = toChars(s, (int) (r.start() + r.length()));
            acc.executeAction(AccessibleAction.SET_TEXT_SELECTION, start, end);
            return 1;
        } catch (Throwable t) {
            Gtk4Log.log(LOG, "textSetSelection: %s", t);
            return 0;
        }
    }

    // ---------------------------------------------------------------------
    // Native

    private static final class Native {
        static final Native INSTANCE;
        static Native INSTANCE0;

        static {
            Native n = null;
            try {
                n = new Native();
            } catch (Throwable t) {
                Gtk4Log.log(LOG, "Accessibility is unavailable: %s", t);
                n = null;
            }
            INSTANCE = n;
        }

        final Arena arena = Arena.global();

        final MemorySegment setPropertyStub;
        final MemorySegment getPropertyStub;
        final MemorySegment getAtContextStub;
        final MemorySegment getPlatformStateStub;
        final MemorySegment getParentStub;
        final MemorySegment getFirstChildStub;
        final MemorySegment getNextSiblingStub;
        final MemorySegment getBoundsStub;
        final MemorySegment setCurrentValueStub;
        final MemorySegment hostFirstChildStub;
        final MemorySegment textContentsStub;
        final MemorySegment textContentsAtStub;
        final MemorySegment textCaretStub;
        final MemorySegment textSelectionStub;
        final MemorySegment textAttributesStub;
        final MemorySegment textDefaultAttributesStub;
        final MemorySegment textExtentsStub;
        final MemorySegment textOffsetStub;
        final MemorySegment textSetCaretStub;
        final MemorySegment textSetSelectionStub;

        final int minorVersion;
        final long booleanType;
        final long intType;
        final long enumType;
        final GSubclass base;
        final GSubclass range;
        final GSubclass text;
        // A spin button: a text, its value, and a range for the Value interface of AT-SPI
        final GSubclass textRange;
        final GSubclass host;
        MemorySegment hostParentFirstChild = MemorySegment.NULL;

        private Native() throws Throwable {
            INSTANCE0 = this;

            setPropertyStub = up("setProperty", FunctionDescriptor.ofVoid(P, I32, P, P));
            getPropertyStub = up("getProperty", FunctionDescriptor.ofVoid(P, I32, P, P));
            getAtContextStub = up("getAtContext", FunctionDescriptor.of(P, P));
            getPlatformStateStub = up("getPlatformState", FunctionDescriptor.of(I32, P, I32));
            getParentStub = up("getParent", FunctionDescriptor.of(P, P));
            getFirstChildStub = up("getFirstChild", FunctionDescriptor.of(P, P));
            getNextSiblingStub = up("getNextSibling", FunctionDescriptor.of(P, P));
            getBoundsStub = up("getBounds", FunctionDescriptor.of(I32, P, P, P, P, P));
            setCurrentValueStub = up("setCurrentValue", FunctionDescriptor.of(I32, P, F64));
            hostFirstChildStub = up("hostFirstChild", FunctionDescriptor.of(P, P));
            textContentsStub = up("textContents", FunctionDescriptor.of(P, P, I32, I32));
            textContentsAtStub = up("textContentsAt", FunctionDescriptor.of(P, P, I32, I32, P, P));
            textCaretStub = up("textCaret", FunctionDescriptor.of(I32, P));
            textSelectionStub = up("textSelection", FunctionDescriptor.of(I32, P, P, P));
            textAttributesStub = up("textAttributes", FunctionDescriptor.of(I32, P, I32, P, P, P, P));
            textDefaultAttributesStub = up("textDefaultAttributes", FunctionDescriptor.ofVoid(P, P, P));
            textExtentsStub = up("textExtents", FunctionDescriptor.of(I32, P, I32, I32, P));
            textOffsetStub = up("textOffset", FunctionDescriptor.of(I32, P, P, P));
            textSetCaretStub = up("textSetCaret", FunctionDescriptor.of(I32, P, I32));
            textSetSelectionStub = up("textSetSelection", FunctionDescriptor.of(I32, P, I64, P));
            MemorySegment classInitStub = up("classInit", FunctionDescriptor.ofVoid(P, P));
            MemorySegment accessibleInitStub = up("accessibleInit", FunctionDescriptor.ofVoid(P, P));
            MemorySegment rangeInitStub = up("rangeInit", FunctionDescriptor.ofVoid(P, P));
            MemorySegment textInitStub = up("textInit", FunctionDescriptor.ofVoid(P, P));
            MemorySegment hostInitStub = up("hostInit", FunctionDescriptor.ofVoid(P, P));

            minorVersion = Gtk.getMinorVersion();
            booleanType = GObjectFunctions.typeFromName("gboolean");
            intType = GObjectFunctions.typeFromName("gint");
            enumType = GObjectFunctions.typeFromName("GEnum");

            long accessibleType = GtkAccessible.getGType();
            long rangeType = GtkAccessibleRange.getGType();
            long textType = GtkAccessibleText.getGType();
            base = registerAccessible("GlassGtk4Accessible", classInitStub, accessibleInitStub);
            range = registerAccessible("GlassGtk4AccessibleRange", classInitStub, accessibleInitStub);
            range.addInterface(rangeType, rangeInitStub);
            text = registerAccessible("GlassGtk4AccessibleText", classInitStub, accessibleInitStub);
            text.addInterface(textType, textInitStub);
            textRange = registerAccessible("GlassGtk4AccessibleTextRange", classInitStub, accessibleInitStub);
            textRange.addInterface(textType, textInitStub);
            textRange.addInterface(rangeType, rangeInitStub);
            host = GSubclass.register(GtkBox.getGType(), "GlassGtk4AccessibleHost", null);
            host.addInterface(accessibleType, hostInitStub);
        }

        /** A GObject type that implements GtkAccessible, for the nodes of the scene. */
        private static GSubclass registerAccessible(String name, MemorySegment classInit, MemorySegment accessibleInit) {
            GSubclass type = GSubclass.register(GObject.getGType(), name, classInit);
            type.addInterface(GtkAccessible.getGType(), accessibleInit);
            return type;
        }

        private MemorySegment up(String name, FunctionDescriptor fd) throws ReflectiveOperationException {
            MethodType type = fd.toMethodType();
            MethodHandle mh = MethodHandles.lookup().findStatic(Gtk4Accessible.class, name, type);
            return Linker.nativeLinker().upcallStub(mh, fd, arena);
        }

        MemorySegment ref(MemorySegment obj) {
            return new GObject(obj).ref().getPointer();
        }

        void unref(MemorySegment obj) {
            try {
                new GObject(obj).unref();
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "unref: %s", e);
            }
        }

        GtkATContext createContext(int role, MemorySegment accessible) {
            try {
                GtkATContext context = GtkATContext.create(role, GtkAccessible.wrap(accessible),
                        GdkDisplay.getDefault());
                return context.isNull() ? null : context;
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "createContext: %s", e);
                return null;
            }
        }

        void updatePlatformState(MemorySegment obj, int state) {
            try {
                GtkAccessible.wrap(obj).updatePlatformState(state);
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "updatePlatformState: %s", e);
            }
        }

        void updateProperty(MemorySegment obj, int property, Object value) {
            try (Arena a = Arena.ofConfined()) {
                GValue v = GValue.create(a);
                Gtk.accessiblePropertyInitValue(property, v);
                setValue(v, value);
                GtkAccessible.wrap(obj).updatePropertyValue(1, a.allocateFrom(I32, property), v.segment());
                v.unset();
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "updateProperty: %s", e);
            }
        }

        void updateState(MemorySegment obj, int state, int value) {
            try (Arena a = Arena.ofConfined()) {
                GValue v = GValue.create(a);
                Gtk.accessibleStateInitValue(state, v);
                setValue(v, value);
                GtkAccessible.wrap(obj).updateStateValue(1, a.allocateFrom(I32, state), v.segment());
                v.unset();
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "updateState: %s", e);
            }
        }

        private void setValue(GValue v, Object value) {
            switch (value) {
                case String s -> v.setString(s);
                case Double d -> v.setDouble(d);
                case Integer i when GObjectFunctions.typeCheckValueHolds(v, booleanType) -> v.setBoolean(i != 0);
                case Integer i when GObjectFunctions.typeCheckValueHolds(v, enumType) -> {
                    v.unset();
                    v.init(intType).setInt(i);
                }
                case Integer i -> v.setInt(i);
                default -> throw new IllegalArgumentException(String.valueOf(value));
            }
        }

        void resetProperty(MemorySegment obj, int property) {
            try {
                GtkAccessible.wrap(obj).resetProperty(property);
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "resetProperty: %s", e);
            }
        }

        void textUpdateCaret(MemorySegment obj) {
            try {
                GtkAccessibleText.wrap(obj).updateCaretPosition();
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "textUpdateCaret: %s", e);
            }
        }

        void textUpdateSelection(MemorySegment obj) {
            try {
                GtkAccessibleText.wrap(obj).updateSelectionBound();
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "textUpdateSelection: %s", e);
            }
        }

        void textUpdateContents(MemorySegment obj, int change, int start, int end) {
            try {
                GtkAccessibleText.wrap(obj).updateContents(change, start, end);
            } catch (RuntimeException e) {
                Gtk4Log.log(LOG, "textUpdateContents: %s", e);
            }
        }

        MemorySegment bytes(String s) {
            return GBytes.create(s.getBytes(StandardCharsets.UTF_8)).getPointer();
        }

        MemorySegment malloc(long size) {
            return GLib.malloc0(size).reinterpret(size);
        }

        MemorySegment hostParentFirstChild(MemorySegment self) {
            if (hostParentFirstChild.address() == 0) {
                return MemorySegment.NULL;
            }
            return GSubclass.callPointerFunction(hostParentFirstChild, self);
        }
    }
}
