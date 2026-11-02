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

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.TestFilter;

/**
 * The last action of every test task: every test class the task would run must have left a result
 * in the task's JUnit XML directory, or the task fails and names the classes that did not.
 * <p>
 * A test class is a concrete compiled class, without {@code $} in its name, that refers to a JUnit
 * Jupiter, JUnit 4 or TestNG test annotation ({@link TestClassFile}), read from the task's classes
 * directories and narrowed by its include and exclude patterns. A result is recognised by the file
 * name ({@code TEST-<name>.xml}), the {@code <testsuite>} name or the class name of a test case:
 * none of them alone is reliable, because Gradle shortens a file name that would be too long, with
 * a hash in it, and a class-level {@code @DisplayName} becomes the suite name.
 * <p>
 * A class pattern the build sets narrows the expected classes. A run narrowed on the command line
 * ({@code --tests}), or by a pattern whose last part names a method, is not checked, and says so.
 * <p>
 * A class rather than a lambda, and with no reference to the project, so that the configuration
 * cache can store it. What the build may still change after the plugin is applied reaches it as a
 * lazy file collection and a provider.
 */
final class CheckThatEveryTestClassLeftAResult implements Action<Task>
{

	/** How much of a result file is read: the suite name and the test cases come first */
	private static final int HEAD_LENGTH = 65536;

	/** The suite name in a result file */
	private static final Pattern SUITE_NAME = Pattern.compile("<testsuite\\s+name=\"([^\"]+)\"");

	/** The class name of a test case in a result file */
	private static final Pattern CASE_CLASS_NAME = Pattern.compile("classname=\"([^\"]+)\"");

	/** The task's classes directories, resolved when the task graph is complete */
	private final FileCollection classesDirs;

	/** The directory the task writes its JUnit XML results to */
	private final Provider<Directory> resultsDirectory;

	/** The classes the build declares without a result, each with its reason */
	private final Provider<Map<String, String>> declaredWithoutResult;

	CheckThatEveryTestClassLeftAResult(FileCollection classesDirs,
		Provider<Directory> resultsDirectory, Provider<Map<String, String>> declaredWithoutResult)
	{
		this.classesDirs = classesDirs;
		this.resultsDirectory = resultsDirectory;
		this.declaredWithoutResult = declaredWithoutResult;
	}

	@Override
	public void execute(Task task)
	{
		Test test = (Test)task;
		Map<String, String> declared = declaredWithoutResult.get();
		if (narrowedByAFilter(test))
		{
			test.getLogger().lifecycle(
				test.getName() + ": narrowed by a filter, not checked for missing results");
			return;
		}
		Set<String> expected = expectedClasses(test);
		List<File> resultFiles = resultFiles();
		if (expected.isEmpty())
		{
			nothingToExpect(test, resultFiles);
			return;
		}
		compare(test, expected, recordedClasses(resultFiles), declared);
	}

	/**
	 * A run narrowed on the command line, or by a pattern that names a method, leaves classes out
	 * that this check cannot tell
	 */
	private static boolean narrowedByAFilter(Test test)
	{
		TestFilter filter = test.getFilter();
		return !commandLineIncludePatterns(test).isEmpty() || Stream
			.concat(filter.getIncludePatterns().stream(), filter.getExcludePatterns().stream())
			.anyMatch(CheckThatEveryTestClassLeftAResult::namesAMethod);
	}

	/**
	 * The patterns of {@code --tests}. {@link TestFilter} has no public getter for them, Gradle's
	 * own filter has one (the internal {@code DefaultTestFilter}, in 8.8 as in 9.7.1), and it is
	 * read the way the Groovy original reached it, by name. A filter without it stops the check
	 * rather than letting it guess.
	 */
	private static Collection<?> commandLineIncludePatterns(Test test)
	{
		TestFilter filter = test.getFilter();
		try
		{
			return (Collection<?>)filter.getClass().getMethod("getCommandLineIncludePatterns")
				.invoke(filter);
		}
		catch (ReflectiveOperationException | ClassCastException exception)
		{
			throw new GradleException(test.getName()
				+ ": cannot tell whether the run was narrowed on the command line, the test filter "
				+ filter.getClass().getName() + " has no getCommandLineIncludePatterns()",
				exception);
		}
	}

	/** A pattern whose last part starts in lower case and has no wildcard names a method */
	private static boolean namesAMethod(String pattern)
	{
		List<String> parts = Arrays.stream(pattern.split("\\.")).filter(part -> !part.isEmpty())
			.toList();
		if (parts.isEmpty())
		{
			return false;
		}
		String last = parts.get(parts.size() - 1);
		return Character.isLowerCase(last.charAt(0)) && !last.contains("*");
	}

