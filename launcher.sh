#!/bin/sh
#
# Copyright (c) 2026, Thiago Sayão. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  The copyright holder designates
# this particular file as subject to the "Classpath" exception as provided
# in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#

# Runs one or more of the demos in the demos directory with the GTK 4 glass,
# on the current desktop or in a nested compositor that supports translucent
# window backdrops (ext-background-effect-v1). The demos are started together,
# in the same desktop.
#
#   none   the current desktop and session bus, with the backdrop support of
#          the running compositor (the default)
#   gnome  GNOME Shell (51 or later) in a toolbox container, so that a newer
#          GNOME than the one of the host can be used. The desktop shows the
#          wallpaper of the host. When the container does not exist, the
#          launcher asks before creating it.
#   kde    KWin and the Plasma desktop in a toolbox container of Fedora
#          Rawhide, created in the same way.
#   hyprland
#          Hyprland in a toolbox container of Fedora Rawhide, created in the
#          same way, with the lionheartp/Hyprland COPR, as Fedora does not
#          ship Hyprland.
#
# A nested compositor runs on a private D-Bus session with its own settings,
# so it leaves the running desktop untouched, and is closed when the last demo
# exits.
#
# Requires the plugin built with ./gradlew jar, and runs the demos with the
# JavaFX SDK it was built with (build/run.args), inside a Wayland session.
#
# Usage: ./launcher.sh [options] [-- extra JVM options]
#
# Options:
#   --apps=a,b,...          demos to run, by name (see --list), or "all"
#                           (eyecandy)
#   --compositor=gnome|kde|hyprland|none
#                           nested compositor, or none for the current
#                           desktop (none)
#   --pipeline=auto|vk|gl|sw
#                           Prism pipeline: Vulkan, OpenGL 4.5 or software;
#                           auto tries them in that order (auto)
#   --backdrop=auto|on|off  blur the area behind the windows; auto blurs it
#                           (auto)
#   --toolbox=NAME          toolbox container of the nested compositor
#                           (jfx-gnome51, jfx-kde, jfx-hyprland), or "none" to use the
#                           compositor of the host
#   --toolbox-release=N     Fedora release of a new toolbox container, a
#                           number or rawhide (gnome: 45, kde and
#                           hyprland: rawhide)
#   --wallpaper=FILE        gnome: image of the desktop (the host wallpaper)
#   --size=WxH              gnome, kde, hyprland: size of the nested output (2400x1440)
#   --scale=N               kde, hyprland: output scale (1)
#   --monitors=WxH@S,...    gnome: monitors of the nested shell, side by side,
#                           with their scales, each in a window of its own
#                           without --headless (SIZE@1)
#   --headless              gnome: run the nested shell without a window, on
#                           a virtual monitor of --size: nothing shows on the
#                           desktop and the focus on the host does not matter
#   --screenshot=FILE       gnome, kde: save a screenshot of the nested desktop
#   --screenshot-delay=S    seconds to wait before the screenshot (12);
#                           with --headless, the session then ends
#   --list                  list the demos and exit
#   --help, -h              show this help and exit
#
# The environment variables GDK_DISABLE and GSK_DEBUG are passed to the demos.
# The extra JVM options are passed to the demos. The demos are eyecandy, the
# showcases of the Prism renderer in one window (-Deyecandy.show=fractals
# starts on one), games, a Breakout made with FXGL, which ./gradlew fxgl
# downloads first (-Dgames.autoplay=true plays by itself; "all" leaves it out
# until then). The tests of the glass are in jfx-tester
# (https://github.com/tsayao/jfx-tester), whose --wayland option runs its labs
# with this plugin.
#
# Examples:
#   ./launcher.sh
#   ./launcher.sh --apps=eyecandy --compositor=kde -- -Deyecandy.show=fractals

