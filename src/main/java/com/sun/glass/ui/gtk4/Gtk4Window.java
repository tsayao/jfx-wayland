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

import com.sun.glass.events.MouseEvent;
import com.sun.glass.events.ViewEvent;
import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.Application;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.HeaderButtonMetrics;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.gtk4.wrappers.GObject;
import com.sun.glass.ui.gtk4.wrappers.GdkAppLaunchContext;
import com.sun.glass.ui.gtk4.wrappers.GLib;
import com.sun.glass.ui.gtk4.wrappers.GListModel;
import com.sun.glass.ui.gtk4.wrappers.GdkDisplay;
import com.sun.glass.ui.gtk4.wrappers.GdkMonitor;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkCursor;
import com.sun.glass.ui.gtk4.wrappers.GdkDevice;
import com.sun.glass.ui.gtk4.wrappers.AdwHeaderBar;
import com.sun.glass.ui.gtk4.wrappers.AdwWindow;
import com.sun.glass.ui.gtk4.wrappers.GdkEvent;
import com.sun.glass.ui.gtk4.wrappers.GdkFrameClock;
import com.sun.glass.ui.gtk4.wrappers.GdkPopup;
import com.sun.glass.ui.gtk4.wrappers.GdkRectangle;
import com.sun.glass.ui.gtk4.wrappers.GdkSurface;
import com.sun.glass.ui.gtk4.wrappers.GdkToplevel;
import com.sun.glass.ui.gtk4.wrappers.GdkWaylandToplevel;
import com.sun.glass.ui.gtk4.wrappers.Gtk;
import com.sun.glass.ui.gtk4.wrappers.GtkBox;
import com.sun.glass.ui.gtk4.wrappers.GtkHeaderBar;
import com.sun.glass.ui.gtk4.wrappers.GtkNative;
import com.sun.glass.ui.gtk4.wrappers.GtkOverlay;
import com.sun.glass.ui.gtk4.wrappers.GtkPopover;
import com.sun.glass.ui.gtk4.wrappers.GtkWidget;
import com.sun.glass.ui.gtk4.wrappers.GtkWindow;
import com.sun.glass.ui.gtk4.wrappers.GtkWindowControls;
import com.sun.prism.vk.DmaBufPresenter;
import javafx.geometry.Dimension2D;

