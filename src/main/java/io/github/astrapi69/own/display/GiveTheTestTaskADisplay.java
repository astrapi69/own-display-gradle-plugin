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

import java.util.HashMap;
import java.util.Map;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;

/**
 * The first action of every test task: it gives the task's JVM the build's own display, or no
 * display at all when the task runs headless.
 * <p>
 * A class rather than a lambda, so that the configuration cache can store it.
 */
final class GiveTheTestTaskADisplay implements Action<Task>
{

	private final Provider<OwnDisplay> ownDisplay;

	GiveTheTestTaskADisplay(Provider<OwnDisplay> ownDisplay)
	{
		this.ownDisplay = ownDisplay;
	}

	@Override
	public void execute(Task task)
	{
		Test test = (Test)task;
		if (runsHeadless(test))
		{
			withoutDisplay(test);
			test.getLogger().lifecycle("{}: tests run headless, on no display", test.getName());
			return;
		}
		String display = ownDisplay.get().display();
		test.environment("DISPLAY", display);
		test.environment("E2E_HARNESS_DISPLAY", display);
		test.getLogger().lifecycle(
			"{}: tests run on display {}, from the own-display plugin (its output: {})",
			test.getName(), display, ownDisplay.get().log());
	}

	/** A headless JVM cannot open a window, so it needs no display and no Xvfb */
	private static boolean runsHeadless(Test test)
	{
		return "true".equals(String.valueOf(test.getSystemProperties().get("java.awt.headless")));
	}

	/** No DISPLAY either, so that nothing in the JVM reaches the screen the build was started on */
	private static void withoutDisplay(Test test)
	{
		Map<String, Object> environment = new HashMap<>(test.getEnvironment());
		environment.remove("DISPLAY");
		test.setEnvironment(environment);
	}
}