ROOT=$(cd "$(dirname "$0")" && pwd)
# The first launcher, which the relaunched ones in the toolbox container and
# the private session (which see the processes of the host) end with
: "${LAUNCHER_OWNER:=$$}"
export LAUNCHER_OWNER
DEMOS=$ROOT/demos

APPS=eyecandy
COMPOSITOR=none
PIPELINE=auto
BACKDROP=auto
TOOLBOX=
TOOLBOX_RELEASE=
# A COPR repository enabled in the container before installing
TOOLBOX_COPR=
# What the demos need, besides the compositor
TOOLBOX_PACKAGES="dbus-daemon java-25-openjdk-devel gtk4 libadwaita vulkan-loader mesa-vulkan-drivers \
mesa-libgbm dejavu-sans-fonts google-noto-sans-fonts google-noto-color-emoji-fonts at-spi2-core"
WALLPAPER=
SIZE=2400x1440
SCALE=1
SCREENSHOT=
SCREENSHOT_DELAY=12
HEADLESS=
MONITORS=

usage() {
    sed -n '/^# Runs one or more/,/^[^#]/p' "$0" | sed -e '/^[^#]/d' -e 's/^# \{0,1\}//'
    echo "Apps: $(demos | paste -sd' ' -)"
    exit "${1:-0}"
}

