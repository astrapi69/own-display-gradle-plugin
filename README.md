# own-display-gradle-plugin

A Gradle plugin that runs every test task on a display of the build's own: an Xvfb with a window
manager, measured before the tests start and while they run, never the desktop's display.

It comes out of [mystic-crypt-ui](https://github.com/astrapi69/mystic-crypt-ui), where a test run on
the inherited `DISPLAY` clicked and typed on a person's real screen (mystic-crypt-ui#504), and is
shared by every repository of the family whose tests open windows (mystic-crypt-ui#599).

Work in progress: the first version is tracked in this repository's issues.
