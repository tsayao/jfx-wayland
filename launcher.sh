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
#   niri   niri (26.04 or later for the backdrop) in a toolbox container of
#          Fedora Rawhide, created in the same way.
#   cosmic the compositor of COSMIC in a toolbox container of Fedora
#          Rawhide, created in the same way; its releases until 1.11 have no
#          backdrops.
#   sway, labwc, wayfire
#          the wlroots compositors, in toolbox containers of Fedora Rawhide,
#          created in the same way; without backdrops, for the opaque windows
#          and the protocols of wlroots.
#   weston the reference compositor, in a toolbox container of Fedora
#          Rawhide, created in the same way; without backdrops, for the core
#          protocols only.
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
#   --compositor=gnome|kde|hyprland|niri|cosmic|sway|labwc|wayfire|weston|none
#                           nested compositor, or none for the current
#                           desktop (none)
#   --pipeline=auto|vk|gl|es2egl|sw
#                           Prism pipeline: Vulkan, OpenGL 4.5, the ES2
#                           pipeline on EGL or software; auto tries them in
#                           that order (auto)
#   --backdrop=auto|on|off  blur the area behind the windows; auto blurs it
#                           (auto)
#   --toolbox=NAME          toolbox container of the nested compositor
#                           (jfx-gnome51, or jfx- and the name of the
#                           compositor), or "none" to use the compositor of
#                           the host
#   --toolbox-release=N     Fedora release of a new toolbox container, a
#                           number or rawhide (gnome: 45, the others:
#                           rawhide)
#   --wallpaper=FILE        gnome: image of the desktop (the host wallpaper)
#   --size=WxH              gnome, kde, hyprland, sway, wayfire, weston: size
#                           of the nested output (2400x1440); niri, cosmic
#                           and labwc open a window of their own size
#   --scale=N               kde, hyprland, niri, sway, wayfire, weston: output
#                           scale, a whole number for weston (1)
#   --monitors=WxH@S,...    gnome: monitors of the nested shell, side by side,
#                           with their scales, each in a window of its own
#                           without --headless (SIZE@1)
#   --headless              gnome: run the nested shell without a window, on
#                           a virtual monitor of --size: nothing shows on the
#                           desktop and the focus on the host does not matter
#   --screenshot=FILE       gnome, kde, niri, cosmic, sway, labwc, wayfire:
#                           save a screenshot of the nested desktop (with
#                           grim on niri, cosmic and the wlroots compositors)
#   --screenshot-delay=S    seconds to wait before the screenshot (12);
#                           with --headless, the session then ends
#   --list                  list the demos and exit
#   --help, -h              show this help and exit
#
# The environment variables GDK_DISABLE, GDK_DEBUG and GSK_DEBUG are passed to
# the demos.
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
RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
# The games need FXGL, which ./gradlew fxgl downloads
FXGL_CLASSPATH=$ROOT/build/fxgl.classpath
RUN_ARGS=$ROOT/build/run.args
# The demos are compiled once into build/demos, again only when a source, the
# plugin or FXGL changes, as running them from their sources compiled them on
# every start
DEMO_CLASSES=$ROOT/build/demos

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
JVM_OPTIONS=
# The processes started here: the demos, and the compositor and the helpers
PIDS=
CHILDREN=

# ---------------------------------------------------------------------------
# Options

usage() {
    sed -n '/^# Runs one or more/,/^[^#]/p' "$0" | sed -e '/^[^#]/d' -e 's/^# \{0,1\}//'
    echo "Apps: $(demos | paste -sd' ' -)"
    exit "${1:-0}"
}

fail() {
    echo "$1" >&2
    exit "${2:-2}"
}