demos() {
    for f in "$DEMOS"/*Demo.java; do
        name=$(basename "$f" Demo.java)
        printf '%s\n' "$name" | tr '[:upper:]' '[:lower:]'
    done
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apps=*) APPS=${1#*=} ;;
        --compositor=*) COMPOSITOR=${1#*=} ;;
        --pipeline=*) PIPELINE=${1#*=} ;;
        --backdrop=*) BACKDROP=${1#*=} ;;
        --toolbox=*) TOOLBOX=${1#*=} ;;
        --toolbox-release=*) TOOLBOX_RELEASE=${1#*=} ;;
        --wallpaper=*) WALLPAPER=${1#*=} ;;
        --size=*) SIZE=${1#*=} ;;
        --scale=*) SCALE=${1#*=} ;;
        --screenshot=*) SCREENSHOT=${1#*=} ;;
        --screenshot-delay=*) SCREENSHOT_DELAY=${1#*=} ;;
        --headless) HEADLESS=1 ;;
        --monitors=*) MONITORS=${1#*=} ;;
        --list) demos; exit 0 ;;
        --help|-h) usage 0 ;;
        --) shift; break ;;
        *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

case "$COMPOSITOR" in
    gnome)
        : "${TOOLBOX:=jfx-gnome51}" "${TOOLBOX_RELEASE:=45}"
        TOOLBOX_PACKAGES="gnome-shell mutter-devkit nautilus $TOOLBOX_PACKAGES" ;;
    kde)
        : "${TOOLBOX:=jfx-kde}" "${TOOLBOX_RELEASE:=rawhide}"
        TOOLBOX_PACKAGES="kwin plasma-workspace kf6-kconfig xdg-desktop-portal-kde breeze-gtk-gtk4 spectacle $TOOLBOX_PACKAGES" ;;
    hyprland)
        : "${TOOLBOX:=jfx-hyprland}" "${TOOLBOX_RELEASE:=rawhide}"
        TOOLBOX_COPR=lionheartp/Hyprland
        TOOLBOX_PACKAGES="hyprland hyprland-guiutils xdg-desktop-portal-hyprland $TOOLBOX_PACKAGES" ;;
    none) ;;
    *) echo "Unknown compositor: $COMPOSITOR (gnome, kde, hyprland or none)" >&2; exit 2 ;;
esac
case "$PIPELINE" in
    auto) PRISM_ORDER=vk,gl,sw ;;
    vk|gl|sw) PRISM_ORDER=$PIPELINE ;;
    *) echo "Unknown pipeline: $PIPELINE (auto, vk, gl or sw)" >&2; exit 2 ;;
esac
case "$BACKDROP" in
    auto|on|off) ;;
    *) echo "Unknown backdrop: $BACKDROP (auto, on or off)" >&2; exit 2 ;;
esac
# The games need FXGL, which ./gradlew fxgl downloads
FXGL_CLASSPATH=$ROOT/build/fxgl.classpath
if [ "$APPS" = all ]; then
    APPS=$(demos | paste -sd, -)
    if [ ! -f "$FXGL_CLASSPATH" ]; then
        APPS=$(printf '%s' "$APPS" | tr ',' '\n' | grep -vx games | paste -sd, -)
    fi
fi
CLASSES=
for app in $(printf '%s' "$APPS" | tr ',' ' '); do
    want=$(printf '%s' "${app%Demo}" | tr '[:upper:]' '[:lower:]')
    class=
    for f in "$DEMOS"/*Demo.java; do
        name=$(basename "$f" .java)
        if [ "$(printf '%s' "${name%Demo}" | tr '[:upper:]' '[:lower:]')" = "$want" ]; then
            class=$name
        fi
    done
    if [ -z "$class" ]; then
        echo "Unknown demo: $app (available: $(demos | paste -sd' ' -))" >&2
        exit 2
    fi
    CLASSES="$CLASSES $class"
done
if [ -z "$CLASSES" ]; then
    echo "No demos given" >&2
    exit 2
fi
case " $CLASSES " in
    *" GamesDemo "*)
        if [ ! -f "$FXGL_CLASSPATH" ]; then
            echo "FXGL not downloaded: run ./gradlew fxgl" >&2
            exit 1
        fi ;;
esac

RUN_ARGS=$ROOT/build/run.args
if [ ! -f "$RUN_ARGS" ]; then
    echo "Plugin not built: $RUN_ARGS not found (run ./gradlew jar -PjfxSdk=DIR)" >&2
    exit 1
fi
if [ -z "$WAYLAND_DISPLAY" ]; then
    echo "This script runs inside a Wayland session" >&2
    exit 1
fi

relaunch() {
    "$@" sh "$0" --apps="$APPS" --compositor="$COMPOSITOR" --pipeline="$PIPELINE" \
        --backdrop="$BACKDROP" --toolbox="$TOOLBOX" --toolbox-release="$TOOLBOX_RELEASE" \
        --wallpaper="$WALLPAPER" --size="$SIZE" --scale="$SCALE" --screenshot="$SCREENSHOT" \
        --screenshot-delay="$SCREENSHOT_DELAY" ${HEADLESS:+--headless} ${MONITORS:+--monitors=$MONITORS} \
        -- $JVM_OPTIONS
}
JVM_OPTIONS="$*"

ensure_toolbox() {
    if ! command -v toolbox > /dev/null; then
        echo "toolbox is not installed: install it (podman-toolbox on Debian and Ubuntu, toolbox on" >&2
        echo "Fedora; https://github.com/containers/toolbox), or use --toolbox=none to use" >&2
        echo "the compositor of the host" >&2
        exit 1
    fi
    if podman container exists "$TOOLBOX" 2>/dev/null; then
        # A container created before a package joined the list gets it now
        missing=$(toolbox run --container "$TOOLBOX" sh -c \
            'for p in "$@"; do rpm -q "$p" > /dev/null 2>&1 || printf "%s " "$p"; done' sh $TOOLBOX_PACKAGES)
        if [ -n "$missing" ]; then
            echo "Installing in $TOOLBOX: $missing"
            enable_copr
            toolbox run --container "$TOOLBOX" sudo dnf install -y $missing || exit 1
        fi
        return
    fi
    echo "The toolbox container $TOOLBOX does not exist. It can be created from Fedora $TOOLBOX_RELEASE"
    [ "$COMPOSITOR" = gnome ] && echo "(GNOME 51 is in Fedora 45)"
    echo "with the packages:"
    echo "  $TOOLBOX_PACKAGES"
    [ -n "$TOOLBOX_COPR" ] && echo "from Fedora and the COPR repository $TOOLBOX_COPR"
    printf 'Create it? [y/N] '
    answer=
    if ! { read -r answer < /dev/tty; } 2>/dev/null; then
        echo
    fi
    case "$answer" in
        y|Y|yes|s|S|sim) ;;
        *)
            echo "Not created; use --toolbox=none to use the compositor of the host" >&2
            exit 1
            ;;
    esac
    if [ "$TOOLBOX_RELEASE" = rawhide ]; then
        # toolbox only takes release numbers
        toolbox --assumeyes create --image registry.fedoraproject.org/fedora-toolbox:rawhide "$TOOLBOX" || exit 1
    else
        toolbox --assumeyes create --distro fedora --release "$TOOLBOX_RELEASE" "$TOOLBOX" || exit 1
    fi
    enable_copr
    toolbox run --container "$TOOLBOX" sudo dnf install -y $TOOLBOX_PACKAGES || exit 1
}

enable_copr() {
    if [ -n "$TOOLBOX_COPR" ]; then
        toolbox run --container "$TOOLBOX" sudo dnf copr enable -y "$TOOLBOX_COPR" || exit 1
    fi
}

if [ "$COMPOSITOR" = gnome ] && [ -z "$WALLPAPER" ]; then
    WALLPAPER=$(gsettings get org.gnome.desktop.background picture-uri 2>/dev/null \
        | sed -e "s/^'//" -e "s/'$//" -e 's|^file://||')
fi
if [ "$COMPOSITOR" != none ]; then
    if [ "$TOOLBOX" != none ] && [ ! -f /run/.toolboxenv ]; then
        ensure_toolbox
        relaunch toolbox run --container "$TOOLBOX" env GDK_DISABLE="$GDK_DISABLE" GSK_DEBUG="$GSK_DEBUG" \
            LAUNCHER_OWNER="$LAUNCHER_OWNER"
        exit $?
    fi
fi

LOGS=${TMPDIR:-/tmp}/jfx-wayland-launcher-$COMPOSITOR
[ "$COMPOSITOR" != none ] && LOGS=$LOGS-$TOOLBOX
mkdir -p "$LOGS"

# The demos run with a copy of the plugin, which a build can then replace
if [ -f "$ROOT/build/libs/jfx-wayland.jar" ] && [ -f "$RUN_ARGS" ] && [ -z "$LAUNCHER_JAR" ]; then
    export LAUNCHER_JAR=$LOGS/jfx-wayland.jar
    cp "$ROOT/build/libs/jfx-wayland.jar" "$LAUNCHER_JAR"
    sed "s|\"build/libs/jfx-wayland.jar\"|\"$LAUNCHER_JAR\"|" "$RUN_ARGS" > "$LOGS/run.args"
fi
[ -n "$LAUNCHER_JAR" ] && RUN_ARGS=$LOGS/run.args

if [ "$COMPOSITOR" != none ] && [ -z "$LAUNCHER_SESSION" ]; then
    export LAUNCHER_SESSION=1
    # The buttons of the window titles, from the settings of the host, which
    # the private session does not see; under KDE, those of KWin
    if [ "$COMPOSITOR" = kde ]; then
        export LAUNCHER_BUTTON_LAYOUT='icon:minimize,maximize,close'
    else
        LAUNCHER_BUTTON_LAYOUT=$(gsettings get org.gnome.desktop.wm.preferences button-layout 2>/dev/null \
            | sed -e "s/^'//" -e "s/'$//")
        export LAUNCHER_BUTTON_LAYOUT=${LAUNCHER_BUTTON_LAYOUT:-:minimize,maximize,close}
    fi
    # The services of the private session get a dconf database of their own, as
    # a write to the user database also notifies the dconf clients of the host
    # (through XDG_RUNTIME_DIR/dconf), and the GNOME Shell of the host can crash
    # on that. One per compositor, named without dashes, which the D-Bus object
    # path of the dconf writer cannot have
    printf 'user-db:jfx_wayland_launcher_%s\n' "$COMPOSITOR" > "$LOGS/dconf-profile"
    export DCONF_PROFILE=$LOGS/dconf-profile
    relaunch dbus-run-session --
    exit $?
fi

SOCKET=wayland-launcher-$COMPOSITOR-$$
HOST_DISPLAY=$WAYLAND_DISPLAY
BUS=$DBUS_SESSION_BUS_ADDRESS
CHILDREN=

cleanup() {
    for pid in $PIDS; do
        pkill -TERM -P "$pid" 2>/dev/null
    done
    kill $CHILDREN $PIDS 2>/dev/null
    if [ "$COMPOSITOR" = none ]; then
        return
    fi
    rm -f "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/$SOCKET" "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/$SOCKET.lock"
    for p in /proc/[0-9]*; do
        pid=${p#/proc/}
        [ "$pid" = $$ ] && continue
        if grep -qzx -e "DBUS_SESSION_BUS_ADDRESS=$BUS" -e "DBUS_STARTER_ADDRESS=$BUS" -F "$p/environ" 2>/dev/null; then
            kill "$pid" 2>/dev/null
        fi
    done
}
trap cleanup EXIT
trap 'exit 130' INT TERM
# The end of a headless session after its screenshot
trap 'exit 0' USR1

# The demos are compiled once into build/demos, again only when a source, the
# plugin or FXGL changes, as running them from their sources compiled them on
# every start
DEMO_CLASSES=$ROOT/build/demos

# The class path of the plugin in build/run.args, for javac
plugin_classpath() {
    awk '/^"-cp"$/ { getline; gsub(/"/, ""); print }' "$ROOT/build/run.args"
}

compile_demos() {
    stamp=$DEMO_CLASSES/.stamp
    cp=$(plugin_classpath)
    [ -f "$FXGL_CLASSPATH" ] && cp=$cp:$(cat "$FXGL_CLASSPATH")
    if [ ! -f "$stamp" ] || [ -n "$(find "$DEMOS" "$ROOT/build/run.args" "$ROOT/build/libs/jfx-wayland.jar" \
            "$FXGL_CLASSPATH" -newer "$stamp" -print 2>/dev/null | head -1)" ]; then
        rm -rf "$DEMO_CLASSES"
        sources=$CLASSES
    else
        sources=
        for class in $CLASSES; do
            [ -f "$DEMO_CLASSES/$class.class" ] || sources="$sources $class"
        done
    fi
    [ -z "$sources" ] && return
    echo "Compiling the demos into $DEMO_CLASSES"
    mkdir -p "$DEMO_CLASSES"
    {
        grep -E '^"--(module-path|add-exports|add-modules)=' "$ROOT/build/run.args"
        printf '"-cp"\n"%s"\n' "$cp"
    } > "$LOGS/javac.args"
    files=
    for class in $sources; do
        files="$files $DEMOS/$class.java"
    done
    (cd "$ROOT" && javac @"$LOGS/javac.args" -nowarn -d "$DEMO_CLASSES" -sourcepath "$DEMOS" $files) \
        > "$LOGS/javac.log" 2>&1
    if [ $? -ne 0 ]; then
        cat "$LOGS/javac.log" >&2
        rm -rf "$DEMO_CLASSES"
        exit 1
    fi
    [ "$sources" = "$CLASSES" ] && touch "$stamp"
}

run() {
    cd "$ROOT" || exit 1
    exec java @"$RUN_ARGS" \
        --add-exports javafx.graphics/com.sun.prism=ALL-UNNAMED \
        -Dprism.order=$PRISM_ORDER $BACKDROP_OPTION "$@"
}

wait_for_socket() {
    for i in $(seq 1 100); do
        [ -S "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/$SOCKET" ] && return
        sleep 0.2
    done
    echo "The nested compositor did not start, see $LOGS" >&2
    exit 1
}

# Places the monitors of the nested shell side by side, at their scales
apply_monitors() {
    case "$MONITORS" in
        *@*) ;;
        *) return ;;
    esac
    MONITORS=$MONITORS python3 - <<'PY' > "$LOGS/monitors.log" 2>&1 || echo "The monitors were not configured, see $LOGS/monitors.log" >&2
import os
from gi.repository import Gio, GLib

wanted = [float(m.split('@')[1]) if '@' in m else 1.0 for m in os.environ['MONITORS'].split(',')]
bus = Gio.bus_get_sync(Gio.BusType.SESSION)
def call(method, args, reply):
    return bus.call_sync('org.gnome.Mutter.DisplayConfig', '/org/gnome/Mutter/DisplayConfig',
                         'org.gnome.Mutter.DisplayConfig', method, args, GLib.VariantType(reply),
                         Gio.DBusCallFlags.NONE, -1, None).unpack()
serial, monitors, logical, props = call('GetCurrentState', None, '(ua((ssss)a(siiddada{sv})a{sv})a(iiduba(ssss)a{sv})a{sv})')
# The order of the connectors is the order of the virtual monitors
monitors = sorted(monitors, key=lambda m: m[0][0])
config = []
x = 0
for i, (ids, modes, mprops) in enumerate(monitors):
    mode = next((m for m in modes if m[6].get('is-current')), modes[0])
    scale = wanted[i] if i < len(wanted) else 1.0
    # The nearest scale that the mode supports
    scale = min(mode[5], key=lambda s: abs(s - scale)) if mode[5] else scale
    config.append((x, 0, scale, 0, i == 0, [(ids[0], mode[0], {})]))
    print(ids[0], mode[0], 'scale', scale, 'at', x)
    x += int(round(mode[1] / scale))
call('ApplyMonitorsConfig', GLib.Variant('(uua(iiduba(ssa{sv}))a{sv})', (serial, 1, config, {})), '()')
PY
}

start_gnome() {
    wallpaper=$WALLPAPER
    if [ -n "$wallpaper" ] && [ ! -f "$wallpaper" ] && [ -f "/run/host$wallpaper" ]; then
        wallpaper=/run/host$wallpaper
    fi
    export XDG_CONFIG_HOME="$LOGS/config"
    rm -rf "$XDG_CONFIG_HOME"
    mkdir -p "$XDG_CONFIG_HOME/glib-2.0/settings"
    cat > "$XDG_CONFIG_HOME/glib-2.0/settings/keyfile" <<EOF
[org/gnome/shell]
welcome-dialog-last-shown-version='999'

[org/gnome/desktop/interface]
enable-hot-corners=false

[org/gnome/desktop/session]
idle-delay=uint32 0

[org/gnome/desktop/notifications]
show-banners=false

[org/gnome/desktop/screensaver]
idle-activation-enabled=false
lock-enabled=false

[org/gnome/desktop/background]
picture-uri='file://$wallpaper'
picture-uri-dark='file://$wallpaper'
picture-options='zoom'
EOF
    export GSETTINGS_BACKEND=keyfile

    [ -z "$MONITORS" ] && MONITORS=$SIZE@1
    set --
    devkit=
    for m in $(printf '%s' "$MONITORS" | tr ',' ' '); do
        if [ -n "$HEADLESS" ]; then
            set -- "$@" --virtual-monitor "${m%@*}"
        elif [ -z "$devkit" ]; then
            devkit="--monitor-size=${m%@*}"
        else
            devkit="$devkit --add-monitor=${m%@*}"
        fi
    done
    if [ -n "$HEADLESS" ]; then
        # No window on the desktop, whose focus would deactivate the nested windows
        set -- --headless "$@"
    else
        set -- --devkit --devkit-args="$devkit"
    fi
    (unset WAYLAND_DISPLAY DISPLAY; exec gnome-shell "$@" \
        --unsafe-mode --wayland --wayland-display=$SOCKET) > "$LOGS/shell.log" 2>&1 &
    CHILDREN="$CHILDREN $!"
    wait_for_socket
    for i in $(seq 1 150); do
        gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell \
            --method org.gnome.Shell.Eval "Main.layoutManager._startingUp" 2>/dev/null \
            | grep -q "'false'" && break
        sleep 0.2
    done
    gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell \
        --method org.gnome.Shell.Eval "Main.overview.hide()" > /dev/null 2>&1
    apply_monitors
    sleep 1

    export WAYLAND_DISPLAY=$SOCKET GDK_BACKEND=wayland XDG_CURRENT_DESKTOP=GNOME
    # The demos are Wayland clients only
    unset DISPLAY XAUTHORITY
    dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP > /dev/null 2>&1
    start_atspi
    if [ -n "$SCREENSHOT" ]; then
        (sleep "$SCREENSHOT_DELAY"
         gdbus call --session --dest org.gnome.Shell.Screenshot --object-path /org/gnome/Shell/Screenshot \
             --method org.gnome.Shell.Screenshot.Screenshot false false "$SCREENSHOT"
         # Nobody can close the demos of a headless session
         [ -n "$HEADLESS" ] && kill -USR1 $$) > /dev/null 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

# The bus of AT-SPI activates its registry through systemd, which the private
# session does not have: start it here, for screen readers
start_atspi() {
    if [ -x /usr/libexec/at-spi2-registryd ]; then
        (/usr/libexec/at-spi2-registryd) > "$LOGS/atspi.log" 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

kde() {
    exec env XDG_CONFIG_HOME="$LOGS/config" XDG_CURRENT_DESKTOP=KDE KDE_FULL_SESSION=true \
        KDE_SESSION_VERSION=6 QT_QPA_PLATFORM=wayland "$@"
}

start_kde() {
    mkdir -p "$LOGS/config"
    # The portals, activated by the private bus, and the demos then pick the
    # KDE implementations, not the ones of the desktop of the host
    export XDG_CURRENT_DESKTOP=KDE
    (kde kwriteconfig6 --file kded6rc --group Module-plasma_welcome --key autoload false)
    (kde kwriteconfig6 --file ksplashrc --group KSplash --key Engine none)
    (kde kwriteconfig6 --file ksplashrc --group KSplash --key Theme None)
    (kde kwriteconfig6 --file kwinrc --group Plugins --key blurEnabled true)
    (kde kwriteconfig6 --file kwinrc --group Plugins --key contrastEnabled true)

    (kde kwin_wayland --wayland-display "$HOST_DISPLAY" --socket $SOCKET \
        --width "${SIZE%x*}" --height "${SIZE#*x}" --scale "$SCALE") > "$LOGS/kwin.log" 2>&1 &
    CHILDREN="$CHILDREN $!"
    wait_for_socket

    export WAYLAND_DISPLAY=$SOCKET
    dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP > /dev/null 2>&1
    start_atspi
    # The GTK theme of Plasma, which kde-gtk-config sets in a full session;
    # GDK reads it from the settings of the private session
    gsettings set org.gnome.desktop.interface gtk-theme Breeze 2>/dev/null
    if command -v plasmashell > /dev/null; then
        (kde plasmashell --no-respawn) > "$LOGS/plasma.log" 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
    sleep 3
    if [ -n "$SCREENSHOT" ]; then
        # Spectacle may take screenshots through KWin
        (sleep "$SCREENSHOT_DELAY"
         kde spectacle --background --nonotify --fullscreen --output "$SCREENSHOT") > /dev/null 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

start_hyprland() {
    # The Lua configuration, as Hyprland 0.57 drops the .conf one
    cat > "$LOGS/hyprland.lua" <<EOF
hl.monitor({ output = "", mode = "$SIZE", position = "auto", scale = $SCALE })
hl.config({
    misc = {
        disable_hyprland_logo = true,
        disable_splash_rendering = true,
    },
})
EOF
    export XDG_CURRENT_DESKTOP=Hyprland
    (exec start-hyprland -- --config "$LOGS/hyprland.lua") > "$LOGS/hyprland.log" 2>&1 &
    watchdog=$!
    CHILDREN="$CHILDREN $watchdog"
    # Hyprland names its socket itself, and writes it in the lock of its
    # instance, after its pid, a child of the watchdog
    socket=
    for i in $(seq 1 100); do
        for lock in "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"/hypr/*/hyprland.lock; do
            [ -f "$lock" ] || continue
            pid=$(sed -n 1p "$lock")
            if [ "$(awk '/^PPid:/ { print $2 }' "/proc/$pid/status" 2>/dev/null)" = "$watchdog" ]; then
                socket=$(sed -n 2p "$lock")
            fi
        done
        [ -n "$socket" ] && break
        sleep 0.2
    done
    if [ -z "$socket" ]; then
        echo "The nested compositor did not start, see $LOGS" >&2
        exit 1
    fi
    SOCKET=$socket
    wait_for_socket

    export WAYLAND_DISPLAY=$SOCKET
    dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP > /dev/null 2>&1
    start_atspi
    if [ -n "$SCREENSHOT" ]; then
        echo "--screenshot is only supported with --compositor=gnome" >&2
    fi
}