import java.lang.annotation.Native;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Glass window backed by a {@code GtkWindow}, or by a {@code GtkPopover}
 * attached to the owner for {@link Window#POPUP} windows.
 * <p>
 * The widget hierarchy is: window (or popover) &rarr; [header] &rarr;
 * container box &rarr; compositor widget (graphics offload &rarr; picture).
 * GTK4 coordinates are logical pixels, and Wayland gives no access to global
 * positions: each toplevel is reported at (0, 0) and popups are positioned
 * relative to their owner.
 */
public class Gtk4Window extends Window implements DmaBufPresenter {

    private static final Gtk4Log.Category SIZING = Gtk4Log.Category.SIZING;
    private static final Gtk4Log.Category STATE = Gtk4Log.Category.STATE;
    private static final Gtk4Log.Category LIFECYCLE = Gtk4Log.Category.LIFECYCLE;
    private static final Gtk4Log.Category POPUP = Gtk4Log.Category.POPUP;
    private static final Gtk4Log.Category HEADERBAR = Gtk4Log.Category.HEADERBAR;
    private static final Gtk4Log.Category FOCUS = Gtk4Log.Category.FOCUS;

    private static final int DEFAULT_WIDTH = 320;
    private static final int DEFAULT_HEIGHT = 200;
    private static final int RESIZE_BORDER_WIDTH = 5;
    // The corner radius of client-side decorations, left out of the blur region
    private static final int CSD_CORNER_RADIUS = Gtk4Application.ADWAITA ? 15 : 12;

    private static final int INITIAL_STATE_MAXIMIZED = 1;
    private static final int INITIAL_STATE_FULLSCREEN = 1 << 1;
    private static final int INITIAL_STATE_ICONIFIED = 1 << 2;

    @Native private static final int HT_UNSPECIFIED = 0;
    @Native private static final int HT_CAPTION = 1;
    @Native private static final int HT_CLIENT = 2;

    private static final AtomicLong PTR_COUNTER = new AtomicLong(1);
    private static final Map<Long, Gtk4Window> windowMap = new HashMap<>();

    private static final boolean DISABLE_GRAB = Boolean.getBoolean("sun.awt.disablegrab")
            || Boolean.getBoolean("glass.disableGrab");

    /** The window that holds the focus grab requested by a popup, if any. */
    private static Gtk4Window grabWindow;
    /** The toplevel that was active last. */
    private static Gtk4Window lastActiveWindow;
    private static boolean cursorVisible = true;

    private long ptr;
    private Gtk4View currentView;

    private Gtk4Window owner;
    private final Set<Gtk4Window> children = new LinkedHashSet<>();

    private GtkWindowFrameType frameType;
    private WindowType windowType;
    private boolean backdrop;

    private Arena windowArena;
    private Gtk4SignalHelper signals;
    private Gtk4SignalHelper surfaceSignals;

    private Gtk4Compositor compositor;
    private GtkWidget viewWidget;
    private GtkBox containerWidget;
    private Gtk4ContentLayout contentLayout;
    private GtkWindow gtkWindow;
    private GtkWidget headerBar;
    private GtkPopover popover;
    private GtkWidget nativeHeaderControlsStart;
    private GtkWidget nativeHeaderControlsEnd;
    private GdkSurface surface;
    private GdkToplevel toplevel;
    private Gtk4BackgroundEffect backgroundEffect;
    private int[] blurRegion;

    // Last values reported to Glass
    private int windowWidth = -1;
    private int windowHeight = -1;
    private int viewWidth = -1;
    private int viewHeight = -1;
    private int viewX;
    private int viewY;

    private int frameX;
    private int frameY;
    private String testTag;
    private Integer requestedX;
    // The last position the application asked for, which chooses the monitor
    // of full screen: Wayland ignores the position itself
    private Integer preferredX;
    private Integer preferredY;
    // The position 0,0 reported to Glass, which comes back as a request
    private boolean originEchoPending;
    private Integer requestedY;
    private boolean testActivated;
    private boolean activatedSinceMap;
    private boolean pendingMinimize;
    private boolean positionRequested;
    private boolean restorePending;
    private boolean floatingShown;
    private int restoreWidth = -1;
    private int restoreHeight = -1;
    private Integer restoreX;
    private Integer restoreY;

    // Popup geometry, relative to the owner's origin
    private int popupX;
    private int popupY;
    private int popupWidth;
    private int popupHeight;
    // Correction for the offset the popover adds to the requested position
    private int popoverOffsetX;
    private int popoverOffsetY;
    private boolean popupPlacementChecked;

    private boolean mapped;
    private boolean destroyed;
    // Hiding the window, which deactivates it at once
    private boolean hiding;
    private boolean boundsSet;
    private boolean resizable = true;
    private boolean enabled = true;
    private boolean minimized;
    private boolean active;
    private int initialState;
    private int minWidth = -1;
    private int minHeight = -1;
    private int sysMinWidth = -1;
    private int sysMinHeight = -1;
    private int maxWidth = -1;
    private int maxHeight = -1;

    private Cursor cursor;
    private GdkCursor edgeCursor;
    private double headerButtonHeightValue = -1;

    public Gtk4Window(Window owner, Screen screen, int styleMask) {
        super(owner, screen, styleMask);
        if (isExtendedWindow()) {
            headerButtonHeightProperty().subscribe(this::onHeaderButtonHeightChanged);
            // The color scheme of the stage, which JavaFX 27 gives here rather
            // than through setDarkFrame
            headerButtonDarkStyleProperty().subscribe(this::setDarkFrame);
        }
    }

    private static GtkWindowFrameType toFrameType(int mask) {
        if ((mask & Window.TRANSPARENT) != 0) {
            return GtkWindowFrameType.TRANSPARENT;
        }
        if ((mask & Window.TITLED) != 0) {
            return GtkWindowFrameType.TITLED;
        }
        if ((mask & Window.EXTENDED) != 0) {
            return GtkWindowFrameType.EXTENDED;
        }
        return GtkWindowFrameType.UNTITLED;
    }

    private static WindowType toWindowType(int mask) {
        if ((mask & Window.POPUP) != 0) {
            return WindowType.POPUP;
        }
        if ((mask & Window.UTILITY) != 0) {
            return WindowType.UTILITY;
        }
        return WindowType.NORMAL;
    }

    // ---------------------------------------------------------------------
    // Accessors used by the view, compositor and IME

    Optional<Gtk4View> getCurrentView() {
        return Optional.ofNullable(currentView);
    }

    Arena getWindowArena() {
        return windowArena;
    }

    GtkWidget getViewWidget() {
        return viewWidget;
    }

    /** The widget that receives key events: the toplevel, or the owner's for popups. */
    GtkWidget getKeyWidget() {
        Gtk4Window w = this;
        while (w != null && w.gtkWindow == null) {
            w = w.owner;
        }
        return w != null ? w.gtkWindow : null;
    }

    Gtk4Compositor getCompositor() {
        return compositor;
    }

    GdkSurface getSurface() {
        return surface;
    }

    GtkWindowFrameType getFrameType() {
        return frameType;
    }

    boolean isMapped() {
        return mapped;
    }

    /**
     * The origin of this window's view in the coordinate space Glass uses for
     * "screen" positions, which is the top-left of the root toplevel.
     */
    int getScreenX() {
        return isPopup() ? (owner != null ? owner.getScreenX() : 0) + popupX : frameX + viewX;
    }

    int getScreenY() {
        return isPopup() ? (owner != null ? owner.getScreenY() : 0) + popupY : frameY + viewY;
    }

    // ---------------------------------------------------------------------
    // Creation and destruction

    @Override
    protected long _createWindow(long ownerPtr, long screenPtr, int mask) {
        windowType = toWindowType(mask);
        frameType = toFrameType(mask);
        backdrop = Gtk4Application.BACKDROP && windowType != WindowType.POPUP
                && frameType != GtkWindowFrameType.TRANSPARENT && Gtk4BackgroundEffect.isSupported();
        owner = ownerPtr != 0L ? windowMap.get(ownerPtr) : null;
        if (owner != null) {
            owner.children.add(this);
        }
        windowArena = Arena.ofShared();
        signals = new Gtk4SignalHelper(windowArena, MethodHandles.lookup());
        createContent();
        if (windowType == WindowType.POPUP) {
            initializePopup();
        } else {
            initializeToplevel(mask);
        }
        ptr = PTR_COUNTER.getAndIncrement();
        windowMap.put(ptr, this);
        Gtk4Log.log(LIFECYCLE, "_createWindow: windowType=%s frameType=%s owner=%s",
                windowType, frameType, owner);
        return ptr;
    }

    /**
     * The widgets of the content: the view, which shows the frames, in its
     * container, which is the host of the accessibility of the scene.
     */
    private void createContent() {
        compositor = new Gtk4Compositor(!(hasBackdrop() && Gtk4BackgroundEffect.isManagedByGtk()));
        viewWidget = compositor.getWidget();
        viewWidget.setFocusable(true);
        viewWidget.setCanFocus(true);
        Gtk4Accessible.hideFromAccessibility(viewWidget);
        containerWidget = Gtk4Accessible.createHost(this);
        if (containerWidget == null) {
            containerWidget = GtkBox.create(Gtk.ORIENTATION_VERTICAL, 0);
        }
        containerWidget.setHExpand(true);
        containerWidget.setVExpand(true);
        containerWidget.append(viewWidget);
        contentLayout = new Gtk4ContentLayout(containerWidget, windowArena);
    }

    private void initializeToplevel(int mask) {
        gtkWindow = Gtk4Application.ADWAITA ? AdwWindow.create() : GtkWindow.create();
        if (gtkWindow instanceof AdwWindow) {
            gtkWindow.setSizeRequest(-1, -1);
        }
        gtkWindow.setTitle("");
        if (owner != null && owner.gtkWindow != null) {
            gtkWindow.setTransientFor(owner.gtkWindow);
        }
        if (owner != null && (mask & Window.MODAL) != 0) {
            gtkWindow.setModal(true);
        }
        configureWindowContent(mask);
        if (hasBackdrop()) {
            gtkWindow.addCssClass("jfx-backdrop");
        }
        connectToplevelSignals();
    }

    private void connectToplevelSignals() {
        signals.connect(gtkWindow, "realize", this, "onRealize", void.class, GtkWidget.class);
        signals.connect(gtkWindow, "unrealize", this, "onUnrealize", void.class, GtkWidget.class);
        signals.connect(gtkWindow, "map", this, "onMap", void.class, GtkWidget.class);
        signals.connect(gtkWindow, "unmap", this, "onUnmap", void.class, GtkWidget.class);
        signals.connect(gtkWindow, "close-request", this, "onCloseRequest", int.class, GtkWindow.class);
        signals.connect(gtkWindow, "notify::is-active", this, "onActiveChanged", void.class, GObject.class);
        signals.connect(gtkWindow, "notify::maximized", this, "onMaximizedChanged", void.class, GObject.class);
        signals.connect(gtkWindow, "notify::fullscreened", this, "onFullscreenedChanged", void.class, GObject.class);
    }

    private void initializePopup() {
        if (owner == null || owner.containerWidget == null) {
            Gtk4Log.log(POPUP, "initializePopup: no owner, popup will not be shown");
            return;
        }
        popover = GtkPopover.create();
        popover.setAutohide(false);
        popover.setHasArrow(false);
        popover.setPosition(Gtk.POS_BOTTOM);
        popover.setCascadePopdown(false);
        popover.addCssClass("jfx-popover");
        popover.setChild(containerWidget);
        popover.setParent(owner.containerWidget);

        signals.connect(popover, "realize", this, "onRealize", void.class, GtkWidget.class);
        signals.connect(popover, "unrealize", this, "onUnrealize", void.class, GtkWidget.class);
        signals.connect(popover, "map", this, "onMap", void.class, GtkWidget.class);
        signals.connect(popover, "unmap", this, "onUnmap", void.class, GtkWidget.class);
    }

    private void configureWindowContent(int mask) {
        Gtk4Log.log(HEADERBAR, "configureWindowContent: frameType=%s", frameType);
        switch (frameType) {
            case TITLED -> configureTitled(mask);
            case UNTITLED -> configureUndecorated("jfx-undecorated");
            case EXTENDED -> configureExtended();
            case TRANSPARENT -> configureUndecorated("jfx-transparent");
        }
    }

    /** A header bar with the buttons the window allows, above the content. */
    private void configureTitled(int mask) {
        gtkWindow.setDeletable((mask & Window.CLOSABLE) != 0);
        if (gtkWindow instanceof AdwWindow adwWindow) {
            AdwHeaderBar adwHeaderBar = AdwHeaderBar.create();
            adwHeaderBar.setDecorationLayout(decorationLayout(mask));
            GtkBox box = GtkBox.create(Gtk.ORIENTATION_VERTICAL, 0);
            box.append(adwHeaderBar);
            box.append(containerWidget);
            adwWindow.setContent(box);
            headerBar = adwHeaderBar;
        } else {
            GtkHeaderBar gtkHeaderBar = GtkHeaderBar.create();
            gtkHeaderBar.setDecorationLayout(decorationLayout(mask));
            gtkWindow.setTitlebar(gtkHeaderBar);
            gtkWindow.setChild(containerWidget);
            headerBar = gtkHeaderBar;
        }
    }

    private void configureUndecorated(String cssClass) {
        gtkWindow.setDecorated(false);
        gtkWindow.addCssClass(cssClass);
        setWindowContent(containerWidget);
    }

    /** The content under the window controls, at the start and the end of its top. */
    private void configureExtended() {
        if (!(gtkWindow instanceof AdwWindow)) {
            // An empty titlebar keeps the client-side shadow and resize area
            // without a visible header
            GtkBox titlebar = GtkBox.create(Gtk.ORIENTATION_HORIZONTAL, 0);
            titlebar.setVisible(false);
            gtkWindow.setTitlebar(titlebar);
        }
        GtkOverlay overlay = GtkOverlay.create();
        overlay.setChild(containerWidget);
        GtkWindowControls start = GtkWindowControls.create(Gtk.PACK_START);
        overlay.addOverlay(headerControls(start, Gtk.ALIGN_START));
        GtkWindowControls end = GtkWindowControls.create(Gtk.PACK_END);
        overlay.addOverlay(headerControls(end, Gtk.ALIGN_END));
        nativeHeaderControlsStart = start;
        nativeHeaderControlsEnd = end;
        applyNativeHeaderControlsPreferences();
        setWindowContent(overlay);
    }

    /**
     * Places window controls in a corner over the content. Themes other than
     * Adwaita, such as Breeze, style the buttons only inside a title bar, so
     * there the controls get one, without its background.
     */
    private static GtkWidget headerControls(GtkWindowControls controls, int halign) {
        controls.setHAlign(halign);
        controls.setVAlign(Gtk.ALIGN_START);
        if (Gtk4Application.ADWAITA) {
            return controls;
        }
        GtkBox titlebar = GtkBox.create(Gtk.ORIENTATION_HORIZONTAL, 0);
        titlebar.addCssClass("titlebar");
        titlebar.addCssClass("jfx-header-controls");
        titlebar.setHAlign(halign);
        titlebar.setVAlign(Gtk.ALIGN_START);
        titlebar.append(controls);
        return titlebar;
    }

    private void setWindowContent(GtkWidget content) {
        if (gtkWindow instanceof AdwWindow adwWindow) {
            adwWindow.setContent(content);
        } else {
            gtkWindow.setChild(content);
        }
    }

    /**
     * The header bar layout that shows only the buttons the window allows.
     */
    private static String decorationLayout(int mask) {
        List<String> buttons = new ArrayList<>();
        if ((mask & Window.MINIMIZABLE) != 0) {
            buttons.add("minimize");
        }
        if ((mask & Window.MAXIMIZABLE) != 0) {
            buttons.add("maximize");
        }
        if ((mask & Window.CLOSABLE) != 0) {
            buttons.add("close");
        }
        return (mask & Window.RIGHT_TO_LEFT) != 0
                ? String.join(",", buttons.reversed()) + ":"
                : ":" + String.join(",", buttons);
    }

    @Override
    protected boolean _close(long ptr) {
        destroy();
        return true;
    }

    private void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        Gtk4Log.log(LIFECYCLE, "destroy");
        if (testTag != null) {
            Gtk4TestSession.cancelPlacement(this);
        }
        windowMap.remove(ptr);
        releaseFocusAndGrab();
        destroyChildren();
        disconnectSignals();
        if (compositor != null) {
            compositor.clearPaintable();
        }
        disposeBackgroundEffect();
        destroyNativeWindow();
        if (owner != null) {
            owner.children.remove(this);
        }
        notifyDestroy();
        releaseNativeResources();
    }

    /** Gives the focus back to the owner, as window managers do, and ends a grab of the window. */
    private void releaseFocusAndGrab() {
        if (lastActiveWindow == this) {
            lastActiveWindow = null;
        }
        if (active && owner != null && owner.gtkWindow != null && owner.gtkWindow.getVisible()) {
            owner.gtkWindow.present();
        }
        if (grabWindow == this) {
            releaseGrab();
        }
    }

    private void destroyChildren() {
        for (Gtk4Window child : new ArrayList<>(children)) {
            if (child.gtkWindow != null) {
                child.gtkWindow.setTransientFor(null);
            }
            child.owner = null;
            child.destroy();
        }
        children.clear();
    }

    private void disconnectSignals() {
        if (surfaceSignals != null) {
            surfaceSignals.disconnectAllSignals();
            surfaceSignals = null;
        }
        if (signals != null) {
            signals.disconnectAllSignals();
            signals.removeAllControllers();
        }
    }

    /**
     * Destroys the GTK window, or removes the popover, later when a press is
     * being delivered to it (GTK grabs the pointer for its widget once the
     * press is delivered), and the compositor of the frames.
     */
    private void destroyNativeWindow() {
        if (popover != null) {
            GtkPopover removed = popover;
            popover = null;
            if (Gtk4View.isDispatchingPress()) {
                removePopoverLater(removed);
            } else {
                removePopover(removed);
            }
        } else if (gtkWindow != null) {
            gtkWindow.destroy();
            gtkWindow = null;
        }
        if (compositor != null) {
            compositor.dispose();
            compositor = null;
        }
    }

    /**
     * Removes the popover once the press being delivered to it is over (GTK
     * grabs the pointer for its widget once the press is delivered). Until
     * then a reference keeps it alive, even when its owner is destroyed
     * meanwhile, and so does the arena of the upcalls of its widgets, such as
     * the layout of its content, which is closed after the removal.
     */
    private void removePopoverLater(GtkPopover removed) {
        removed.ref();
        Arena arena = windowArena;
        windowArena = null;
        Application.invokeLater(() -> {
            removePopover(removed);
            removed.unref();
            if (arena != null) {
                Gtk4Application.closeLater(arena);
            }
        });
    }

    /** The arena of the upcall stubs, once no more signals can be delivered, and the widgets. */
    private void releaseNativeResources() {
        if (windowArena != null) {
            Gtk4Application.closeLater(windowArena);
            windowArena = null;
        }
        signals = null;
        Gtk4Accessible.removeHost(containerWidget);
        containerWidget = null;
        viewWidget = null;
        currentView = null;
    }

    private static void removePopover(GtkPopover popover) {
        popover.popdown();
        popover.setChild(null);
        popover.unparent();
    }

    // ---------------------------------------------------------------------
    // Signal handlers

    void onRealize(GtkWidget widget) {
        surface = widget.getNative().getSurface();
        toplevel = isPopup() ? null : surface.cast(GdkToplevel::wrap);
        Gtk4Log.log(LIFECYCLE, "onRealize: surface=%s", surface.getPointer());
        if (toplevel != null && Gtk4TestSession.isEnabled()) {
            testTag = Gtk4TestSession.newTag();
        }

        surfaceSignals = new Gtk4SignalHelper(windowArena, MethodHandles.lookup());
        GdkFrameClock frameClock = surface.getFrameClock();
        if (!frameClock.isNull()) {
            // Allocations are final after the layout phase
            surfaceSignals.connect(frameClock, "after-paint", this, "onAfterPaint",
                    void.class, GObject.class);
        }
        surfaceSignals.connect(surface, "notify::scale", this, "onScaleChanged", void.class, GObject.class);
        if (toplevel != null) {
            surfaceSignals.connect(surface, "notify::state", this, "onToplevelStateChanged",
                    void.class, GObject.class);
        }
        if (toplevel != null && hasBackdrop()) {
            backgroundEffect = Gtk4BackgroundEffect.create(surface);
            blurRegion = null;
        }
    }

    void onUnrealize(GtkWidget widget) {
        Gtk4Log.log(LIFECYCLE, "onUnrealize");
        disposeBackgroundEffect();
        if (surfaceSignals != null) {
            surfaceSignals.disconnectAllSignals();
            surfaceSignals = null;
        }
        surface = null;
        toplevel = null;
    }

    void onMap(GtkWidget widget) {
        Gtk4Log.log(LIFECYCLE, "onMap: boundsSet=%b initialState=%d", boundsSet, initialState);
        boolean firstMap = !mapped;
        mapped = true;
        activatedSinceMap = false;
        floatingShown = false;
        onScaleChanged(null);
        if (firstMap) {
            // Wayland gives no global positions: toplevels are at the origin of
            // the coordinate space, popups relative to their owner
            notifyMove(getScreenX() - viewX, getScreenY() - viewY);
            if (testTag != null && surface != null) {
                surface.cast(GdkWaylandToplevel::new).setApplicationId(testTag);
            }
        }
        if (testTag != null) {
            Gtk4Log.log(SIZING, "test placement: scheduled %s", testTag);
            Gtk4TestSession.schedulePlacement(this);
        }
        updateGeometry();

        if (isPopup() || !firstMap) {
            return;
        }

        if (initialState != 0) {
            applyInitialState();
        }
        updateWindowConstraints();
    }

    void onUnmap(GtkWidget widget) {
        Gtk4Log.log(LIFECYCLE, "onUnmap");
        getCurrentView().ifPresent(Gtk4View::notifyMouseExitIfEntered);
        if (grabWindow == this) {
            releaseGrab();
        }
    }

    int onCloseRequest(GtkWindow window) {
        Gtk4Log.log(LIFECYCLE, "onCloseRequest: enabled=%b", isEnabled());
        if (isEnabled()) {
            notifyClose();
        }
        // Glass decides whether the window is closed
        return 1;
    }

    void onActiveChanged(GObject source) {
        boolean isActive = gtkWindow != null && gtkWindow.isActive();
        if (isActive == active) {
            return;
        }
        active = isActive;
        if (isActive && activated()) {
            return;
        }
        Gtk4Log.log(FOCUS, "onActiveChanged: active=%b enabled=%b", isActive, isEnabled());
        if (!isActive && grabWindow != null && !isAnyWindowActive()) {
            // The application lost the focus (e.g. a click outside its windows)
            grabWindow.releaseGrab();
        }
        if (isActive && minimized) {
            minimized = false;
            notifyResize(WindowEvent.RESTORE, windowWidth, windowHeight);
        }
        getCurrentView().ifPresent(v -> v.onWindowFocus(isActive));
        notifyFocusChange(isActive);
    }

    /**
     * Records an activation; a minimize asked while the window was shown
     * happens now, as the activation requested at show would restore it.
     *
     * @return whether the window was minimized instead
     */
    private boolean activated() {
        lastActiveWindow = this;
        activatedSinceMap = true;
        if (!pendingMinimize) {
            return false;
        }
        pendingMinimize = false;
        gtkWindow.minimize();
        return true;
    }

    private void notifyFocusChange(boolean isActive) {
        if (hiding) {
            // As with GTK 3, where the focus out arrives after the window is
            // gone, a window that hides does not lose the focus: the scene
            // keeps it (a SwingNode that loses it starts a deactivation timer
            // on Linux, whose events keep AWT, and the application, running)
            return;
        }
        if (!isActive || isEnabled()) {
            notifyFocus(isActive ? WindowEvent.FOCUS_GAINED : WindowEvent.FOCUS_LOST);
        } else {
            notifyFocusDisabled();
        }
    }

    /**
     * The popover of the first popup of this window that shows, which comes
     * after the nodes of the window in the accessible tree.
     */
    MemorySegment firstShownPopover() {
        for (Gtk4Window w : windowMap.values()) {
            if (w.owner == this && w.isPopup() && w.popover != null && w.popover.getVisible()) {
                return w.popover.getPointer();
            }
        }
        return null;
    }

    private static boolean isAnyWindowActive() {
        for (Gtk4Window w : windowMap.values()) {
            if (w.gtkWindow != null && w.gtkWindow.isActive()) {
                return true;
            }
        }
        return false;
    }

    void onMaximizedChanged(GObject source) {
        boolean maximized = gtkWindow.isMaximized();
        Gtk4Log.log(STATE, "onMaximizedChanged: maximized=%b", maximized);
        notifyResize(maximized ? WindowEvent.MAXIMIZE : WindowEvent.RESTORE,
                Math.max(windowWidth, 1), Math.max(windowHeight, 1));
        updateWindowConstraints();
        updateBlurRegion();
        restoreInitialGeometry();
    }

    /**
     * Restores the bounds requested before the window was shown maximized or
     * full screen, which the compositor does not know.
     */
    private void restoreInitialGeometry() {
        if (!restorePending || !isFloating()) {
            return;
        }
        restorePending = false;
        Application.invokeLater(this::applyInitialGeometry);
    }

    private void applyInitialGeometry() {
        if (destroyed || gtkWindow == null || !isFloating()) {
            return;
        }
        Gtk4Log.log(SIZING, "restoreInitialGeometry: %dx%d", restoreWidth, restoreHeight);
        if (restoreWidth > 0 && restoreHeight > 0) {
            gtkWindow.setDefaultSize(-1, -1);
            gtkWindow.setDefaultSize(restoreWidth, restoreHeight);
        }
        if (testTag != null && (restoreX != null || restoreY != null)) {
            requestedX = restoreX;
            requestedY = restoreY;
            Gtk4TestSession.schedulePlacement(this);
        }
    }

    void onFullscreenedChanged(GObject source) {
        boolean fullscreen = gtkWindow.isFullscreen();
        Gtk4Log.log(STATE, "onFullscreenedChanged: fullscreen=%b", fullscreen);
        updateDecorationVisibility(!fullscreen);
        reportFullscreen(fullscreen);
        updateWindowConstraints();
        updateBlurRegion();
        restoreInitialGeometry();
    }

    void onToplevelStateChanged(GObject source) {
        if (toplevel == null) {
            return;
        }
        int state = toplevel.getState();
        Gtk4Log.log(STATE, "onToplevelStateChanged: state=0x%x", state);
        if ((state & Gdk.TOPLEVEL_STATE_MINIMIZED) != 0 && !minimized) {
            minimized = true;
            notifyResize(WindowEvent.MINIMIZE, windowWidth, windowHeight);
        }
        updateBlurRegion();
        if (testTag != null && mapped) {
            Gtk4TestSession.schedulePlacement(this);
        }
    }

    void onScaleChanged(GObject source) {
        if (surface == null) {
            return;
        }
        float scale = Gtk4Application.UI_SCALE_OVERRIDE > 0 ? Gtk4Application.UI_SCALE_OVERRIDE
                : isPopup() && owner != null ? owner.getOutputScaleX() : (float) surface.getScale();
        if (scale > 0 && (scale != getOutputScaleX() || scale != getOutputScaleY())) {
            Gtk4Log.log(SIZING, "onScaleChanged: scale=%.2f", scale);
            notifyScaleChanged(1.0f, 1.0f, scale, scale);
        }
    }

    void onAfterPaint(GObject frameClock) {
        updateGeometry();
    }

    // ---------------------------------------------------------------------
    // Geometry

    /**
     * Reads the allocated sizes and reports the ones that changed.
     */
    private void updateGeometry() {
        if (destroyed || containerWidget == null) {
            return;
        }
        int vw = containerWidget.getWidth();
        int vh = containerWidget.getHeight();
        int ww = isPopup() ? vw : gtkWindow.getWidth();
        int wh = isPopup() ? vh : gtkWindow.getHeight();
        if (vw <= 0 || vh <= 0 || ww <= 0 || wh <= 0) {
            return;
        }
        if (isPopup()) {
            updatePopupPosition();
        } else if (usesNativeHeaderControls()) {
            updateNativeHeaderMetrics();
        }
        int[] origin = isPopup() ? new int[2] : viewOrigin();
        updateViewPosition(origin[0], origin[1]);
        updateWindowSize(ww, wh);
        updateViewSize(vw, vh);
        updateBlurRegion();
    }

    /** Where the view is in the toplevel, below its header bar. */
    private int[] viewOrigin() {
        double[] x = new double[1];
        double[] y = new double[1];
        if (containerWidget.translateCoordinates(gtkWindow, 0, 0, x, y)) {
            return new int[] {(int) Math.round(x[0]), (int) Math.round(y[0])};
        }
        return new int[2];
    }

    private void updateViewPosition(int x, int y) {
        if (x == viewX && y == viewY) {
            return;
        }
        viewX = x;
        viewY = y;
        Gtk4Log.log(SIZING, "updateGeometry: view position %d,%d", viewX, viewY);
        if (currentView != null) {
            currentView.notifyViewPosition(viewX, viewY);
        }
    }

    private void updateWindowSize(int width, int height) {
        if (width == windowWidth && height == windowHeight) {
            return;
        }
        windowWidth = width;
        windowHeight = height;
        floatingShown |= isFloating();
        Gtk4Log.log(SIZING, "updateGeometry: window size %dx%d", width, height);
        notifyWindowResize();
        if (testTag != null && mapped && !isFloating()) {
            // The compositor moved the window as it maximized it or made it
            // full screen, which the state change may have reported too early
            Gtk4TestSession.schedulePlacement(this);
        }
    }

    private void updateViewSize(int width, int height) {
        if (width == viewWidth && height == viewHeight) {
            return;
        }
        viewWidth = width;
        viewHeight = height;
        Gtk4Log.log(SIZING, "updateGeometry: view size %dx%d", width, height);
        notifyViewResize();
        updateWindowConstraints();
    }

    // ---------------------------------------------------------------------
    // Backdrop

    private boolean hasBackdrop() {
        return backdrop;
    }

    @Override
    public boolean isUnifiedWindow() {
        return backdrop || super.isUnifiedWindow();
    }

    /**
     * Blurs the area behind the window, which is the window widget without
     * the client-side shadow, and keeps the rounded corners of a floating
     * decorated window out of the region.
     */
    private void updateBlurRegion() {
        if (backgroundEffect == null || gtkWindow == null) {
            return;
        }
        double[] tx = new double[1];
        double[] ty = new double[1];
        gtkWindow.cast(GtkNative::wrap).getSurfaceTransform(tx, ty);
        int[] region = {
            (int) Math.round(tx[0]), (int) Math.round(ty[0]),
            gtkWindow.getWidth(), gtkWindow.getHeight(), cornerRadius()
        };
        if (region[2] <= 0 || region[3] <= 0 || Arrays.equals(region, blurRegion)) {
            return;
        }
        blurRegion = region;
        backgroundEffect.setBlurRegion(region[0], region[1], region[2], region[3], region[4]);
        // The region takes effect when GTK commits the surface
        gtkWindow.queueDraw();
    }

    private int cornerRadius() {
        if ((frameType != GtkWindowFrameType.TITLED && frameType != GtkWindowFrameType.EXTENDED)
                || !isFloating() || toplevel == null) {
            return 0;
        }
        int tiled = Gdk.TOPLEVEL_STATE_TILED | Gdk.TOPLEVEL_STATE_TOP_TILED | Gdk.TOPLEVEL_STATE_BOTTOM_TILED
                | Gdk.TOPLEVEL_STATE_LEFT_TILED | Gdk.TOPLEVEL_STATE_RIGHT_TILED;
        return (toplevel.getState() & tiled) != 0 ? 0 : CSD_CORNER_RADIUS;
    }

    /**
     * Moves the window to the requested position and reports where the
     * compositor shows it; returns false while it is not shown yet. The
     * window is activated the first time it is placed when it is the last
     * one shown.
     */
    boolean applyTestPlacement(boolean activate) {
        if (destroyed || gtkWindow == null || testTag == null || !gtkWindow.getMapped()) {
            Gtk4Log.log(SIZING, "test placement: dropped, destroyed=%b mapped=%b", destroyed,
                    gtkWindow != null && gtkWindow.getMapped());
            return true;
        }
        int[] frame = requestedX != null || requestedY != null
                ? Gtk4TestSession.moveWindow(testTag, requestedX, requestedY)
                : Gtk4TestSession.getWindowPosition(testTag);
        if (frame == null) {
            Gtk4Log.log(SIZING, "test placement: %s not shown by the compositor yet", testTag);
            return false;
        }
        requestedX = null;
        requestedY = null;
        Gtk4Log.log(SIZING, "test placement: frame at %d,%d", frame[0], frame[1]);
        if (!testActivated && !minimized) {
            testActivated = true;
            if (activate && viewWidget != null && viewWidget.getFocusable()) {
                Gtk4Log.log(FOCUS, "test activation: %s", Gtk4TestSession.activateWindow(testTag));
            }
        }
        if (frame[0] != frameX || frame[1] != frameY) {
            frameX = frame[0];
            frameY = frame[1];
            notifyMove(frameX, frameY);
        }
        return true;
    }

    /**
     * Asks the test session where the window is, which the user may have
     * moved, and reports a change; returns whether the window moved.
     */
    boolean refreshTestPosition() {
        if (destroyed || gtkWindow == null || testTag == null || !gtkWindow.getMapped()) {
            return false;
        }
        int[] frame = Gtk4TestSession.getWindowPosition(testTag);
        if (frame == null || (frame[0] == frameX && frame[1] == frameY)) {
            return false;
        }
        Gtk4Log.log(SIZING, "test position: frame moved to %d,%d", frame[0], frame[1]);
        frameX = frame[0];
        frameY = frame[1];
        notifyMove(frameX, frameY);
        return true;
    }

    private void disposeBackgroundEffect() {
        if (backgroundEffect != null) {
            backgroundEffect.dispose();
            backgroundEffect = null;
        }
        blurRegion = null;
    }

    @Override
    public void setDarkFrame(boolean value) {
        if (gtkWindow != null && frameType == GtkWindowFrameType.EXTENDED) {
            gtkWindow.removeCssClass(value ? "jfx-light" : "jfx-dark");
            gtkWindow.addCssClass(value ? "jfx-dark" : "jfx-light");
        }
        if (hasBackdrop() && currentView != null) {
            currentView.repaintEverything();
        }
    }

    /**
     * Reads where the compositor placed the popup, which may differ from the
     * requested position when the popup does not fit, and reports it.
     */
    private void updatePopupPosition() {
        if (surface == null || owner == null || popover == null) {
            return;
        }
        int[] placed = popupPlacement();
        if (placed == null) {
            return;
        }
        int x = placed[0];
        int y = placed[1];
        if (x == popupX && y == popupY) {
            popupPlacementChecked = true;
            return;
        }
        Gtk4Log.log(POPUP, "updatePopupPosition: requested %d,%d placed %d,%d", popupX, popupY, x, y);
        if (!popupPlacementChecked) {
            // First placement after a request: compensate the popover's own offset
            popupPlacementChecked = true;
            popoverOffsetX -= x - popupX;
            popoverOffsetY -= y - popupY;
            positionPopover();
            return;
        }
        // The compositor moved the popup to keep it on screen
        popupX = x;
        popupY = y;
        notifyMove(getScreenX(), getScreenY());
    }

    /**
     * Where the view of the popup is, relative to the view of its owner: the
     * position of the popup surface, plus where each view is in its surface.
     */
    private int[] popupPlacement() {
        GtkWidget ownerNative = owner.viewWidget != null ? owner.viewWidget.getNative().cast(GtkWidget::new) : null;
        if (ownerNative == null) {
            return null;
        }
        double[] ownerView = viewInSurface(owner.containerWidget, ownerNative, ownerNative.cast(GtkNative::wrap));
        double[] popupView = viewInSurface(containerWidget, popover, popover);
        if (ownerView == null || popupView == null) {
            return null;
        }
        GdkPopup gdkPopup = surface.cast(GdkPopup::wrap);
        return new int[] {
            (int) Math.round(gdkPopup.getPositionX() + popupView[0] - ownerView[0]),
            (int) Math.round(gdkPopup.getPositionY() + popupView[1] - ownerView[1])
        };
    }

    /** The origin of a view in the surface of its native widget, or null. */
    private static double[] viewInSurface(GtkWidget view, GtkWidget nativeWidget, GtkNative gtkNative) {
        double[] x = new double[1];
        double[] y = new double[1];
        if (!view.translateCoordinates(nativeWidget, 0, 0, x, y)) {
            return null;
        }
        double[] tx = new double[1];
        double[] ty = new double[1];
        gtkNative.getSurfaceTransform(tx, ty);
        return new double[] {x[0] + tx[0], y[0] + ty[0]};
    }

    private void notifyWindowResize() {
        if (windowWidth <= 0 || windowHeight <= 0) {
            return;
        }
        int event;
        if (minimized) {
            event = WindowEvent.MINIMIZE;
        } else if (gtkWindow != null && gtkWindow.isMaximized()) {
            event = WindowEvent.MAXIMIZE;
        } else {
            event = WindowEvent.RESIZE;
        }
        notifyResize(event, windowWidth, windowHeight);
    }

    private void notifyViewResize() {
        if (viewWidth > 0 && viewHeight > 0) {
            getCurrentView().ifPresent(v -> v.notifyResize(viewWidth, viewHeight));
        }
    }

    private boolean isFloating() {
        return gtkWindow != null
                && !gtkWindow.isMaximized()
                && !gtkWindow.isFullscreen()
                && !minimized;
    }

    @Override
    protected void _setBounds(long ptr, int x, int y, boolean xSet, boolean ySet,
                              int w, int h, int cw, int ch,
                              float xGravity, float yGravity) {
        Gtk4Log.log(SIZING, "_setBounds: x=%d y=%d xSet=%b ySet=%b w=%d h=%d cw=%d ch=%d",
                x, y, xSet, ySet, w, h, cw, ch);
        boundsSet = true;
        if (isPopup()) {
            setPopupBounds(x, y, xSet, ySet, w, h, cw, ch);
        } else {
            setToplevelBounds(x, y, xSet, ySet, w, h, cw, ch);
        }
    }

    private void setPopupBounds(int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch) {
        // Glass positions are in the root toplevel's space; the popover is
        // positioned relative to its parent, the owner's container
        if (xSet) {
            popupX = x - (owner != null ? owner.getScreenX() : 0);
        }
        if (ySet) {
            popupY = y - (owner != null ? owner.getScreenY() : 0);
        }
        int width = w > 0 ? w : cw;
        int height = h > 0 ? h : ch;
        popupWidth = width > 0 ? width : popupWidth;
        popupHeight = height > 0 ? height : popupHeight;
        Gtk4Log.log(POPUP, "setPopupBounds: %d,%d %dx%d", popupX, popupY, popupWidth, popupHeight);
        boolean sized = popupWidth > 0 && popupHeight > 0;
        if (sized && (popupWidth != viewWidth || popupHeight != viewHeight)) {
            windowWidth = viewWidth = popupWidth;
            windowHeight = viewHeight = popupHeight;
            notifyWindowResize();
            notifyViewResize();
        }
        if (xSet || ySet) {
            popupPlacementChecked = false;
            notifyMove(getScreenX(), getScreenY());
        }
        if (popover != null) {
            if (sized) {
                contentLayout.setSize(popupWidth, popupHeight, popupWidth, popupHeight);
            }
            positionPopover();
        }
    }

    private void positionPopover() {
        // The popover is centered horizontally below the pointing rectangle,
        // which spans the popup width so that the popup starts at popupX
        popover.setPointingTo(new GdkRectangle(popupX, popupY, Math.max(popupWidth, 1), 0));
        popover.setOffset(popoverOffsetX, popoverOffsetY);
    }

    private void setToplevelBounds(int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch) {
        if (gtkWindow == null) {
            return;
        }
        boolean echo = originEchoPending && x == 0 && y == 0;
        originEchoPending = false;
        if (xSet && !echo) {
            preferredX = x;
        }
        if (ySet && !echo) {
            preferredY = y;
        }
        if (xSet || ySet) {
            requestPosition(x, y, xSet, ySet);
        }
        if (mapped && !isFloating()) {
            // Report the current values back so Glass stays in sync
            notifyWindowResize();
            notifyViewResize();
            return;
        }
        resizeToplevel(x, y, xSet, ySet, w, h, cw, ch);
    }

    /**
     * Moves a toplevel in a test session; elsewhere Wayland clients cannot
     * position their toplevels, which stay at the origin for Glass.
     */
    private void requestPosition(int x, int y, boolean xSet, boolean ySet) {
        if (!Gtk4TestSession.isEnabled()) {
            originEchoPending = x != 0 || y != 0;
            notifyMove(0, 0);
            return;
        }
        positionRequested = true;
        requestedX = xSet ? Integer.valueOf(x) : requestedX;
        requestedY = ySet ? Integer.valueOf(y) : requestedY;
        frameX = xSet ? x : frameX;
        frameY = ySet ? y : frameY;
        notifyMove(frameX, frameY);
        if (mapped && testTag != null) {
            Gtk4TestSession.schedulePlacement(this);
        }
    }

    /**
     * Sets the default size of a floating toplevel from the window size, or
     * from the content size plus the decorations, within the size limits.
     */
    private void resizeToplevel(int x, int y, boolean xSet, boolean ySet, int w, int h, int cw, int ch) {
        int decorationWidth = decorationWidth();
        int decorationHeight = decorationHeight();
        // The default size is the size of the window (including the header bar)
        int newW = w > 0 ? w : (cw > 0 ? cw + decorationWidth : -1);
        int newH = h > 0 ? h : (ch > 0 ? ch + decorationHeight : -1);
        if (newW <= 0 && newH <= 0) {
            return;
        }
        int[] current = currentDefaultSize();
        newW = newW > 0 ? newW : current[0];
        newH = newH > 0 ? newH : current[1];
        if (!mapped) {
            restoreX = xSet ? Integer.valueOf(x) : restoreX;
            restoreY = ySet ? Integer.valueOf(y) : restoreY;
        }
        int clampedW = clamp(newW, minWidth, maxWidth);
        int clampedH = clamp(newH, minHeight, maxHeight);
        Gtk4Log.log(SIZING, "setToplevelBounds: default size %dx%d", clampedW, clampedH);
        int contentW = cw > 0 && clampedW == newW ? cw : clampedW - decorationWidth;
        int contentH = ch > 0 && clampedH == newH ? ch : clampedH - decorationHeight;
        applyDefaultSize(clampedW, clampedH, contentW, contentH);
    }

    /** The size of the window, or else its default size. */
    private int[] currentDefaultSize() {
        int[] width = new int[1];
        int[] height = new int[1];
        gtkWindow.getDefaultSize(width, height);
        return new int[] {windowWidth > 0 ? windowWidth : width[0], windowHeight > 0 ? windowHeight : height[0]};
    }

    private void applyDefaultSize(int width, int height, int contentW, int contentH) {
        if (!resizable || !isEnabled()) {
            // A fixed size window is sized to its content
            contentLayout.setSize(contentW, contentH, contentW, contentH);
        }
        gtkWindow.setDefaultSize(width, height);
        if (!mapped) {
            restoreWidth = width;
            restoreHeight = height;
        }
        if (!mapped && !positionRequested && Gtk4TestSession.isEnabled()) {
            placeCentered(width, height);
        }
        reportDefaultSize(width, height, contentW, contentH);
    }

    /**
     * Reports a size that GTK will not report: the same as now, or one asked
     * before the window is shown.
     */
    private void reportDefaultSize(int width, int height, int contentW, int contentH) {
        if (mapped && width == windowWidth && height == windowHeight) {
            notifyWindowResize();
            notifyViewResize();
        }
        if ((viewWidth <= 0 || viewHeight <= 0) && contentW > 0 && contentH > 0 && currentView != null) {
            currentView.notifyResize(contentW, contentH);
        }
        if (!mapped && windowWidth <= 0 && windowHeight <= 0) {
            // The compositor sizes the window once it shows: until then the
            // stage has the size asked for, as with the other platforms, which
            // applications such as FXGL read right after show()
            notifyResize(WindowEvent.RESIZE, width, height);
        }
    }

    /**
     * Requests the position at which Glass centers a window that was not
     * positioned, and reports it before the window is shown, as an X11
     * window manager would.
     */
    private void placeCentered(int width, int height) {
        int[] area = Gtk4TestSession.getPrimaryWorkArea();
        if (area == null) {
            return;
        }
        requestedX = area[0] + (area[2] - width) / 2;
        requestedY = area[1] + (area[3] - height) / 3;
        frameX = requestedX;
        frameY = requestedY;
        notifyMove(frameX, frameY);
    }

    private static int clamp(int size, int min, int max) {
        if (max > 0 && size > max) {
            size = max;
        }
        return min > 0 && size < min ? min : size;
    }

    private int decorationWidth() {
        return windowWidth > 0 && viewWidth > 0 ? windowWidth - viewWidth : 0;
    }

    /**
     * The height of the decorations, measured from the header bar before the
     * window has been allocated.
     */
    private int decorationHeight() {
        if (windowHeight > 0 && viewHeight > 0) {
            return windowHeight - viewHeight;
        }
        if (headerBar != null && headerBar.getVisible()) {
            int[] natural = new int[1];
            headerBar.measure(Gtk.ORIENTATION_VERTICAL, -1, null, natural, null, null);
            return natural[0];
        }
        return 0;
    }

    @Override
    protected void _updateViewSize(long ptr) {
        notifyViewResize();
    }

    /**
     * Applies the minimum size, or fixes the size when the window is not
     * resizable or disabled. GTK4 has no geometry hints: the minimum is the
     * size request of the content, and a window that is not resizable takes
     * the size of its content.
     */
    private void updateWindowConstraints() {
        if (isPopup() || gtkWindow == null || !mapped || !isFloating()) {
            return;
        }

        boolean canResize = resizable && isEnabled();
        if (canResize) {
            applyResizableContentSize();
        } else if (viewWidth > 0 && viewHeight > 0) {
            contentLayout.setSize(viewWidth, viewHeight, viewWidth, viewHeight);
        }
        gtkWindow.setResizable(canResize);
        Gtk4Log.log(SIZING, "updateWindowConstraints: resizable=%b min=%dx%d", canResize, minWidth, minHeight);
    }

    private void applyResizableContentSize() {
        int w = Math.max(contentSize(minWidth, true), sysMinWidth);
        int h = Math.max(contentSize(minHeight, false), sysMinHeight);
        contentLayout.setSize(w, h, Math.max(viewWidth, 1), Math.max(viewHeight, 1));
    }

    /**
     * Converts a window dimension to the corresponding view dimension.
     */
    private int contentSize(int windowSize, boolean horizontal) {
        if (windowSize <= 0) {
            return -1;
        }
        int decoration = horizontal ? decorationWidth() : decorationHeight();
        return Math.max(windowSize - decoration, 1);
    }

    @Override
    protected boolean _setMinimumSize(long ptr, int width, int height) {
        Gtk4Log.log(SIZING, "_setMinimumSize: %dx%d", width, height);
        minWidth = width;
        minHeight = height;
        updateWindowConstraints();
        enforceSizeLimits();
        return true;
    }

    @Override
    protected boolean _setMaximumSize(long ptr, int width, int height) {
        Gtk4Log.log(SIZING, "_setMaximumSize: %dx%d", width, height);
        maxWidth = width;
        maxHeight = height;
        enforceSizeLimits();
        return true;
    }

    private void enforceSizeLimits() {
        if (isPopup() || gtkWindow == null || windowWidth <= 0 || windowHeight <= 0
                || (mapped && !isFloating())) {
            return;
        }
        int w = clamp(windowWidth, minWidth, maxWidth);
        int h = clamp(windowHeight, minHeight, maxHeight);
        if (w != windowWidth || h != windowHeight) {
            setToplevelBounds(0, 0, false, false, w, h, -1, -1);
        }
    }

    private void setSystemMinimumSize(int width, int height) {
        sysMinWidth = width;
        sysMinHeight = height;
        updateWindowConstraints();
    }

    @Override
    protected boolean _setResizable(long ptr, boolean resizable) {
        this.resizable = resizable;
        if (gtkWindow != null && !mapped) {
            gtkWindow.setResizable(resizable);
        }
        updateWindowConstraints();
        return true;
    }

    // ---------------------------------------------------------------------
    // Visibility and state

    @Override
    protected boolean _setVisible(long ptr, boolean visible) {
        Gtk4Log.log(LIFECYCLE, "_setVisible: visible=%b", visible);
        if (isPopup()) {
            return popover != null && setPopupVisible(visible);
        }
        if (gtkWindow == null) {
            return false;
        }
        if (visible) {
            if (!boundsSet && !mapped) {
                gtkWindow.setDefaultSize(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            }
            gtkWindow.setVisible(true);
            gtkWindow.present();
            viewWidget.grabFocus();
        } else {
            getCurrentView().ifPresent(Gtk4View::notifyMouseExitIfEntered);
            hiding = true;
            try {
                gtkWindow.setVisible(false);
            } finally {
                hiding = false;
            }
        }
        return visible;
    }

    /**
     * Pops the popover up or down; down later when a press is being
     * delivered to it, as GTK grabs the pointer for its widget once the
     * press is delivered.
     */
    private boolean setPopupVisible(boolean visible) {
        if (visible) {
            positionPopover();
            popover.popup();
            Gtk4Accessible.windowShown(this);
        } else if (Gtk4View.isDispatchingPress()) {
            GtkPopover hidden = popover;
            Application.invokeLater(() -> {
                if (popover == hidden && !isVisible()) {
                    hidden.popdown();
                }
            });
        } else {
            popover.popdown();
        }
        return visible;
    }

    @Override
    protected boolean _minimize(long ptr, boolean minimize) {
        if (gtkWindow == null) {
            return false;
        }
        if (!mapped) {
            initialState = minimize
                    ? (initialState | INITIAL_STATE_ICONIFIED)
                    : (initialState & ~INITIAL_STATE_ICONIFIED);
        } else if (minimize) {
            minimizeMapped();
        } else {
            restoreMinimized();
        }
        return minimize;
    }

    /**
     * Minimizes a shown window, after its first activation, which would
     * restore it. Wayland does not report the minimized state, so it is
     * assumed until the window is activated again.
     */
    private void minimizeMapped() {
        if (!activatedSinceMap && gtkWindow.getVisible()) {
            pendingMinimize = true;
        } else {
            gtkWindow.minimize();
        }
        if (!minimized) {
            minimized = true;
            notifyResize(WindowEvent.MINIMIZE, windowWidth, windowHeight);
        }
    }

    private void restoreMinimized() {
        pendingMinimize = false;
        gtkWindow.unminimize();
        activate();
        if (minimized) {
            minimized = false;
            notifyResize(WindowEvent.RESTORE, windowWidth, windowHeight);
        }
    }

    @Override
    protected boolean _maximize(long ptr, boolean maximize, boolean wasMaximized) {
        if (gtkWindow == null) {
            return false;
        }
        if (!mapped) {
            initialState = maximize
                    ? (initialState | INITIAL_STATE_MAXIMIZED)
                    : (initialState & ~INITIAL_STATE_MAXIMIZED);
            return maximize;
        }
        // Constraints only allow a fixed size; lift them before maximizing
        if (maximize) {
            restorePending |= !floatingShown;
            applyResizableContentSize();
            gtkWindow.setResizable(true);
            gtkWindow.maximize();
        } else {
            gtkWindow.unmaximize();
        }
        return maximize;
    }

    /**
     * Tells JavaFX whether the window is in full screen, once per change.
     * A request is reported when it is made, before the compositor applies
     * it: JavaFX asks to leave full screen only when it knows the window is in
     * it, so a request to leave made before the compositor confirmed the
     * entry would otherwise be dropped.
     */
    void reportFullscreen(boolean fullscreen) {
        if (fullscreen != fullscreenReported) {
            fullscreenReported = fullscreen;
            getCurrentView().ifPresent(v -> v.notifyViewEvent(
                    fullscreen ? ViewEvent.FULLSCREEN_ENTER : ViewEvent.FULLSCREEN_EXIT));
        }
    }

    private boolean fullscreenReported;

    void enterFullscreen() {
        if (gtkWindow == null) {
            return;
        }
        if (!mapped) {
            initialState |= INITIAL_STATE_FULLSCREEN;
            return;
        }
        reportFullscreen(true);
        restorePending |= !floatingShown;
        applyResizableContentSize();
        gtkWindow.setResizable(true);
        GdkMonitor monitor = preferredMonitor();
        if (monitor != null) {
            Gtk4Log.log(STATE, "enterFullscreen: on the monitor at %d,%d", preferredX, preferredY);
            gtkWindow.fullscreenOnMonitor(monitor);
            monitor.unref();
        } else {
            gtkWindow.fullscreen();
        }
    }

    /**
     * The monitor that contains the middle of the window at the position the
     * application asked for, or null when it asked for none.
     */
    private GdkMonitor preferredMonitor() {
        if (preferredX == null || preferredY == null) {
            return null;
        }
        GdkDisplay display = GdkDisplay.getDefault();
        if (display.isNull()) {
            return null;
        }
        int cx = preferredX + Math.max(windowWidth, 0) / 2;
        int cy = preferredY + Math.max(windowHeight, 0) / 2;
        GListModel monitors = display.getMonitors();
        GdkRectangle g = new GdkRectangle(0, 0, 0, 0);
        GdkMonitor found = null;
        for (int i = 0; i < monitors.getNItems() && found == null; i++) {
            GdkMonitor m = monitors.getObject(i).cast(GdkMonitor::new);
            m.getGeometry(g);
            if (cx >= g.x() && cx < g.x() + g.width() && cy >= g.y() && cy < g.y() + g.height()) {
                found = m;
            } else {
                m.unref();
            }
        }
        return found;
    }

    void exitFullscreen() {
        if (gtkWindow == null) {
            return;
        }
        if (!mapped) {
            initialState &= ~INITIAL_STATE_FULLSCREEN;
            return;
        }
        reportFullscreen(false);
        gtkWindow.unfullscreen();
    }

    private void applyInitialState() {
        Gtk4Log.log(STATE, "applyInitialState: 0x%x", initialState);
        int state = initialState;
        initialState = 0;
        if ((state & INITIAL_STATE_FULLSCREEN) != 0) {
            enterFullscreen();
        }
        if ((state & INITIAL_STATE_MAXIMIZED) != 0) {
            _maximize(ptr, true, false);
        }
        if ((state & INITIAL_STATE_ICONIFIED) != 0) {
            _minimize(ptr, true);
        }
    }

    // ---------------------------------------------------------------------
    // Focus

    @Override
    protected boolean _requestFocus(long ptr, int event) {
        Gtk4Log.log(FOCUS, "_requestFocus");
        if (gtkWindow == null || !gtkWindow.getVisible()) {
            return false;
        }
        activate();
        viewWidget.grabFocus();
        return true;
    }

    /**
     * Raises and focuses the window. Wayland gives the focus to another
     * window only with an activation token of the focused one, which the
     * launch context of the display requests, as GTK does to start
     * applications.
     */
    private void activate() {
        if (!gtkWindow.isActive()) {
            if (Gtk4TestSession.isEnabled() && testTag != null) {
                // The compositor of the test session does not honor the tokens of a
                // client without input; it activates the window itself, as at show
                Gtk4Log.log(FOCUS, "test activation: %s", Gtk4TestSession.activateWindow(testTag));
                return;
            }
            String token = activationToken();
            Gtk4Log.log(FOCUS, "activate: token=%s", token);
            if (token != null) {
                gtkWindow.setStartupId(token);
            }
        }
        gtkWindow.present();
    }

    /**
     * A press in a window that a modal window disables, which brings the
     * modal window forward.
     */
    void notifyBlockedPress() {
        notifyFocusDisabled();
    }

    private static MethodHandle startupNotifyId;

    private static String activationToken() {
        try {
            if (startupNotifyId == null) {
                SymbolLookup gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", Arena.global());
                startupNotifyId = Linker.nativeLinker().downcallHandle(
                        gio.find("g_app_launch_context_get_startup_notify_id").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
            }
            GdkAppLaunchContext context = GdkDisplay.getDefault().getAppLaunchContext();
            if (context == null || context.isNull()) {
                return null;
            }
            try {
                MemorySegment id = (MemorySegment) startupNotifyId.invokeExact(context.getPointer(),
                        MemorySegment.NULL, MemorySegment.NULL);
                if (id.address() == 0) {
                    return null;
                }
                String token = id.reinterpret(Long.MAX_VALUE).getString(0);
                GLib.free(id);
                return token;
            } finally {
                context.unref();
            }
        } catch (Throwable t) {
            Gtk4Log.log(FOCUS, "Cannot get an activation token: %s", t);
            return null;
        }
    }

    @Override
    protected void _setFocusable(long ptr, boolean isFocusable) {
        // GTK4 offers no way to make a toplevel reject the focus
        if (viewWidget != null) {
            viewWidget.setFocusable(isFocusable);
        }
    }

    @Override
    protected boolean _grabFocus(long ptr) {
        // Wayland has no global pointer grab; the grab is released when the
        // application loses the focus
        Gtk4Log.log(FOCUS, "_grabFocus: disabled=%b", DISABLE_GRAB);
        if (!DISABLE_GRAB) {
            grabWindow = this;
        }
        return true;
    }

    @Override
    protected void _ungrabFocus(long ptr) {
        Gtk4Log.log(FOCUS, "_ungrabFocus");
        if (grabWindow == this) {
            grabWindow = null;
        }
    }

    private void releaseGrab() {
        Gtk4Log.log(FOCUS, "ungrabFocus");
        grabWindow = null;
        notifyFocusUngrab();
    }

    @Override
    protected void _setEnabled(long ptr, boolean enabled) {
        Gtk4Log.log(FOCUS, "_setEnabled: %b", enabled);
        boolean reenabled = enabled && !this.enabled;
        this.enabled = enabled;
        updateWindowConstraints();
        if (reenabled && mapped && active) {
            notifyFocus(WindowEvent.FOCUS_GAINED);
        }
    }

    // ---------------------------------------------------------------------
    // Attributes

    @Override
    protected boolean _setTitle(long ptr, String title) {
        if (gtkWindow != null) {
            gtkWindow.setTitle(title != null ? title : "");
        }
        return true;
    }

    @Override
    protected void _setLevel(long ptr, int level) {
        // Wayland does not support keeping windows above others
    }

    @Override
    protected void _setAlpha(long ptr, float alpha) {
        if (gtkWindow != null) {
            gtkWindow.setOpacity(alpha);
        } else if (popover != null) {
            popover.setOpacity(alpha);
        }
    }

    @Override
    protected boolean _setBackground(long ptr, float r, float g, float b) {
        return true;
    }

    @Override
    protected void _setIcon(long ptr, Pixels pixels) {
        // Wayland toplevels take their icon from the desktop file
    }

    @Override
    protected boolean _setMenubar(long ptr, long menubarPtr) {
        return true;
    }

    @Override
    protected void _toFront(long ptr) {
        if (gtkWindow != null && gtkWindow.getVisible()) {
            activate();
        }
    }

    @Override
    protected void _toBack(long ptr) {
        if (toplevel != null) {
            toplevel.lower();
        }
    }

    @Override
    protected boolean _setView(long ptr, View view) {
        Gtk4Log.log(LIFECYCLE, "_setView: view=%s", view);
        if (currentView != null) {
            currentView.notifyMouse(MouseEvent.EXIT, MouseEvent.BUTTON_NONE, 0, 0, 0, 0, 0, false, false);
            currentView.detach();
        }

        currentView = view instanceof Gtk4View gtk4View ? gtk4View : null;
        if (currentView != null && signals != null) {
            currentView.attach(this, viewWidget, signals);
            if (viewWidth > 0 && viewHeight > 0) {
                currentView.notifyViewPosition(viewX, viewY);
                currentView.notifyResize(viewWidth, viewHeight);
            }
        }
        return true;
    }

    @Override
    protected void _requestInput(long ptr, String text, int type, double width, double height,
                                 double Mxx, double Mxy, double Mxz, double Mxt,
                                 double Myx, double Myy, double Myz, double Myt,
                                 double Mzx, double Mzy, double Mzz, double Mzt) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void _releaseInput(long ptr) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public long getNativeWindow() {
        return 0L;
    }

    @Override
    public long getRawHandle() {
        return ptr;
    }

    // ---------------------------------------------------------------------
    // Cursor

    @Override
    protected void _setCursor(long ptr, Cursor cursor) {
        this.cursor = cursor;
        applyCursor();
    }

    private void applyCursor() {
        if (viewWidget == null) {
            return;
        }
        GdkCursor gdkCursor;
        if (!cursorVisible) {
            gdkCursor = Gtk4Cursor.hiddenCursor();
        } else if (edgeCursor != null) {
            gdkCursor = edgeCursor;
        } else {
            gdkCursor = cursor instanceof Gtk4Cursor c ? c.getGdkCursor() : null;
        }
        viewWidget.setCursor(gdkCursor);
    }

    static void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        for (Gtk4Window w : windowMap.values()) {
            w.applyCursor();
        }
    }

    // ---------------------------------------------------------------------
    // Extended windows

    @Override
    public boolean isComposited() {
        // Every window shows the dma-bufs of the pipelines through its compositor
        return compositor != null;
    }

    @Override
    public boolean publishDmaBuf(int fd, int stride, int offset, int fourcc, long modifier,
                                 int width, int height, Runnable release) {
        return compositor != null
                && compositor.publishDmaBuf(fd, stride, offset, fourcc, modifier, width, height, release);
    }

    @Override
    public void requestRepaint() {
        Application.invokeLater(() -> getCurrentView().ifPresent(Gtk4View::repaint));
    }

    /**
     * Handles a button press for an extended window: double-clicking the drag
     * area toggles maximization, pressing on the border starts a resize, pressing
     * on the drag area starts a move and the secondary button there opens the
     * window menu.
     *
     * @return whether the press was consumed
     */
    boolean handleExtendedButtonPress(GdkEvent event, int button, int nPress, double x, double y) {
        if (frameType != GtkWindowFrameType.EXTENDED || toplevel == null
                || gtkWindow == null || gtkWindow.isFullscreen()) {
            return false;
        }
        int hitTest = nonClientHitTest(x, y);
        Gtk4Log.log(HEADERBAR, "handleExtendedButtonPress: button=%d nPress=%d at %.1f,%.1f hitTest=%d",
                button, nPress, x, y, hitTest);
        if (nPress == 2 && button == Gdk.BUTTON_PRIMARY) {
            return doubleClick(hitTest);
        }
        if (nPress != 1) {
            return false;
        }
        if (button == Gdk.BUTTON_PRIMARY) {
            return beginResizeOrMove(event, hitTest, x, y);
        }
        if (button == Gdk.BUTTON_SECONDARY && hitTest == HT_CAPTION) {
            toplevel.showWindowMenu(event);
            return true;
        }
        return false;
    }

    /** A double click on the caption maximizes or restores the window. */
    private boolean doubleClick(int hitTest) {
        if (resizable && hitTest == HT_CAPTION) {
            if (gtkWindow.isMaximized()) {
                gtkWindow.unmaximize();
            } else {
                _maximize(ptr, true, false);
            }
        }
        return hitTest == HT_CAPTION;
    }

    /**
     * A press on an edge resizes the window, and one on the caption moves it,
     * through the compositor.
     */
    private boolean beginResizeOrMove(GdkEvent event, int hitTest, double x, double y) {
        GdkDevice device = event.getDevice();
        int time = event.getTime();
        int edge = resizable && isFloating() ? getWindowEdge(x, y) : -1;
        if (edge >= 0 && (edge != Gdk.SURFACE_EDGE_NORTH || hitTest != HT_CLIENT)) {
            notifyFocusUngrab();
            toplevel.beginResize(edge, device, Gdk.BUTTON_PRIMARY, x + viewX, y + viewY, time);
            return true;
        }
        if (hitTest == HT_CAPTION) {
            Gtk4Log.log(HEADERBAR, "beginMove: at %.1f,%.1f time=%d", x + viewX, y + viewY, time);
            notifyFocusUngrab();
            toplevel.beginMove(device, Gdk.BUTTON_PRIMARY, x + viewX, y + viewY, time);
            return true;
        }
        return false;
    }

    /**
     * Updates the edge cursor of an extended window.
     *
     * @return whether the pointer is in the client area
     */
    boolean updateExtendedCursor(double x, double y) {
        if (frameType != GtkWindowFrameType.EXTENDED || !resizable || !isFloating()) {
            setEdgeCursor(null);
            return true;
        }
        int edge = getWindowEdge(x, y);
        if (edge < 0 || (edge == Gdk.SURFACE_EDGE_NORTH && nonClientHitTest(x, y) == HT_CLIENT)) {
            setEdgeCursor(null);
            return true;
        }
        setEdgeCursor(Gtk4Cursor.edgeCursor(edge));
        return false;
    }

    private void setEdgeCursor(GdkCursor cursor) {
        if (edgeCursor != cursor) {
            edgeCursor = cursor;
            applyCursor();
        }
    }

    private int getWindowEdge(double x, double y) {
        int w = viewWidth;
        int h = viewHeight;
        if (x <= RESIZE_BORDER_WIDTH) {
            if (y <= 2 * RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_NORTH_WEST;
            if (y >= h - 2 * RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_SOUTH_WEST;
            return Gdk.SURFACE_EDGE_WEST;
        }
        if (x >= w - RESIZE_BORDER_WIDTH) {
            if (y <= 2 * RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_NORTH_EAST;
            if (y >= h - 2 * RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_SOUTH_EAST;
            return Gdk.SURFACE_EDGE_EAST;
        }
        if (y <= RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_NORTH;
        if (y >= h - RESIZE_BORDER_WIDTH) return Gdk.SURFACE_EDGE_SOUTH;
        return -1;
    }

    /**
     * Classifies the window region at the specified view coordinate.
     */
    private int nonClientHitTest(double x, double y) {
        if (view == null || view.isInFullscreen() || !isExtendedWindow()) {
            return HT_CLIENT;
        }

        HeaderButtonMetrics metrics = headerButtonMetrics.get();
        if (metrics != null && isInsideButtonInsets(x, y, metrics)) {
            return HT_CLIENT;
        }

        View.EventHandler eventHandler = view.getEventHandler();
        if (eventHandler == null) {
            return HT_CLIENT;
        }

        return switch (eventHandler.pickHeaderArea(x, y)) {
            case UNSPECIFIED -> HT_UNSPECIFIED;
            case DRAGBAR -> HT_CAPTION;
            case null, default -> HT_CLIENT;
        };
    }

    private boolean isInsideButtonInsets(double x, double y, HeaderButtonMetrics metrics) {
        Dimension2D left = metrics.leftInset();
        if (x >= 0 && x < left.getWidth() && y >= 0 && y < left.getHeight()) {
            return true;
        }
        Dimension2D right = metrics.rightInset();
        double startX = viewWidth - right.getWidth();
        return x >= startX && x < viewWidth && y >= 0 && y < right.getHeight();
    }

    private void onHeaderButtonHeightChanged(Number height) {
        Gtk4Log.log(HEADERBAR, "onHeaderButtonHeightChanged: %s", height);
        headerButtonHeightValue = height.doubleValue();
        headerButtonOverlay.set(null);
        applyNativeHeaderControlsPreferences();
        updateNativeHeaderMetrics();
    }

    private boolean usesNativeHeaderControls() {
        return nativeHeaderControlsStart != null && nativeHeaderControlsEnd != null;
    }

    private void applyNativeHeaderControlsPreferences() {
        if (!usesNativeHeaderControls()) {
            return;
        }
        boolean visible = headerButtonHeightValue != 0;
        nativeHeaderControlsStart.setVisible(visible);
        nativeHeaderControlsEnd.setVisible(visible);
        int prefHeight = visible && headerButtonHeightValue > 0 ? (int) Math.ceil(headerButtonHeightValue) : -1;
        nativeHeaderControlsStart.setSizeRequest(-1, prefHeight);
        nativeHeaderControlsEnd.setSizeRequest(-1, prefHeight);
    }

    private void updateDecorationVisibility(boolean visible) {
        if (headerBar != null) {
            headerBar.setVisible(visible);
        }
        if (usesNativeHeaderControls()) {
            boolean controlsVisible = visible && headerButtonHeightValue != 0;
            nativeHeaderControlsStart.setVisible(controlsVisible);
            nativeHeaderControlsEnd.setVisible(controlsVisible);
        }
    }

    private void updateNativeHeaderMetrics() {
        if (!usesNativeHeaderControls() || headerButtonHeightValue == 0) {
            setSystemMinimumSize(-1, -1);
            setHeaderButtonMetrics(HeaderButtonMetrics.EMPTY);
            return;
        }

        int startWidth = nativeHeaderControlsStart.getWidth();
        int startHeight = nativeHeaderControlsStart.getHeight();
        int endWidth = nativeHeaderControlsEnd.getWidth();
        int endHeight = nativeHeaderControlsEnd.getHeight();
        boolean startVisible = startWidth > 0 && startHeight > 0;
        boolean endVisible = endWidth > 0 && endHeight > 0;

        if (!startVisible && !endVisible) {
            setHeaderButtonMetrics(HeaderButtonMetrics.EMPTY);
            return;
        }

        Dimension2D left = startVisible ? new Dimension2D(startWidth, startHeight) : new Dimension2D(0, 0);
        Dimension2D right = endVisible ? new Dimension2D(endWidth, endHeight) : new Dimension2D(0, 0);
        double minHeight = headerButtonHeightValue > 0
                ? headerButtonHeightValue : Math.max(left.getHeight(), right.getHeight());
        setSystemMinimumSize((int) (left.getWidth() + right.getWidth()),
                (int) Math.max(left.getHeight(), right.getHeight()));
        setHeaderButtonMetrics(new HeaderButtonMetrics(left, right, minHeight));
    }

    private void setHeaderButtonMetrics(HeaderButtonMetrics metrics) {
        if (!metrics.equals(headerButtonMetrics.get())) {
            headerButtonMetrics.set(metrics);
        }
    }

    // ---------------------------------------------------------------------
    // Robot support

    int getViewWidth() {
        return viewWidth;
    }

    // Outside a test session the compositor does not tell where a window is: the
    // robot learns it from the position of its pointer in the view
    private boolean positionKnown;

    /**
     * Whether the screen position of the window is known: in a test session,
     * or once the robot pointer was over it.
     */
    boolean isPositionKnown() {
        return positionKnown || Gtk4TestSession.isEnabled();
    }

    /**
     * Sets the screen position of the view, learned from the robot: its
     * pointer at a screen point is at a point of the view.
     */
    void setViewScreenPosition(int x, int y) {
        positionKnown = true;
        int fx = x - viewX;
        int fy = y - viewY;
        if (fx != frameX || fy != frameY) {
            Gtk4Log.log(SIZING, "robot: frame at %d,%d", fx, fy);
            frameX = fx;
            frameY = fy;
            notifyMove(frameX, frameY);
        }
    }

    /**
     * The toplevels shown, the active one first, whose view contains a
     * screen point at their known or assumed position.
     */
    static List<Gtk4Window> getToplevelsAt(int x, int y) {
        Gtk4Window active = getActiveWindow();
        List<Gtk4Window> found = new ArrayList<>();
        for (Gtk4Window w : windowMap.values()) {
            if (!w.isPopup() && w.isShowing() && w.containsScreenPoint(x, y)) {
                if (w == active) {
                    found.addFirst(w);
                } else {
                    found.add(w);
                }
            }
        }
        return found;
    }

    boolean isShowingToplevel() {
        return !isPopup() && isShowing();
    }

    int getViewHeight() {
        return viewHeight;
    }

    private boolean isShowing() {
        return mapped && !destroyed && (popover != null ? popover.getVisible()
                : gtkWindow != null && gtkWindow.getVisible());
    }

    private boolean containsScreenPoint(int x, int y) {
        int sx = getScreenX();
        int sy = getScreenY();
        return x >= sx && y >= sy && x < sx + viewWidth && y < sy + viewHeight;
    }

    /**
     * The toplevel that receives keyboard input.
     */
    static Gtk4Window getActiveWindow() {
        for (Gtk4Window w : windowMap.values()) {
            if (w.active) {
                return w;
            }
        }
        return lastActiveWindow != null && lastActiveWindow.isShowing() ? lastActiveWindow : null;
    }

    /**
     * The window whose view is at the given position, in the coordinate space
     * of the active toplevel: its popups, topmost first, then the toplevel.
     */
    /**
     * The toplevels that can be hit, topmost first: those shown by the
     * compositor in a test session, otherwise the active window only.
     */
    static List<Gtk4Window> getStackingOrder() {
        Gtk4Window root = getActiveWindow();
        if (!Gtk4TestSession.isEnabled()) {
            return root != null ? List.of(root) : List.of();
        }
        Map<String, Gtk4Window> byTag = new HashMap<>();
        for (Gtk4Window w : windowMap.values()) {
            if (w.testTag != null && !w.isPopup() && w.isShowing()) {
                byTag.put(w.testTag, w);
            }
        }
        List<Gtk4Window> order = new ArrayList<>();
        for (String tag : Gtk4TestSession.getStackingOrder()) {
            Gtk4Window w = byTag.get(tag);
            if (w != null) {
                order.add(w);
            }
        }
        if (root != null && !order.contains(root)) {
            order.addFirst(root);
        }
        return order;
    }

    static Gtk4Window getWindowAt(List<Gtk4Window> stackingOrder, int x, int y) {
        for (Gtk4Window w : stackingOrder) {
            Gtk4Window popup = popupAt(w, x, y);
            if (popup != null) {
                return popup;
            }
        }
        for (Gtk4Window w : stackingOrder) {
            if (w.containsScreenPoint(x, y)) {
                return w;
            }
        }
        return null;
    }

    private static Gtk4Window popupAt(Gtk4Window owner, int x, int y) {
        List<Gtk4Window> popups = new ArrayList<>(owner.children);
        for (int i = popups.size() - 1; i >= 0; i--) {
            Gtk4Window child = popups.get(i);
            if (!child.isPopup() || !child.isShowing()) {
                continue;
            }
            Gtk4Window nested = popupAt(child, x, y);
            if (nested != null) {
                return nested;
            }
            if (child.containsScreenPoint(x, y)) {
                return child;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "Gtk4Window[" + ptr + " " + windowType + " " + frameType + "]";
    }
}
