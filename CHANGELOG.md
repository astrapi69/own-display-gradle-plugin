## Change log
----------------------

Version 1.0
-------------

CHANGED:
- both plugin IDs are compiled for Java 25, class file version 69, so a build that applies one runs Gradle on
  Java 25: Gradle 8.14.4 or a later 8.14 release, or Gradle 9.1.0 or later, measured with the functional tests,
  which run on 8.14.4 and 9.8.2. Gradle 9.0.0 runs on Java 25 but cannot load the plugins (#13)
- the build runs on JDK 25 in every workflow and on Gradle 9.8.2, its wrapper jar checked against the published
  sha256; a `.gitattributes` keeps the line endings of the wrapper scripts (#13)

ADDED:
- a second plugin ID, `io.github.astrapi69.test-results-complete`: after each test task, every concrete compiled
  class that refers to a JUnit Jupiter, JUnit 4 or TestNG test annotation must have left a result in the task's
  JUnit XML, or the task fails and names the classes, so a test that ends the test JVM with `System.exit` can no
  longer end the task green over classes that never ran. The classes are read when the task has run, so a task
  that sets `testClassesDirs` in its own `register` block is checked too; a class pattern of the build narrows the
  expected classes, a run narrowed with `--tests` or to a method is not checked and says so, and a class known to
  leave no result is declared in `ext.testClassesWithoutResult` with its reason. The configuration cache stores
  and reuses the check. Ported from astrapi69/mystic-crypt-ui's `gradle/test-results-complete.gradle` as of
  `3510e99f` (astrapi69/mystic-crypt-ui#699, #11)
- every test JVM gets `NO_AT_BRIDGE=1`, so the GTK modules a desktop session sets (`GTK_MODULES=gail:atk-bridge`)
  do not connect it to the accessibility bus and print AT-SPI warnings past the test results, as in
  astrapi69/mystic-crypt-ui#695 (#9)
- every test task runs on a display of the build's own: an Xvfb on a random free number from 100 to 999 with
  fluxbox answering on it, used only when the kernel names that Xvfb as the listener on the display's socket.
  Every test JVM's `DISPLAY` is read from `/proc` while it runs, and a JVM on another display, or on none without
  being headless, stops the run. A headless test task gets no display at all. The harness and its tests come from
  mystic-crypt-ui as of astrapi69/mystic-crypt-ui#597 (#1)

FIXED:
- each plugin marker's POM carries the display name and the description of its own plugin; both markers had
  carried the artifact's name and the display check's description, the marker of `test-results-complete`
  included. The artifact's description names both plugins (#14)