# The demos, by name in lower case
demos() {
    for f in "$DEMOS"/*Demo.java; do
        lower "$(basename "$f" Demo.java)"
        echo
    done
}

lower() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

parse_options() {
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
            *) fail "Unknown option: $1 (see --help)" ;;
        esac
        shift
    done
    JVM_OPTIONS="$*"
}

# The toolbox container of each compositor, the release of Fedora it is made
# of, and its packages
configure_compositor() {
    case "$COMPOSITOR" in
        gnome)
            : "${TOOLBOX:=jfx-gnome51}" "${TOOLBOX_RELEASE:=45}"
            TOOLBOX_PACKAGES="gnome-shell mutter-devkit nautilus $TOOLBOX_PACKAGES" ;;
        kde)
            : "${TOOLBOX:=jfx-kde}" "${TOOLBOX_RELEASE:=rawhide}"
            TOOLBOX_PACKAGES="kwin plasma-workspace kf6-kconfig xdg-desktop-portal-kde breeze-gtk-gtk4 spectacle \
$TOOLBOX_PACKAGES" ;;
        hyprland)
            : "${TOOLBOX:=jfx-hyprland}" "${TOOLBOX_RELEASE:=rawhide}"
            TOOLBOX_COPR=lionheartp/Hyprland
            TOOLBOX_PACKAGES="hyprland hyprland-guiutils xdg-desktop-portal-hyprland $TOOLBOX_PACKAGES" ;;
        niri|sway|labwc|wayfire)
            : "${TOOLBOX:=jfx-$COMPOSITOR}" "${TOOLBOX_RELEASE:=rawhide}"
            TOOLBOX_PACKAGES="$COMPOSITOR grim $TOOLBOX_PACKAGES" ;;
        cosmic)
            : "${TOOLBOX:=jfx-cosmic}" "${TOOLBOX_RELEASE:=rawhide}"
            TOOLBOX_PACKAGES="cosmic-comp grim $TOOLBOX_PACKAGES" ;;
        weston)
            : "${TOOLBOX:=jfx-weston}" "${TOOLBOX_RELEASE:=rawhide}"
            TOOLBOX_PACKAGES="weston $TOOLBOX_PACKAGES" ;;
        none) ;;
        *) fail "Unknown compositor: $COMPOSITOR (gnome, kde, hyprland, niri, cosmic, sway, labwc, wayfire, weston or none)" ;;
    esac
}

validate_options() {
    case "$PIPELINE" in
        auto) PRISM_ORDER=vk,gl,es2egl,sw ;;
        vk|gl|es2egl|sw) PRISM_ORDER=$PIPELINE ;;
        *) fail "Unknown pipeline: $PIPELINE (auto, vk, gl, es2egl or sw)" ;;
    esac
    case "$BACKDROP" in
        auto|on|off) ;;
        *) fail "Unknown backdrop: $BACKDROP (auto, on or off)" ;;
    esac
}

# The class of a demo given by name, in any case and with or without Demo
demo_class() {
    want=$(lower "${1%Demo}")
    for f in "$DEMOS"/*Demo.java; do
        name=$(basename "$f" .java)
        if [ "$(lower "${name%Demo}")" = "$want" ]; then
            echo "$name"
            return
        fi
    done
}

# CLASSES, the classes of the demos to run; "all" leaves out the games until
# FXGL is downloaded
resolve_demos() {
    if [ "$APPS" = all ]; then
        APPS=$(demos | paste -sd, -)
        [ -f "$FXGL_CLASSPATH" ] || APPS=$(printf '%s' "$APPS" | tr ',' '\n' | grep -vx games | paste -sd, -)
    fi
    CLASSES=
    for app in $(printf '%s' "$APPS" | tr ',' ' '); do
        class=$(demo_class "$app")
        [ -n "$class" ] || fail "Unknown demo: $app (available: $(demos | paste -sd' ' -))"
        CLASSES="$CLASSES $class"
    done
    [ -n "$CLASSES" ] || fail "No demos given"
}

check_requirements() {
    case " $CLASSES " in
        *" GamesDemo "*) [ -f "$FXGL_CLASSPATH" ] || fail "FXGL not downloaded: run ./gradlew fxgl" 1 ;;
    esac
    [ -f "$RUN_ARGS" ] || fail "Plugin not built: $RUN_ARGS not found (run ./gradlew jar -PjfxSdk=DIR)" 1
    [ -n "$WAYLAND_DISPLAY" ] || fail "This script runs inside a Wayland session" 1
}

# ---------------------------------------------------------------------------
# The toolbox container and the private session

# Runs this script again, with the same options, under a command
relaunch() {
    "$@" sh "$0" --apps="$APPS" --compositor="$COMPOSITOR" --pipeline="$PIPELINE" \
        --backdrop="$BACKDROP" --toolbox="$TOOLBOX" --toolbox-release="$TOOLBOX_RELEASE" \
        --wallpaper="$WALLPAPER" --size="$SIZE" --scale="$SCALE" --screenshot="$SCREENSHOT" \
        --screenshot-delay="$SCREENSHOT_DELAY" ${HEADLESS:+--headless} ${MONITORS:+--monitors=$MONITORS} \
        -- $JVM_OPTIONS
}

in_toolbox() {
    toolbox run --container "$TOOLBOX" "$@"
}

ensure_toolbox() {
    if ! command -v toolbox > /dev/null; then
        echo "toolbox is not installed: install it (podman-toolbox on Debian and Ubuntu, toolbox on" >&2
        echo "Fedora; https://github.com/containers/toolbox), or use --toolbox=none to use" >&2
        echo "the compositor of the host" >&2
        exit 1
    fi
    if podman container exists "$TOOLBOX" 2>/dev/null; then
        install_missing_packages
    else
        confirm_toolbox_creation
        create_toolbox
    fi
}

# A container created before a package joined the list gets it now
install_missing_packages() {
    missing=$(in_toolbox sh -c \
        'for p in "$@"; do rpm -q "$p" > /dev/null 2>&1 || printf "%s " "$p"; done' sh $TOOLBOX_PACKAGES)
    if [ -n "$missing" ]; then
        echo "Installing in $TOOLBOX: $missing"
        enable_copr
        in_toolbox sudo dnf install -y $missing || exit 1
    fi
}

confirm_toolbox_creation() {
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
        *) fail "Not created; use --toolbox=none to use the compositor of the host" 1 ;;
    esac
}

create_toolbox() {
    if [ "$TOOLBOX_RELEASE" = rawhide ]; then
        # toolbox only takes release numbers
        toolbox --assumeyes create --image registry.fedoraproject.org/fedora-toolbox:rawhide "$TOOLBOX" || exit 1
    else
        toolbox --assumeyes create --distro fedora --release "$TOOLBOX_RELEASE" "$TOOLBOX" || exit 1
    fi
    enable_copr
    in_toolbox sudo dnf install -y $TOOLBOX_PACKAGES || exit 1
}

enable_copr() {
    if [ -n "$TOOLBOX_COPR" ]; then
        in_toolbox sudo dnf copr enable -y "$TOOLBOX_COPR" || exit 1
    fi
}

# A nested compositor runs in its toolbox container: this script runs again
# there, with the wallpaper of the host for GNOME
enter_toolbox() {
    if [ "$COMPOSITOR" = gnome ] && [ -z "$WALLPAPER" ]; then
        WALLPAPER=$(gsettings get org.gnome.desktop.background picture-uri 2>/dev/null \
            | sed -e "s/^'//" -e "s/'$//" -e 's|^file://||')
    fi
    if [ "$COMPOSITOR" = none ] || [ "$TOOLBOX" = none ] || [ -f /run/.toolboxenv ]; then
        return
    fi
    ensure_toolbox
    relaunch in_toolbox env GDK_DISABLE="$GDK_DISABLE" GDK_DEBUG="$GDK_DEBUG" GSK_DEBUG="$GSK_DEBUG" \
        LAUNCHER_OWNER="$LAUNCHER_OWNER"
    exit $?
}

prepare_logs() {
    LOGS=${TMPDIR:-/tmp}/jfx-wayland-launcher-$COMPOSITOR
    [ "$COMPOSITOR" != none ] && LOGS=$LOGS-$TOOLBOX
    mkdir -p "$LOGS"
}

# The demos run with a copy of the plugin, which a build can then replace
copy_plugin() {
    if [ -f "$ROOT/build/libs/jfx-wayland.jar" ] && [ -z "$LAUNCHER_JAR" ]; then
        export LAUNCHER_JAR=$LOGS/jfx-wayland.jar
        cp "$ROOT/build/libs/jfx-wayland.jar" "$LAUNCHER_JAR"
        sed "s|\"build/libs/jfx-wayland.jar\"|\"$LAUNCHER_JAR\"|" "$RUN_ARGS" > "$LOGS/run.args"
    fi
    [ -n "$LAUNCHER_JAR" ] && RUN_ARGS=$LOGS/run.args
}

# A nested compositor runs on a private D-Bus session, with the buttons of
# the window titles of the host and a dconf database of its own: this script
# runs again in it
enter_private_session() {
    if [ "$COMPOSITOR" = none ] || [ -n "$LAUNCHER_SESSION" ]; then
        return
    fi
    export LAUNCHER_SESSION=1
    export LAUNCHER_BUTTON_LAYOUT
    LAUNCHER_BUTTON_LAYOUT=$(host_button_layout)
    # The services of the private session get a dconf database of their own, as
    # a write to the user database also notifies the dconf clients of the host
    # (through XDG_RUNTIME_DIR/dconf), and the GNOME Shell of the host can crash
    # on that. One per compositor, named without dashes, which the D-Bus object
    # path of the dconf writer cannot have
    printf 'user-db:jfx_wayland_launcher_%s\n' "$COMPOSITOR" > "$LOGS/dconf-profile"
    export DCONF_PROFILE=$LOGS/dconf-profile
    relaunch dbus-run-session --
    exit $?
}

# The buttons of the window titles, from the settings of the host, which the
# private session does not see; under KDE, those of KWin
host_button_layout() {
    if [ "$COMPOSITOR" = kde ]; then
        echo 'icon:minimize,maximize,close'
        return
    fi
    layout=$(gsettings get org.gnome.desktop.wm.preferences button-layout 2>/dev/null \
        | sed -e "s/^'//" -e "s/'$//")
    echo "${layout:-:minimize,maximize,close}"
}

# ---------------------------------------------------------------------------
# Ending

# Stops the demos and the compositor, and, in a private session, every
# process of its bus
cleanup() {
    for pid in $PIDS; do
        pkill -TERM -P "$pid" 2>/dev/null
    done
    kill $CHILDREN $PIDS 2>/dev/null
    if [ "$COMPOSITOR" = none ]; then
        return
    fi
    rm -f "$RUNTIME_DIR/$SOCKET" "$RUNTIME_DIR/$SOCKET.lock"
    kill_bus_processes
}

kill_bus_processes() {
    for p in /proc/[0-9]*; do
        pid=${p#/proc/}
        [ "$pid" = $$ ] && continue
        if grep -qzx -e "DBUS_SESSION_BUS_ADDRESS=$BUS" -e "DBUS_STARTER_ADDRESS=$BUS" -F "$p/environ" 2>/dev/null
        then
            kill "$pid" 2>/dev/null
        fi
    done
}

set_traps() {
    trap cleanup EXIT
    trap 'exit 130' INT TERM
    # The end of a headless session after its screenshot
    trap 'exit 0' USR1
}

# A signal to the first launcher does not reach the relaunched ones
watch_owner() {
    if [ "$LAUNCHER_OWNER" != $$ ]; then
        (while kill -0 "$LAUNCHER_OWNER" 2>/dev/null; do sleep 1; done
         kill -TERM $$) > /dev/null 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

# ---------------------------------------------------------------------------
# The demos

# The class path of the plugin in build/run.args, for javac
plugin_classpath() {
    awk '/^"-cp"$/ { getline; gsub(/"/, ""); print }' "$ROOT/build/run.args"
}

# Whether a source of the demos, the plugin or FXGL changed since the demos
# were compiled
demos_stale() {
    stamp=$DEMO_CLASSES/.stamp
    [ ! -f "$stamp" ] || [ -n "$(find "$DEMOS" "$ROOT/build/run.args" "$ROOT/build/libs/jfx-wayland.jar" \
        "$FXGL_CLASSPATH" -newer "$stamp" -print 2>/dev/null | head -1)" ]
}

compile_demos() {
    if demos_stale; then
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
    write_javac_args
    files=
    for class in $sources; do
        files="$files $DEMOS/$class.java"
    done
    if ! (cd "$ROOT" && javac @"$LOGS/javac.args" -nowarn -d "$DEMO_CLASSES" -sourcepath "$DEMOS" $files) \
            > "$LOGS/javac.log" 2>&1; then
        cat "$LOGS/javac.log" >&2
        rm -rf "$DEMO_CLASSES"
        exit 1
    fi
    [ "$sources" = "$CLASSES" ] && touch "$DEMO_CLASSES/.stamp"
}

# The modules of the plugin, and the class path with FXGL when it is there
write_javac_args() {
    cp=$(plugin_classpath)
    [ -f "$FXGL_CLASSPATH" ] && cp=$cp:$(cat "$FXGL_CLASSPATH")
    {
        grep -E '^"--(module-path|add-exports|add-modules)=' "$ROOT/build/run.args"
        printf '"-cp"\n"%s"\n' "$cp"
    } > "$LOGS/javac.args"
}

run() {
    cd "$ROOT" || exit 1
    exec java @"$RUN_ARGS" \
        --add-exports javafx.graphics/com.sun.prism=ALL-UNNAMED \
        -Dprism.order=$PRISM_ORDER "$@"
}

# Runs a demo in the background, its output prefixed with its name
run_demo() {
    class=$1
    name=$(lower "${class%Demo}")
    backdrop=$BACKDROP
    [ "$backdrop" = auto ] && backdrop=on
    demo_options=-Dglass.gtk4.backdrop=$([ "$backdrop" = on ] && echo true || echo false)
    # Its extended windows have no title bar of GTK, where the blur area that
    # KWin offsets shows the most
    [ "$class" = EyeCandyDemo ] && demo_options="$demo_options -Dglass.gtk4.backdrop.kwin=true"
    # The compiled demos, and for the games FXGL, join the plugin on the class path
    extra=$DEMO_CLASSES
    [ "$class" = GamesDemo ] && extra=$extra:$(cat "$FXGL_CLASSPATH")
    sed '/^"-cp"$/{n;s|"$|:'"$extra"'"|}' "$RUN_ARGS" > "$LOGS/$name.args"
    (RUN_ARGS=$LOGS/$name.args
     run -Dprism.verbose=true -Djavafx.enablePreview=true \
        -Djavafx.suppressPreviewWarning=true $demo_options $JVM_OPTIONS "$class" 2>&1 \
        | tee "$LOGS/$name.log" | sed -u "s/^/[$name] /") &
    PIDS="$PIDS $!"
}

run_demos() {
    for class in $CLASSES; do
        run_demo "$class"
    done
    if [ "$COMPOSITOR" = none ]; then
        echo "Running$CLASSES on the current desktop ($PIPELINE), logs in $LOGS"
    else
        echo "Running$CLASSES on $COMPOSITOR ($PIPELINE), logs in $LOGS"
    fi
}

# ---------------------------------------------------------------------------
# The compositors

wait_for_socket() {
    for i in $(seq 1 100); do
        [ -S "$RUNTIME_DIR/$SOCKET" ] && return
        sleep 0.2
    done
    fail "The nested compositor did not start, see $LOGS" 1
}

# The demos and the portals use the nested compositor
use_nested_display() {
    export WAYLAND_DISPLAY=$SOCKET
    dbus-update-activation-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP > /dev/null 2>&1
    start_atspi
}

# The bus of AT-SPI activates its registry through systemd, which the private
# session does not have: start it here, for screen readers
start_atspi() {
    if [ -x /usr/libexec/at-spi2-registryd ]; then
        (/usr/libexec/at-spi2-registryd) > "$LOGS/atspi.log" 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

screenshot_unsupported() {
    if [ -n "$SCREENSHOT" ]; then
        echo "--screenshot is not supported with --compositor=$COMPOSITOR" >&2
    fi
}

start_none() {
    screenshot_unsupported
}

shell_eval() {
    gdbus call --session --dest org.gnome.Shell --object-path /org/gnome/Shell \
        --method org.gnome.Shell.Eval "$1"
}

start_gnome() {
    write_gnome_settings "$(host_path "$WALLPAPER")"
    [ -z "$MONITORS" ] && MONITORS=$SIZE@1
    launch_gnome_shell
    wait_for_socket
    wait_for_shell
    shell_eval "Main.overview.hide()" > /dev/null 2>&1
    apply_monitors
    sleep 1
    export GDK_BACKEND=wayland XDG_CURRENT_DESKTOP=GNOME
    # The demos are Wayland clients only
    unset DISPLAY XAUTHORITY
    use_nested_display
    [ -n "$SCREENSHOT" ] && schedule_gnome_screenshot
}

# A file of the host, which the container sees under /run/host when it is not
# shared
host_path() {
    if [ -n "$1" ] && [ ! -f "$1" ] && [ -f "/run/host$1" ]; then
        echo "/run/host$1"
    else
        echo "$1"
    fi
}

# The settings of the nested shell: no welcome, hot corner, notifications,
# screen saver or lock, and the wallpaper
write_gnome_settings() {
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
picture-uri='file://$1'
picture-uri-dark='file://$1'
picture-options='zoom'
EOF
    export GSETTINGS_BACKEND=keyfile
}

# Starts the shell, with virtual monitors when headless (no window on the
# desktop, whose focus would deactivate the nested windows), or else in the
# windows of the Mutter Development Kit, one per monitor
launch_gnome_shell() {
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
        set -- --headless "$@"
    else
        set -- --devkit --devkit-args="$devkit"
    fi
    (unset WAYLAND_DISPLAY DISPLAY; exec gnome-shell "$@" \
        --unsafe-mode --wayland --wayland-display="$SOCKET") > "$LOGS/shell.log" 2>&1 &
    CHILDREN="$CHILDREN $!"
}

wait_for_shell() {
    for i in $(seq 1 150); do
        shell_eval "Main.layoutManager._startingUp" 2>/dev/null | grep -q "'false'" && return
        sleep 0.2
    done
}

# A screenshot of the nested desktop after a delay; a headless session then
# ends, as nobody can close its demos
schedule_gnome_screenshot() {
    (sleep "$SCREENSHOT_DELAY"
     gdbus call --session --dest org.gnome.Shell.Screenshot --object-path /org/gnome/Shell/Screenshot \
         --method org.gnome.Shell.Screenshot.Screenshot false false "$SCREENSHOT"
     [ -n "$HEADLESS" ] && kill -USR1 $$) > /dev/null 2>&1 &
    CHILDREN="$CHILDREN $!"
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

# Runs a program of KDE with the settings of the nested session
kde() {
    exec env XDG_CONFIG_HOME="$LOGS/config" XDG_CURRENT_DESKTOP=KDE KDE_FULL_SESSION=true \
        KDE_SESSION_VERSION=6 QT_QPA_PLATFORM=wayland "$@"
}

start_kde() {
    mkdir -p "$LOGS/config"
    # The portals, activated by the private bus, and the demos then pick the
    # KDE implementations, not the ones of the desktop of the host
    export XDG_CURRENT_DESKTOP=KDE
    write_kde_settings
    (kde kwin_wayland --wayland-display "$HOST_DISPLAY" --socket "$SOCKET" \
        --width "${SIZE%x*}" --height "${SIZE#*x}" --scale "$SCALE") > "$LOGS/kwin.log" 2>&1 &
    CHILDREN="$CHILDREN $!"
    wait_for_socket
    use_nested_display
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

# No welcome or splash screen, and the blur and contrast effects of KWin
write_kde_settings() {
    (kde kwriteconfig6 --file kded6rc --group Module-plasma_welcome --key autoload false)
    (kde kwriteconfig6 --file ksplashrc --group KSplash --key Engine none)
    (kde kwriteconfig6 --file ksplashrc --group KSplash --key Theme None)
    (kde kwriteconfig6 --file kwinrc --group Plugins --key blurEnabled true)
    (kde kwriteconfig6 --file kwinrc --group Plugins --key contrastEnabled true)
}

start_hyprland() {
    write_hyprland_config
    export XDG_CURRENT_DESKTOP=Hyprland
    (exec start-hyprland -- --config "$LOGS/hyprland.lua") > "$LOGS/hyprland.log" 2>&1 &
    watchdog=$!
    CHILDREN="$CHILDREN $watchdog"
    SOCKET=$(hyprland_socket "$watchdog")
    [ -n "$SOCKET" ] || fail "The nested compositor did not start, see $LOGS" 1
    wait_for_socket
    use_nested_display
    screenshot_unsupported
}

# The Lua configuration, as Hyprland 0.57 drops the .conf one
write_hyprland_config() {
    cat > "$LOGS/hyprland.lua" <<EOF
hl.monitor({ output = "", mode = "$SIZE", position = "auto", scale = $SCALE })
hl.config({
    misc = {
        disable_hyprland_logo = true,
        disable_splash_rendering = true,
    },
})
EOF
}

# Hyprland names its socket itself, and writes it in the lock of its
# instance, after its pid, a child of the watchdog
hyprland_socket() {
    for i in $(seq 1 100); do
        for lock in "$RUNTIME_DIR"/hypr/*/hyprland.lock; do
            [ -f "$lock" ] || continue
            pid=$(sed -n 1p "$lock")
            if [ "$(awk '/^PPid:/ { print $2 }' "/proc/$pid/status" 2>/dev/null)" = "$1" ]; then
                sed -n 2p "$lock"
                return
            fi
        done
        sleep 0.2
    done
}