start_none() {
    if [ -n "$SCREENSHOT" ]; then
        echo "--screenshot is only supported with --compositor=gnome" >&2
    fi
}

PIDS=
compile_demos
if [ -n "$LAUNCHER_BUTTON_LAYOUT" ]; then
    gsettings set org.gnome.desktop.wm.preferences button-layout "$LAUNCHER_BUTTON_LAYOUT" 2>/dev/null
fi
start_$COMPOSITOR

for class in $CLASSES; do
    name=$(printf '%s' "${class%Demo}" | tr '[:upper:]' '[:lower:]')
    backdrop=$BACKDROP
    [ "$backdrop" = auto ] && backdrop=on
    [ "$backdrop" = on ] && BACKDROP_OPTION=-Dglass.gtk4.backdrop=true || BACKDROP_OPTION=-Dglass.gtk4.backdrop=false
    DEMO_OPTIONS=
    case "$class" in
        EyeCandyDemo)
            # Its extended windows have no title bar of GTK, where the blur
            # area that KWin offsets shows the most
            DEMO_OPTIONS=-Dglass.gtk4.backdrop.kwin=true ;;
    esac
    # The compiled demos, and for the games FXGL, join the plugin on the class path
    extra=$DEMO_CLASSES
    [ "$class" = GamesDemo ] && extra=$extra:$(cat "$FXGL_CLASSPATH")
    sed '/^"-cp"$/{n;s|"$|:'"$extra"'"|}' "$RUN_ARGS" > "$LOGS/$name.args"
    (RUN_ARGS=$LOGS/$name.args
     run -Dprism.verbose=true -Djavafx.enablePreview=true \
        -Djavafx.suppressPreviewWarning=true $DEMO_OPTIONS $JVM_OPTIONS "$class" 2>&1 \
        | tee "$LOGS/$name.log" | sed -u "s/^/[$name] /") &
    PIDS="$PIDS $!"
done
if [ "$COMPOSITOR" = none ]; then
    echo "Running$CLASSES on the current desktop ($PIPELINE), logs in $LOGS"
else
    echo "Running$CLASSES on $COMPOSITOR ($PIPELINE), logs in $LOGS"
fi
# A signal to the first launcher does not reach the relaunched ones
if [ "$LAUNCHER_OWNER" != $$ ]; then
    (while kill -0 "$LAUNCHER_OWNER" 2>/dev/null; do sleep 1; done
     kill -TERM $$) > /dev/null 2>&1 &
    CHILDREN="$CHILDREN $!"
fi
wait $PIDS
