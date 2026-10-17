/**
 * The MIT License
 *
 * Copyright (C) 2026 Asterios Raptis
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.github.astrapi69.own.display;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;

/**
 * Runs every {@link Test} task of the build on a display of the build's own, never on the one the
 * build was started from.
 * <p>
 * The first test task that executes makes the {@link OwnDisplay} service start the harness: an Xvfb
 * on a random free display number, a window manager that answers, both measured. Every test task of
 * the build then gets that display as {@code DISPLAY}, and as {@code E2E_HARNESS_DISPLAY}, so that
 * a test can tell it apart from an inherited one. The harness watches every test JVM while it runs
 * and stops the run when one of them is on another display. When the build ends, the service stops
 * the harness, and the harness what it started.
 * <p>
 * A test task that sets the system property {@code java.awt.headless} to {@code true} gets no
 * display at all, and needs neither Xvfb nor the harness's tools.
 * <p>
 * The test tasks run one at a time: they share one display, one window manager and one clipboard.
 * <p>
 * It comes from mystic-crypt-ui, where a test run on the inherited {@code DISPLAY} clicked and
 * typed on a person's real screen (astrapi69/mystic-crypt-ui#504, #505, #597).
 */
public class OwnDisplayPlugin implements Plugin<Project>
{

	/** The name the service is registered under, once for the whole build */
	static final String SERVICE_NAME = "ownDisplay";

	/** The directory below the root project's build directory where the harness works and logs */
	static final String WORK_DIRECTORY = "own-display";

	@Override
	public void apply(Project project)
	{
		Provider<OwnDisplay> ownDisplay = project.getGradle().getSharedServices()
			.registerIfAbsent(SERVICE_NAME, OwnDisplay.class, service -> {
				service.getMaxParallelUsages().set(1);
				service.getParameters().getWorkDirectory().set(
					project.getRootProject().getLayout().getBuildDirectory().dir(WORK_DIRECTORY));
			});
		project.getTasks().withType(Test.class).configureEach(test -> {
			test.usesService(ownDisplay);
			test.doFirst(new GiveTheTestTaskADisplay(ownDisplay));
		});
	}
}