# The compositors that name their socket themselves run this script when
# they start, which writes the name
write_announce() {
    rm -f "$LOGS/socket"
    cat > "$LOGS/announce" <<EOF
#!/bin/sh
printf '%s' "\$WAYLAND_DISPLAY" > "$LOGS/socket"
EOF
    chmod +x "$LOGS/announce"
}

announced_socket() {
    for i in $(seq 1 100); do
        if [ -s "$LOGS/socket" ]; then
            cat "$LOGS/socket"
            return
        fi
        sleep 0.2
    done
}

# After the start of a compositor that announces its socket, in the
# background as $1
use_announced_socket() {
    CHILDREN="$CHILDREN $1"
    SOCKET=$(announced_socket)
    [ -n "$SOCKET" ] || fail "The nested compositor did not start, see $LOGS" 1
    wait_for_socket
    use_nested_display
    schedule_grim_screenshot
}

# A screenshot of the nested desktop after a delay, through the screen copy
# protocols of niri and wlroots
schedule_grim_screenshot() {
    if [ -n "$SCREENSHOT" ]; then
        (sleep "$SCREENSHOT_DELAY"; exec grim "$SCREENSHOT") > "$LOGS/screenshot.log" 2>&1 &
        CHILDREN="$CHILDREN $!"
    fi
}

