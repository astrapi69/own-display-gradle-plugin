# own-display-gradle-plugin

A Gradle plugin that runs every test task on a display of the build's own: an Xvfb with a window
manager, measured before the tests start and while they run, never the desktop's display.

It comes out of [mystic-crypt-ui](https://github.com/astrapi69/mystic-crypt-ui), where a test run on
the inherited `DISPLAY` clicked and typed on a person's real screen (mystic-crypt-ui#504), and is
shared by every repository of the family whose tests open windows (mystic-crypt-ui#599).

## Use

```groovy
plugins {
    id 'io.github.astrapi69.own-display' version '1.0'
}
```

The plugin is published to Maven Central, so the build looks there for plugins as well:

```groovy
// settings.gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Nothing else is configured. Every `Test` task of the build is affected.

## What it does

- **A display of the build's own.** The first test task that runs starts an Xvfb on a random free
  display number from 100 to 999, and fluxbox on it. It goes on only once `wmctrl` sees a window
  manager answering, because without one Swing tests wait for focus instead of failing
  (mystic-crypt-ui#322). Every test task of the build gets that display as `DISPLAY`, and as
  `E2E_HARNESS_DISPLAY` so that a test can tell the two apart. When the build ends, both are
  stopped.
- **Measured, not assumed.** The display is used only when the kernel names that Xvfb as the
  listener on the display's socket (`ss -xlpn`). On a Wayland desktop Xwayland serves `:1` as well
  as `:0`, so refusing `:0` alone is not enough (mystic-crypt-ui#597).
- **Every test JVM watched.** While the tests run, the `DISPLAY` each test JVM was started with is
  read from `/proc/<pid>/environ`. A JVM on another display, or on none without being headless, is
  killed and the run is stopped. Every later test task then refuses to start, and the build names
  the reason.
- **Headless needs nothing.** A test task with `systemProperty 'java.awt.headless', 'true'` gets no
  display at all, and none of the tools below. That is what lets it run on macOS and Windows.
- **One test task at a time.** They share one display, one window manager and one clipboard.

The harness writes what it did to `build/own-display/harness.log`: the display, the listener on its
socket, and each test JVM with the display it ran on.

## What the machine needs

Linux with `Xvfb`, `fluxbox`, `wmctrl`, `xdpyinfo` and `ss`:

```
sudo apt-get install -y xvfb fluxbox wmctrl x11-utils iproute2
```

Only for test tasks that are not headless. A build without any of them fails with the name of the
missing one instead of running the tests on the desktop.

## Watching a run on purpose

`E2E_USE_CURRENT_DISPLAY=1` runs the tests on the display the build was started with, for a run
that is meant to be watched. Nothing sets it by default.

## Building it

JDK 17, as `.sdkmanrc` names it (`sdk env`), and the tools above, since the tests run the harness
for real:

```
./gradlew build
```

The functional tests run a consumer build on every Gradle version in
`functionalTestGradleVersions` (`gradle.properties`). No test opens a window.

`git config core.hooksPath .githooks` once per clone: the hooks refuse a commit Spotless would
format, and one that credits a non-human collaborator.