	/**
	 * The test classes the task would run: its classes directories, narrowed by its include and
	 * exclude patterns and by the class patterns of its filter
	 */
	private Set<String> expectedClasses(Test test)
	{
		FileTree candidates = classesDirs.getAsFileTree().matching(patterns -> {
			patterns.include(test.getIncludes());
			patterns.exclude(test.getExcludes());
		});
		List<Pattern> includes = asRegularExpressions(test.getFilter().getIncludePatterns());
		List<Pattern> excludes = asRegularExpressions(test.getFilter().getExcludePatterns());
		Set<String> expected = new TreeSet<>();
		candidates.visit(details -> {
			if (isConcreteTestClass(details))
			{
				String className = className(details);
				if (matchesAny(includes, className, true)
					&& !matchesAny(excludes, className, false))
				{
					expected.add(className);
				}
			}
		});
		return expected;
	}

	private static boolean isConcreteTestClass(FileVisitDetails details)
	{
		if (details.isDirectory() || !details.getName().endsWith(".class")
			|| details.getName().contains("$"))
		{
			return false;
		}
		try
		{
			return TestClassFile.isConcreteTestClass(Files.readAllBytes(details.getFile().toPath()),
				details.getFile().toString());
		}
		catch (IOException exception)
		{
			throw new GradleException("cannot read " + details.getFile(), exception);
		}
	}

	private static String className(FileVisitDetails details)
	{
		String path = details.getRelativePath().getPathString();
		return path.substring(0, path.length() - ".class".length()).replace('/', '.');
	}

	/**
	 * A filter pattern as a regular expression: {@code *} stands for anything, the rest for itself
	 */
	private static List<Pattern> asRegularExpressions(Set<String> patterns)
	{
		return patterns.stream()
			.map(pattern -> Pattern.compile(Arrays.stream(pattern.split("\\*", -1))
				.map(Pattern::quote).collect(Collectors.joining(".*"))))
			.toList();
	}

	private static boolean matchesAny(List<Pattern> patterns, String className,
		boolean whenThereAreNone)
	{
		if (patterns.isEmpty())
		{
			return whenThereAreNone;
		}
		return patterns.stream().anyMatch(pattern -> pattern.matcher(className).matches());
	}

	private List<File> resultFiles()
	{
		File[] files = resultsDirectory.get().getAsFile().listFiles();
		if (files == null)
		{
			return List.of();
		}
		return Arrays.stream(files)
			.filter(file -> file.getName().startsWith("TEST-") && file.getName().endsWith(".xml"))
			.toList();
	}

	/** Every name a result file gives its class: the file name, the suite name, the case classes */
	private static Set<String> recordedClasses(List<File> resultFiles)
	{
		Set<String> recorded = new HashSet<>();
		for (File file : resultFiles)
		{
			String name = file.getName();
			recorded.add(name.substring("TEST-".length(), name.length() - ".xml".length()));
			String head = head(file);
			Matcher suite = SUITE_NAME.matcher(head);
			if (suite.find())
			{
				recorded.add(suite.group(1));
			}
			Matcher caseClass = CASE_CLASS_NAME.matcher(head);
			while (caseClass.find())
			{
				recorded.add(caseClass.group(1));
			}
		}
		return recorded;
	}

	/** The first characters of a result file, where the suite and its test cases are named */
	private static String head(File file)
	{
		char[] buffer = new char[HEAD_LENGTH];
		int length = 0;
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			int read;
			while (length < buffer.length
				&& (read = reader.read(buffer, length, buffer.length - length)) > 0)
			{
				length += read;
			}
		}
		catch (IOException exception)
		{
			throw new GradleException("cannot read the test result " + file, exception);
		}
		return new String(buffer, 0, length);
	}

	/**
	 * No test class: a task without results has nothing to check, and says so; a task with results
	 * cannot check, and fails instead of reporting green
	 */
	private static void nothingToExpect(Test test, List<File> resultFiles)
	{
		if (!resultFiles.isEmpty())
		{
			throw new GradleException(test.getName() + ": " + resultFiles.size()
				+ " result files, but no compiled class refers to a test annotation this check knows"
				+ " (JUnit Jupiter, JUnit 4, TestNG), so it cannot tell whether every test class ran");
		}
		test.getLogger().lifecycle(test.getName() + ": no test classes, nothing to check");
	}

	/** Every expected class has a result, or is declared without one, or the task fails */
	private static void compare(Test test, Set<String> expected, Set<String> recorded,
		Map<String, String> declared)
	{
		List<String> missing = new ArrayList<>();
		List<String> excused = new ArrayList<>();
		for (String className : expected)
		{
			if (!recorded.contains(className))
			{
				(declared.containsKey(className) ? excused : missing).add(className);
			}
		}
		for (String className : excused)
		{
			test.getLogger().lifecycle(test.getName() + ": " + className
				+ " leaves no result, as declared: " + declared.get(className));
		}
		if (!missing.isEmpty())
		{
			throw new GradleException(test.getName() + ": " + missing.size() + " of "
				+ expected.size() + " test classes left no result, so the test JVM ended before"
				+ " they ran or they were never run (System.exit?): " + String.join(", ", missing));
		}
		test.getLogger()
			.lifecycle(test.getName() + ": all " + (expected.size() - excused.size())
				+ " test classes left a result"
				+ (excused.isEmpty() ? "" : ", " + excused.size() + " declared without one"));
	}
}
