# jfx-wayland

JavaFX on Wayland.

> [!WARNING]
> Most of this code was developed by an AI model. It is meant for
> demonstration and as a proof of concept only, and has not been reviewed for
> production use. Do not use it in production.

## How it works

The project is one jar for an unmodified JavaFX with a Glass on GTK 4 for
Wayland (`-Dglass.platform=Gtk4`) and two Prism pipelines, Vulkan (`vk`) and
OpenGL (`gl`). It is all Java: GTK, Vulkan, EGL and libei are called through
the Foreign Function & Memory API, with generated [bindings](#bindings).

### Bindings

- GTK 4, GDK, GLib, GObject, Gio and libadwaita: the `generateGtk4Wrappers`
  task reads their GObject introspection files (`*.gir`) and writes a Java
  class for each type the glass uses (listed in `build.gradle`), with the
  GObject hierarchy (`GtkPicture extends GtkWidget`) and methods that make
  the native calls, converting strings, objects and callbacks. A function is
  looked up the first time it is called, and `isAvailable()` tells whether
  the GTK at run time has it, so the plugin is built with the GIR files of a
  recent GTK and still runs on GTK 4.14.
- Vulkan: the `generateVulkanBindings` task reads the Vulkan registry
  (`vk.xml`) and writes a `VK` class with the constants and commands the
  pipeline uses, and a class per structure with its memory layout.
- EGL, GBM and OpenGL: a hand-written list of functions (`GLNative`), the
  OpenGL ones resolved through `eglGetProcAddress`; libei, a small
  hand-written wrapper (`Ei`).

The generated sources are kept in `src/generated`, and `./gradlew
generateBindings` regenerates them (see [Build](#build)).

### Rendering

```
Prism (vk / gl) ──► dma-buf ──► GdkDmabufTexture ──► GtkGraphicsOffload ──► compositor
```

The pipeline renders each frame into a dma-buf, a GPU buffer that can be
shared between processes. GTK shows it inside a `GtkGraphicsOffload`, which
hands the buffer to the compositor as a Wayland subsurface, so the frame
reaches the screen without being copied. A sync file attached to the buffer
lets the compositor, not the application, wait for the GPU to finish. The
software pipeline, which has no dma-buf, uploads its pixels to GTK instead.

The shaders of both pipelines are generated from the JSL sources of JavaFX.

### Robot

Wayland clients cannot inject input or read the screen, so
`javafx.scene.robot.Robot` works as the libei robot of
[jfx-tester](https://github.com/tsayao/jfx-tester) does. Its input goes
through libei, the library of the compositors for emulated input, which gets
its connection from the RemoteDesktop portal (asking the user once for
permission, with a restore token for the next sessions), or, in a nested
GNOME Shell of tests (`-Dglass.gtk4.testSession=gnome`), from its remote
desktop session, without asking. Through libei it moves the pointer, clicks,
scrolls, types and touches the screen; each event is sent in a frame and the
compositor is pinged, so that it has processed the event when the call
returns (libei 1.3; an older one, as in Ubuntu 24.04, gives the frame a
moment instead), and a device that the compositor pauses for a moment is
awaited. libei and the portal run on a thread of the robot, with a GLib main
context of its own, so no event of the application is handled in the middle
of a call of the robot (the connection and the screenshots, which may show a
dialog of the desktop, keep the application responding meanwhile). When the
compositor ends the connection, as when the session is revoked, the robot
connects again; it stops asking only when the user refuses.

The keys are those of the active layout: `keyPress(KeyCode.Q)` presses the
key that types the key code `Q` on it (as the key events give the key
codes), so that the robot types the same on QWERTY, AZERTY or ABNT2. A move
over a window of the application waits until its view gets the pointer,
instead of a fixed delay, and `getMouseX`/`getMouseY` give where the views
last saw the pointer when the user moved it after the robot.

The screen comes from the Screenshot portal, as libei cannot read it:

- `getPixelColor` opens the color picker of the desktop (`PickColor`) and
  clicks the point with the pointer, so it reads what the compositor shows
  there, then puts the pointer back. The first click comes 80 ms after the
  call (`-Dglass.gtk4.colorPicker.delay`, in milliseconds), and another one
  every 400 ms while the picker has not answered, up to 8;
- `getScreenCapture`, and `getPixelColor` when the picker does not work or a
  button is held (which its click would release), crop a screenshot of all
  the screens, which the portal saves in a file that is then deleted.

The clicks of a pick that reach the application, before the picker is shown,
are dropped by its views.

When the portal fails once (or with `-Dglass.gtk4.colorPicker=false` and
`-Dglass.gtk4.screenshot=false`), the pixels are read from the frames of the
application windows themselves.

### Windows on Wayland

Wayland does not let clients place their windows or know where they are. The
glass follows this, except in a nested GNOME Shell of tests
(`-Dglass.gtk4.testSession=gnome`, as jfx-tester runs them), where it asks the
shell to place its windows, which the tests of positions and of multiple
monitors need.

Everything else maps to GTK: windows and their decorations, input methods,
clipboard and drag and drop, file dialogs through the portal, and
accessibility, which GTK exposes to screen readers through AT-SPI.

## Requirements

- JDK 25. Gradle runs its daemon on a JDK 25 of the system
  (`gradle/gradle-daemon-jvm.properties`), whatever JDK starts it
- A JavaFX SDK of the version the plugin is built against, since it uses
  internal APIs, with no `com.sun.glass.ui.gtk4` or `com.sun.prism.vk`
  classes in `javafx.graphics`
- `libshaderc` or `glslc`, for the SPIR-V shaders

## Build

```
./gradlew jar -PjfxSdk=/path/to/jfx/build/sdk
```

The build compiles the GTK 4 wrappers and the Vulkan bindings kept in
`src/generated`, and generates the shaders: the Vulkan GLSL of the Prism and
Decora shaders from JSL, compiled to SPIR-V, and their OpenGL 4.5 core GLSL,
from the `-gl` backend of the JSL compiler, with the static Vulkan shaders
converted for OpenGL.

`./gradlew generateBindings` regenerates the wrappers and the bindings, for
a change of the types or the Vulkan commands they cover (the lists in
`build.gradle`). It needs:

- the GObject introspection files of GTK 4.22 or later (`Gtk-4.0.gir`,
  `Gdk-4.0.gir`, `GdkWayland-4.0.gir`, `Adw-1.gir`, ...). The glass checks the
  version of GTK at run time, so the plugin runs on GTK 4.14 or later;
- the Vulkan registry, `vk.xml` (Fedora: `vulkan-headers`).

| Property     | Default                              | Used by            |
|--------------|--------------------------------------|--------------------|
| `jfxSdk`     | `../jfx/build/sdk`                   | `jar`              |
| `girDir`     | `/usr/share/gir-1.0`                 | `generateBindings` |
| `vkRegistry` | `/usr/share/vulkan/registry/vk.xml`  | `generateBindings` |

## Run

The plugin runs with an unmodified JavaFX: a released SDK, the JavaFX jars
from Maven Central, or an SDK built from source. It was tested with JavaFX
27.0.1 and with a build of the master branch.

### Runtime requirements

- JDK 25
- JavaFX of the version the plugin was built against (the plugin uses its
  internal APIs), with no `com.sun.glass.ui.gtk4` or `com.sun.prism.vk`
  classes in `javafx.graphics`
- `jfx-wayland.jar`, from `build/libs` (it contains the generated wrappers and
  the SPIR-V shaders, so the GIR files, `vk.xml` and `libshaderc` are only
  needed to build it)
- GTK 4.14 or later (`libgtk-4.so.1`), which has `GtkGraphicsOffload`: the
  frames are shown in a `GtkGraphicsOffload`, so that GTK can hand the
  dma-bufs to the compositor as subsurfaces. The glass refuses to start with
  an older GTK. libadwaita is used under GNOME, when available.
- For the `vk` pipeline: the Vulkan loader (`libvulkan.so.1`) and a driver
  with Vulkan 1.3 and `VK_KHR_push_descriptor`. Frames are handed to the
  compositor as dma-bufs when the driver also has `VK_KHR_external_memory_fd`
  and `VK_EXT_external_memory_dma_buf`, and copied otherwise.
- For the `gl` pipeline (`-Dprism.order=gl`): EGL 1.5 with the GBM platform,
  `EGL_KHR_surfaceless_context` and `EGL_EXT_image_dma_buf_import`, and an
  OpenGL 4.5 core driver with `GL_OES_EGL_image`, on a render node
  (`/dev/dri/renderD128` and the next ones with a configuration without
  `EGL_CONFIG_CAVEAT`, or `-Dprism.gl.device=PATH`). It
  renders with an upper-left origin and a [0, 1] depth range
  (`glClipControl`), as Vulkan does, so both pipelines share their shaders,
  projections and exported frames: linear ARGB8888 buffers of the render
  node, synchronized with a sync file when `EGL_ANDROID_native_fence_sync` is
  available. It does not use the ES2 pipeline of JavaFX.
- A Wayland compositor (GNOME, KDE Plasma, ...). Xorg is not supported: the
  glass does not work in an X11 session, nor with `GDK_BACKEND=x11`.

These requirements are checked when JavaFX starts:

- The glass fails to start, with the reason, when GTK 4 cannot be loaded, is
  older than 4.14, or lacks `GtkGraphicsOffload` or `GdkDmabufTextureBuilder`,
  and when there is no Wayland compositor (no socket at `$WAYLAND_DISPLAY`, or
  `GDK_BACKEND` selects another backend).
- The pipelines are tried in the order of `prism.order`, `vk,gl,sw` by
  default: Vulkan, then OpenGL when Vulkan cannot be used, and the software
  pipeline when neither can. Software rasterizers (lavapipe, llvmpipe) are
  not used: they are slower than the software pipeline. The `vk` pipeline prints why Vulkan cannot be
  used (no loader, no Vulkan 1.3 device, no `VK_KHR_push_descriptor`, ...),
  and the `gl` pipeline does the same for EGL and OpenGL.
  `-Dprism.verbose=true` shows the devices and the extensions in use.

### Command line

```
java --module-path=/path/to/javafx/lib --add-modules=javafx.controls \
     --enable-native-access=javafx.graphics,ALL-UNNAMED \
     --add-exports=javafx.base/com.sun.javafx=ALL-UNNAMED \
     --add-exports=javafx.base/com.sun.javafx.logging=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.glass.events=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.glass.ui.delegate=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.javafx.application.preferences=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.javafx.geom=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.javafx.geom.transform=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism.impl=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism.impl.ps=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism.paint=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism.ps=ALL-UNNAMED \
     --add-exports=javafx.graphics/com.sun.prism.shader=ALL-UNNAMED \
     -Dglass.platform=Gtk4 -Dprism.order=vk,gl,sw \
     -cp jfx-wayland.jar:app.jar app.Main
```

The plugin is on the class path, so the exports are to `ALL-UNNAMED`. With
JavaFX on the class path too (no module path), the `--add-exports` options are
not needed, but JavaFX reports it as an unsupported configuration and the main
class must not extend `Application`.

`./gradlew -q runArgs -PjfxSdk=DIR` prints these arguments for an SDK, and the
build writes them to `build/run.args`, for `java @build/run.args` (paths under the
home directory are relative to the project directory, from where it must run).

JavaFX loads `com.sun.glass.ui.gtk4.Gtk4PlatformFactory` for
`-Dglass.platform=Gtk4`, and `com.sun.prism.vk.VKPipeline` and
`com.sun.prism.gl.GLPipeline` for the `vk` and `gl` entries of `prism.order`. With a JavaFX that supports the `ext:` prefix, the classes
can also be given by name: `-Dglass.platform=ext:com.sun.glass.ui.gtk4.Gtk4PlatformFactory`.

| System property              | Description                                             |
|------------------------------|---------------------------------------------------------|
| `glass.gtk4.backdrop=true`   | Blurs the area behind toplevel windows, see [Backdrop](#backdrop) |
| `glass.gtk4.backdrop.active` | Set by the glass: whether the backdrop is in use            |
| `glass.gtk4.adwaita=true\|false` | `AdwWindow` or `GtkWindow`; by default `AdwWindow` only under GNOME (`XDG_CURRENT_DESKTOP`), so that elsewhere the windows follow the GTK theme of the desktop |
| `glass.gtk4.portal=false`    | Uses GTK file dialogs instead of the portal             |
| `glass.gtk4.verbose=<cats>`  | Logs the given categories, or `all`                     |

## Backdrop

With `-Dglass.gtk4.backdrop=true`, the compositor blurs the area behind every
toplevel window that is not transparent, when it supports the
`ext-background-effect-v1` protocol (GNOME 51). The compositors that support
it are listed at <https://wayland.app/protocols/ext-background-effect-v1>.
The blur shows through where the scene fill and the content are transparent or
translucent, so the application sets a transparent `Scene` fill.

When the compositor does not support the protocol, the backdrop is disabled
and a warning is printed. The glass sets `glass.gtk4.backdrop.active` to `true`
or `false` before the application starts, so that the application can use an
opaque fill when there is no blur behind the window, as the demos do.

This property stands in for the `StageBackdrop` API of
[openjdk/jfx#2048](https://github.com/openjdk/jfx/pull/2048) (8375578: Enable
translucent window backdrops), which selects the backdrop per stage. When that
API is available in JavaFX, the plugin will implement it and the property will
be removed.

## Demos

`demos/` has two applications, which `launcher.sh` runs (see below):

- `eyecandy`, the showcases of the Prism renderer in one window, chosen in
  the middle of its header bar or with Ctrl+1 to Ctrl+5: Nebula (a planet, its
  moon, a galaxy and a nebula in 3D), Fractals (Mandelbrot and Julia sets
  computed in the background, geometric fractals and a Menger sponge),
  Fireworks, Disco and Aurora. `-Deyecandy.show=fractals` starts on one;
- `games`, a Breakout made with [FXGL](https://github.com/AlmasB/FXGL), the
  game engine for JavaFX: its entities, collisions, input, particles,
  animations and menus. `./gradlew fxgl` downloads FXGL first, which the
  launcher then adds to the class path. The mouse or the arrows move the
  paddle, the click or Space launches the ball, and `-Dgames.autoplay=true`
  plays by itself.

```
./launcher.sh
./launcher.sh -- -Deyecandy.show=fractals
./gradlew fxgl && ./launcher.sh --apps=games
```

![Fractals: the Mandelbrot set, a Menger sponge and geometric fractals](docs/images/fractals-mandelbrot.jpg)


![Nebula, Fireworks, Disco and Aurora](docs/images/eyecandy.jpg)

![Neon Breakout, made with FXGL](docs/images/games.jpg)

On KDE Plasma (`./launcher.sh --compositor=kde`), the windows are
`GtkWindow`s with the Breeze theme of GTK instead of libadwaita. KWin offsets
the area it blurs behind the windows from the one GTK gives it, so there the
backdrop is off (`-Dglass.gtk4.backdrop.kwin=true` turns it on, as the
launcher does for `eyecandy`):

![Nebula on KDE Plasma](docs/images/plasma-eyecandy.jpg)

## Tests

The tests of the GTK 4 glass and of the Prism pipelines are in
[jfx-tester](https://github.com/tsayao/jfx-tester), with the ones of the GTK 3
glass: a lab per area (robot, windows, screens, stage, dialogs, drag and drop,
clipboard, render, 3D, Swing, touch and accessibility), each an application
of its own. Its `--wayland` option runs them with this plugin, on Wayland
itself, in a nested GNOME Shell or KDE Plasma of a toolbox container, with
`-Dglass.gtk4.testSession=gnome` under GNOME so that windows can be placed:

```
./jfx-tester --distros=fedora:45 --headless --tests=dnd,clipboard --wayland=$HOME/jfx-wayland
./jfx-tester --distros=fedora:45 --desktops=gnome,kde --headless --tests=labs --wayland=$HOME/jfx-wayland
```

## Launcher

`launcher.sh` runs one or more demos together, on the current desktop or in a
nested compositor, with the plugin and the JavaFX SDK of `build/run.args`, so
the plugin is built first with `./gradlew jar`.

```
./launcher.sh
./launcher.sh --apps=all --compositor=kde
./launcher.sh --pipeline=sw --backdrop=off -- -Deyecandy.show=disco -Dglass.gtk4.verbose=all
```

| Option                       | Description                                                     |
|------------------------------|-----------------------------------------------------------------|
| `--apps=a,b,...`             | Demos to run, `eyecandy` or `games`, or `all` (`eyecandy`)     |
| `--compositor=none\|gnome\|kde\|hyprland` | Current desktop, or a nested compositor (`none`)        |
| `--pipeline=auto\|vk\|gl\|sw`  | Prism pipeline: Vulkan, OpenGL 4.5 or software; `auto` tries them in that order (`auto`) |
| `--backdrop=auto\|on\|off`    | Sets `glass.gtk4.backdrop`; `auto` turns it on (`auto`) |
| `--toolbox=NAME`             | Toolbox container of the compositor (`jfx-gnome51`, `jfx-kde`, `jfx-hyprland`), or `none` for the compositor of the host |
| `--toolbox-release=N`        | Fedora release of a new toolbox container, a number or `rawhide` (gnome: `45`, kde and hyprland: `rawhide`) |
| `--wallpaper=FILE`           | gnome: desktop image (the host wallpaper)                      |
| `--size=WxH`, `--scale=N`    | gnome, kde, hyprland: size of the nested output (2400x1440); kde, hyprland: its scale |
| `--monitors=WxH@S,...`       | gnome: monitors side by side, with their scales, each in a window of its own without `--headless` (`SIZE@1`) |
| `--headless`                 | gnome: no window on the desktop, virtual monitors instead; the host focus does not matter |
| `--screenshot=FILE`          | gnome, kde: screenshot of the nested desktop                   |
| `--list`, `--help`           | Lists the demos, or shows all the options                      |

Arguments after `--` are passed to the JVM of the demos. The logs are written
to `$TMPDIR/jfx-wayland-launcher-<compositor>`.

The nested compositors run in toolbox containers, so that a newer desktop
than the one of the host can be used: with `--compositor=gnome`, GNOME Shell
51 from Fedora 45 (`jfx-gnome51`), with `--compositor=kde`, KWin and the
Plasma desktop from Fedora Rawhide (`jfx-kde`), and with
`--compositor=hyprland`, Hyprland from Fedora Rawhide and the
[lionheartp/Hyprland](https://copr.fedorainfracloud.org/coprs/lionheartp/Hyprland/)
COPR, as Fedora does not ship it (`jfx-hyprland`). When the container does not
exist, the launcher asks before creating it and installing the compositor,
the JDK, GTK 4 and the Vulkan drivers in it. This needs
[toolbox](https://github.com/containers/toolbox) (the `podman-toolbox`
package on Debian and Ubuntu, `toolbox` on Fedora); without it, use
`--toolbox=none` and the compositor of the host.

