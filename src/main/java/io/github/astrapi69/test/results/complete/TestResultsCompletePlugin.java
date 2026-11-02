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
package io.github.astrapi69.test.results.complete;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;

/**
 * Checks after every {@link Test} task of the build that each of its test classes left a result,
 * and fails the task, naming the classes, when one did not.
 * <p>
 * A test that ends the test JVM, with {@code System.exit(0)} as awt-extensions' {@code CloseWindow}
 * does when its window is closed, leaves every class after it without a result, and Gradle still
 * reports the task green, because the worker exited with 0 (astrapi69/swing-components#34,
 * astrapi69/mystic-crypt-ui#288). The check is {@link CheckThatEveryTestClassLeftAResult}.
 * <p>
 * A class that is known to leave no result is declared in the build script, with its reason, as
 * {@code ext.testClassesWithoutResult = ['org.example.SomeTest': 'reason']}, anywhere in it.
 * <p>
 * It comes from mystic-crypt-ui's {@code gradle/test-results-complete.gradle}
 * (astrapi69/mystic-crypt-ui#699). What the build may still change after the plugin is applied, the
 * classes directories and the declared classes, reaches the check as a lazy file collection and a
 * provider: a task registered with a source set of its own sets its classes directories in its
 * register block, which runs after every configureEach.
 */
public class TestResultsCompletePlugin implements Plugin<Project>
{

	/** The build script property that declares classes without a result, with their reasons */
	static final String DECLARED_WITHOUT_RESULT = "testClassesWithoutResult";

	@Override
	public void apply(Project project)
	{
		Provider<Map<String, String>> declared = project
			.provider(() -> declaredWithoutResult(project));
		project.getTasks().withType(Test.class).configureEach(test -> {
			FileCollection classesDirs = project
				.files((Callable<FileCollection>)test::getTestClassesDirs);
			test.doLast(new CheckThatEveryTestClassLeftAResult(classesDirs,
				test.getReports().getJunitXml().getOutputLocation(), declared));
		});
	}

	/** What the build script declares, by class name, read when the provider is */
	private static Map<String, String> declaredWithoutResult(Project project)
	{
		Object declared = project.findProperty(DECLARED_WITHOUT_RESULT);
		Map<String, String> byClassName = new TreeMap<>();
		if (declared == null)
		{
			return byClassName;
		}
		if (!(declared instanceof Map<?, ?> reasons))
		{
			throw new GradleException("ext." + DECLARED_WITHOUT_RESULT
				+ " maps a class name to its reason, as ['org.example.SomeTest': 'reason'], and is a "
				+ declared.getClass().getName());
		}
		reasons.forEach((className, reason) -> byClassName.put(String.valueOf(className),
			String.valueOf(reason)));
		return byClassName;
	}
}
