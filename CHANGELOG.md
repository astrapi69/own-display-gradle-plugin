## Change log
----------------------

Version 1.0 (unreleased)
-------------

ADDED:
- every test task runs on a display of the build's own: an Xvfb on a random free number from 100 to 999 with
  fluxbox answering on it, used only when the kernel names that Xvfb as the listener on the display's socket.
  Every test JVM's `DISPLAY` is read from `/proc` while it runs, and a JVM on another display, or on none without
  being headless, stops the run. A headless test task gets no display at all. The harness and its tests come from
  mystic-crypt-ui as of astrapi69/mystic-crypt-ui#597 (#1)