# niri opens a window of the winit backend, whose output is named winit
start_niri() {
    export XDG_CURRENT_DESKTOP=niri
    write_announce
    cat > "$LOGS/niri.kdl" <<EOF
output "winit" {
    scale $SCALE
}
hotkey-overlay {
    skip-at-startup
}
spawn-at-startup "$LOGS/announce"
EOF
    (exec niri --config "$LOGS/niri.kdl") > "$LOGS/niri.log" 2>&1 &
    use_announced_socket $!
}

# cosmic-comp runs the command of its arguments, in a window of the winit
# backend, and ends when it ends: so the script that announces the socket
# then waits. The demos are Wayland clients only, without Xwayland
start_cosmic() {
    export XDG_CURRENT_DESKTOP=COSMIC
    write_announce
    echo 'exec sleep infinity' >> "$LOGS/announce"
    (exec cosmic-comp --no-xwayland "$LOGS/announce") > "$LOGS/cosmic.log" 2>&1 &
    use_announced_socket $!
}

# The output of the Wayland backend of wlroots is named WL-1
start_sway() {
    export XDG_CURRENT_DESKTOP=sway
    write_announce
    cat > "$LOGS/sway.conf" <<EOF
output * resolution $SIZE scale $SCALE
exec $LOGS/announce
EOF
    (exec sway --config "$LOGS/sway.conf") > "$LOGS/sway.log" 2>&1 &
    use_announced_socket $!
}

