# own-display-gradle-plugin

A Gradle plugin that runs every test task on a display of the build's own: an Xvfb with a window
manager, measured before the tests start and while they run, never the desktop's display.

It comes out of [mystic-crypt-ui](https://github.com/astrapi69/mystic-crypt-ui), where a test run on
the inherited `DISPLAY` clicked and typed on a person's real screen (mystic-crypt-ui#504), and is
shared by every repository of the family whose tests open windows (mystic-crypt-ui#599).

The same artifact has a second plugin ID, `io.github.astrapi69.test-results-complete`, which
checks that every test class of a test task left a result. It needs no display and can be applied
alone, or next to `own-display` (see [Every test class leaves a result](#every-test-class-leaves-a-result)).

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

Both plugin IDs are compiled for Java 25, so the build that applies one runs Gradle on Java 25 or
later: Gradle 8.14.4 or a later 8.14 release, or Gradle 9.1.0 or later (#13). Measured on Java 25
with the functional tests: 8.14.4, 8.14.6, 9.1.0, 9.7.0 and 9.8.2 load and run both plugins; 8.8,
8.14 and 8.14.3 cannot compile a build script on Java 25, and 9.0.0 runs on it but cannot read class
files of Java 25 (`Unsupported class file major version 69`), so it cannot load the plugins.

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

## Every test class leaves a result

```groovy
plugins {
    id 'io.github.astrapi69.test-results-complete' version '1.0'
}
```

The `pluginManagement` block and the Gradle versions above apply to it as well. Nothing else is
configured, and every `Test` task of the build is checked.

A test that ends the test JVM, with `System.exit(0)` as awt-extensions' `CloseWindow` does when
its window is closed, leaves every class after it without a result, and Gradle still reports the
task green, because the worker exited with 0 (swing-components#34, mystic-crypt-ui#288). So after
each test task:

- **Every test class must have left a result.** A test class is a concrete compiled class of the
  task's classes directories (not abstract, not an interface, no `$` in its name), narrowed by the
  task's include and exclude patterns, that refers to a JUnit Jupiter (`Test`, `ParameterizedTest`,
  `TestFactory`, `RepeatedTest`, `TestTemplate`), JUnit 4 or TestNG test annotation. Its result is
  a `TEST-*.xml` in the task's JUnit XML directory whose file name, `<testsuite>` name or test case
  `classname` names it: Gradle shortens a file name that would be too long, with a hash in it,
  and a class-level `@DisplayName` becomes the suite name. Otherwise the task fails and names the
  classes:
  `test: 2 of 3 test classes left no result, so the test JVM ended before they ran or they were
  never run (System.exit?): org.example.BRunsTest, org.example.CExitTest`.
- **Read when the task has run.** A test task that sets `testClassesDirs` in its own `register`
  block is checked against those directories (mystic-crypt-ui#699).
- **Filters.** A class pattern the build sets (`filter { includeTestsMatching 'org.example.*' }`)
  narrows the expected classes. A run narrowed on the command line (`--tests`), or by a pattern
  whose last part names a method, is not checked, and says so:
  `test: narrowed by a filter, not checked for missing results`.
- **No test classes.** A task without test classes and without results says so and passes. A task
  with results but no class the check recognises fails, because it cannot tell.
- **Configuration cache.** The check refers to no project, so the configuration cache stores it
  and reuses it.

When every class left a result, the task says how many it checked:
`test: all 3 test classes left a result`.

A class that is known to leave no result (the original's example: TestNG writes none for a class
whose every test has `enabled = false`) is declared in the build script, with its reason. The line may stand anywhere in
the build script, before or after the `plugins` block has applied the plugin:

```groovy
ext.testClassesWithoutResult = ['org.example.SomeTest': 'every test has enabled = false']
```

The task then names it with its reason, and counts it apart:
`test: org.example.SomeTest leaves no result, as declared: every test has enabled = false`,
`test: all 2 test classes left a result, 1 declared without one`.

It comes from mystic-crypt-ui's `gradle/test-results-complete.gradle` (mystic-crypt-ui#699), whose
copies the family's repositories carry. The check needs none of the tools that `own-display` needs.

## Building it

JDK 25, as `.sdkmanrc` names it (`sdk env`), and the tools above, since the tests run the harness
for real:

```
./gradlew build
```

The functional tests run a consumer build on every Gradle version in
`functionalTestGradleVersions` (`gradle.properties`). No test opens a window.

`git config core.hooksPath .githooks` once per clone: the hooks refuse a commit Spotless would
format, and one that credits a non-human collaborator.