start_labwc() {
    export XDG_CURRENT_DESKTOP=labwc:wlroots
    write_announce
    mkdir -p "$LOGS/labwc"
    cp "$LOGS/announce" "$LOGS/labwc/autostart"
    printf '<?xml version="1.0"?>\n<labwc_config/>\n' > "$LOGS/labwc/rc.xml"
    (exec labwc --config-dir "$LOGS/labwc") > "$LOGS/labwc.log" 2>&1 &
    use_announced_socket $!
}

# Without the panel and the background of wf-shell, which is not installed
start_wayfire() {
    export XDG_CURRENT_DESKTOP=Wayfire
    write_announce
    cat > "$LOGS/wayfire.ini" <<EOF
[output:WL-1]
mode = $SIZE
scale = $SCALE

[autostart]
autostart_wf_shell = false
announce = $LOGS/announce
EOF
    (exec wayfire --config "$LOGS/wayfire.ini") > "$LOGS/wayfire.log" 2>&1 &
    use_announced_socket $!
}

start_weston() {
    export XDG_CURRENT_DESKTOP=weston
    (exec weston --backend=wayland --socket="$SOCKET" --width="${SIZE%x*}" --height="${SIZE#*x}" \
        --scale="$SCALE" --idle-time=0) > "$LOGS/weston.log" 2>&1 &
    CHILDREN="$CHILDREN $!"
    wait_for_socket
    use_nested_display
    screenshot_unsupported
}

# ---------------------------------------------------------------------------

main() {
    parse_options "$@"
    configure_compositor
    validate_options
    resolve_demos
    check_requirements
    enter_toolbox
    prepare_logs
    copy_plugin
    enter_private_session

    SOCKET=wayland-launcher-$COMPOSITOR-$$
    HOST_DISPLAY=$WAYLAND_DISPLAY
    BUS=$DBUS_SESSION_BUS_ADDRESS
    set_traps
    compile_demos
    if [ -n "$LAUNCHER_BUTTON_LAYOUT" ]; then
        gsettings set org.gnome.desktop.wm.preferences button-layout "$LAUNCHER_BUTTON_LAYOUT" 2>/dev/null
    fi
    "start_$COMPOSITOR"
    run_demos
    watch_owner
    wait $PIDS
}

main "$@"
