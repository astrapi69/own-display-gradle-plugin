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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs {@code io.github.astrapi69.test-results-complete} the way a consuming repository does: in a
 * Gradle build of its own, through TestKit, on every Gradle version in
 * {@code functionalTestGradleVersions}, and reads what the check said and whether the build failed.
 * <p>
 * The consumer's tests run headless and open no window. The ones that end the test JVM call
 * {@code System.exit(0)}, as awt-extensions' {@code CloseWindow} does when its window is closed
 * (astrapi69/swing-components#34, astrapi69/mystic-crypt-ui#288). JUnit Jupiter runs the classes in
 * the order of their names, so which classes come after the one that ends the JVM is known.
 */
class TestResultsCompletePluginFunctionalTest
{

	/** The consumer's build file with JUnit Jupiter, and the placeholder for what a case adds */
	private static final String BUILD_FILE = """
		plugins {
		    id 'java'
		    id 'io.github.astrapi69.test-results-complete'
		}
		repositories {
		    mavenCentral()
		}
		dependencies {
		    testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
		    testRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
		}
		tasks.withType(Test).configureEach {
		    useJUnitPlatform()
		    systemProperty 'java.awt.headless', 'true'
		    systemProperty 'junit.jupiter.testclass.order.default', 'org.junit.jupiter.api.ClassOrderer$ClassName'
		}
		%s
		""";

	/** The consumer's build file with another test framework: its dependency and its use line */
	private static final String OTHER_FRAMEWORK_BUILD_FILE = """
		plugins {
		    id 'java'
		    id 'io.github.astrapi69.test-results-complete'
		}
		repositories {
		    mavenCentral()
		}
		dependencies {
		    testImplementation '%s'
		}
		test {
		    %s
		    systemProperty 'java.awt.headless', 'true'
		}
		""";

	/**
	 * A test task with a source set of its own, its classes directories set in its register block:
	 * that block runs after every configureEach, the plugin's included, as the lethenon plugin's
	 * zipTest does (astrapi69/mystic-crypt-ui#699)
	 */
	private static final String LATE_TASK = """
		sourceSets {
		    late
		}
		dependencies {
		    lateImplementation 'org.junit.jupiter:junit-jupiter:5.11.4'
		    lateRuntimeOnly 'org.junit.platform:junit-platform-launcher:1.11.4'
		}
		tasks.register('lateTest', Test) {
		    testClassesDirs = sourceSets.late.output.classesDirs
		    classpath = sourceSets.late.runtimeClasspath
		}
		""";

	/** A class pattern the build sets, as a guard against foreign classes on the path does */
	private static final String GUARDED_BY_A_CLASS_PATTERN = """
		test {
		    filter {
		        includeTestsMatching 'org.example.guarded.*'
		    }
		}
		""";

	/** A pattern the build sets whose last part names a method */
	private static final String NARROWED_TO_A_METHOD = """
		test {
		    filter {
		        includeTestsMatching 'org.example.guarded.FirstTest.runs'
		    }
		}
		""";

	/** A class whose tag the task excludes, declared at the end of the build script, after apply */
	private static final String DECLARED_WITHOUT_RESULT = """
		test {
		    useJUnitPlatform {
		        excludeTags 'manual'
		    }
		}
		ext.testClassesWithoutResult = ['org.example.ManualTest': 'runs only by hand, its tag is excluded']
		""";

	/** A JUnit Jupiter test class that passes */
	private static final String RUNS = """
		package %s;

		class %s {
		    @org.junit.jupiter.api.Test
		    void runs() {
		    }
		}
		""";

	/** A JUnit Jupiter test class that ends the test JVM with exit code 0 */
	private static final String ENDS_THE_JVM = """
		package %s;

		class %s {
		    @org.junit.jupiter.api.Test
		    void endsTheTestJvm() {
		        System.exit(0);
		    }
		}
		""";

	/** A JUnit Jupiter test class with a class-level display name, which becomes the suite name */
	private static final String RUNS_UNDER_A_DISPLAY_NAME = """
		package %s;

		@org.junit.jupiter.api.DisplayName("a class named for people")
		class %s {
		    @org.junit.jupiter.api.Test
		    void runs() {
		    }
		}
		""";

	/** A JUnit Jupiter test class tagged for running by hand */
	private static final String TAGGED_MANUAL = """
		package %s;

		@org.junit.jupiter.api.Tag("manual")
		class %s {
		    @org.junit.jupiter.api.Test
		    void byHand() {
		    }
		}
		""";

	/** A TestNG test class */
	private static final String TESTNG = """
		package %s;

		public class %s {
		    @org.testng.annotations.Test
		    public void runs() {
		    }
		}
		""";

	/** A JUnit 4 test class */
	private static final String JUNIT4 = """
		package %s;

		public class %s {
		    @org.junit.Test
		    public void runs() {
		    }
		}
		""";

	/** An interface whose default method is a test: it never runs on its own */
	private static final String CONTRACT = """
		package org.example;

		interface TestContract {
		    @org.junit.jupiter.api.Test
		    default void fromTheContract() {
		    }
		}
		""";

	/** An abstract class with a test: it never runs on its own */
	private static final String ABSTRACT_BASE = """
		package org.example;

		abstract class AbstractBase {
		    @org.junit.jupiter.api.Test
		    void fromTheBase() {
		    }
		}
		""";

	/** A test class with a base class and an interface, both with tests of their own */
	private static final String WITH_BASE_AND_CONTRACT = """
		package org.example;

		class FirstTest extends AbstractBase implements TestContract {
		    @org.junit.jupiter.api.Test
		    void first() {
		    }
		}
		""";

	/** A class-level display name, which becomes the suite name, and a nested class */
	private static final String NAMED_FOR_PEOPLE = """
		package org.example;

		@org.junit.jupiter.api.DisplayName("a class named for people")
		class NamedTest {
		    @org.junit.jupiter.api.Test
		    void named() {
		    }

		    @org.junit.jupiter.api.Nested
		    class Inner {
		        @org.junit.jupiter.api.Test
		        void inner() {
		        }
		    }
		}
		""";

	/** Gradle 9 fails a test task that discovers no test; what the check says is under test here */
	private static final String NO_TEST_DISCOVERED_IS_FINE = """
		tasks.named('test') { Test task ->
		    if (task.hasProperty('failOnNoDiscoveredTests')) {
		        task.failOnNoDiscoveredTests = false
		    }
		}
		""";

	/**
	 * A test annotation of the build's own, which JUnit Jupiter runs and this check does not know
	 */
	private static final String META_ANNOTATION = """
		package org.example;

		@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
		@java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
		@org.junit.jupiter.api.Test
		@interface Check {
		}
		""";

	/** A test class whose test carries only the build's own annotation */
	private static final String META_ANNOTATED = """
		package org.example;

		class MetaAnnotatedTest {
		    @Check
		    void runs() {
		    }
		}
		""";

	/** A class with no test annotation */
	private static final String HELPER = """
		package org.example;

		class Helper {
		    static String help() {
		        return "help";
		    }
		}
		""";

	/**
	 * A class whose name is too long for a file name of {@code TEST-<name>.xml}, 255 bytes on
	 * Linux: Gradle 9.7.1 shortens it, to {@code TEST-org.example.segment-CIN39QJE600OC.segment...}
	 * (measured on #11)
	 */
	private static final String LONG_NAME = "org.example." + "segment".repeat(9) + "a."
		+ "segment".repeat(9) + "b." + "segment".repeat(9) + "c." + "segment".repeat(9)
		+ "d.LongNameTest";

	/** What the check says when a class left no result after the first one ended the test JVM */
	private static final String TWO_LEFT_NO_RESULT = "test: 2 of 3 test classes left no result, "
		+ "so the test JVM ended before they ran or they were never run (System.exit?): "
		+ "org.example.BRunsTest, org.example.CExitTest";

	static Stream<String> gradleVersions()
	{
		return Arrays.stream(System.getProperty("functionalTestGradleVersions").split(","))
			.map(String::trim);
	}

	static String newestGradleVersion()
	{
		List<String> versions = gradleVersions().toList();
		return versions.get(versions.size() - 1);
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("every concrete test class left a result: the task passes and says how many it checked")
	void everyTestClassLeftAResult_passes_withTheSuccessLine(String gradleVersion,
		@TempDir File project) throws IOException
	{
		consumer(project, "");
		write(new File(project, "src/test/java/org/example/TestContract.java"), CONTRACT);
		write(new File(project, "src/test/java/org/example/AbstractBase.java"), ABSTRACT_BASE);
		write(new File(project, "src/test/java/org/example/FirstTest.java"),
			WITH_BASE_AND_CONTRACT);
		write(new File(project, "src/test/java/org/example/NamedTest.java"), NAMED_FOR_PEOPLE);
		write(new File(project, "src/test/java/org/example/Helper.java"), HELPER);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome(), result.getOutput());
		assertTrue(result.getOutput().contains("test: all 2 test classes left a result\n"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("newestGradleVersionOnly")
	@DisplayName("a class whose result file name Gradle shortens and whose suite carries its display name is recognised by its test cases")
	void aClassWithAShortenedResultFileName_isRecognised(String gradleVersion,
		@TempDir File project) throws IOException
	{
		consumer(project, "");
		testClass(project, "test", LONG_NAME, RUNS_UNDER_A_DISPLAY_NAME);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertTrue(result.getOutput().contains("test: all 1 test classes left a result\n"),
			result.getOutput());
		assertFalse(resultFile(project, "test", LONG_NAME).exists(),
			"Gradle shortened the long name, so the file name alone does not recognise it");
	}

	/**
	 * Gradle 8.8 does not shorten a result file name that is too long: it fails the task with
	 * "Could not write XML test results for ..." (measured on #11), and neither do 8.14.4, 8.14.6
	 * and 9.1.0 (measured on #13), so the case runs on the newest version only
	 */
	static Stream<String> newestGradleVersionOnly()
	{
		return Stream.of(newestGradleVersion());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a test class that ends the test JVM with System.exit(0): the build fails and names the classes that left no result")
	void aTestEndsTheTestJvm_failsTheBuild_namingTheClassesWithoutAResult(String gradleVersion,
		@TempDir File project) throws IOException
	{
		endsTheJvmConsumer(project, "");

		BuildResult result = runner(project, gradleVersion, "test").buildAndFail();

		assertEquals(TaskOutcome.FAILED, result.task(":test").getOutcome(), result.getOutput());
		assertTrue(result.getOutput().contains(TWO_LEFT_NO_RESULT), result.getOutput());
		assertFalse(resultFile(project, "test", "org.example.BRunsTest").exists(),
			"the class after the one that ended the JVM never ran");
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a test task that sets its classes directories in its own register block is checked against them (astrapi69/mystic-crypt-ui#699)")
	void aTestTaskWithItsOwnClassesDirectories_isCheckedAgainstThem(String gradleVersion,
		@TempDir File project) throws IOException
	{
		consumer(project, LATE_TASK);
		testClass(project, "late", "org.example.LateTest", RUNS);

		BuildResult result = runner(project, gradleVersion, "lateTest").build();

		assertEquals(TaskOutcome.SUCCESS, result.task(":lateTest").getOutcome(),
			result.getOutput());
		assertTrue(result.getOutput().contains("lateTest: all 1 test classes left a result\n"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a class pattern the build sets narrows the expected classes: a class outside it is not expected")
	void aClassPatternOfTheBuild_narrowsTheExpectedClasses(String gradleVersion,
		@TempDir File project) throws IOException
	{
		guardedConsumer(project, GUARDED_BY_A_CLASS_PATTERN);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertTrue(result.getOutput().contains("test: all 2 test classes left a result\n"),
			result.getOutput());
		assertFalse(resultFile(project, "test", "org.example.other.OutsideTest").exists(),
			"the class outside the pattern did not run, and was not expected to");
	}

	@ParameterizedTest(name = "Gradle {0}, {1}")
	@MethodSource("narrowedRuns")
	@DisplayName("a run narrowed on the command line, or to a method, is not checked, and says so")
	void aNarrowedRun_isNotChecked_andSaysSo(String gradleVersion, String kind,
		String buildAddition, List<String> arguments, @TempDir File project) throws IOException
	{
		guardedConsumer(project, buildAddition);

		BuildResult result = runner(project, gradleVersion, arguments.toArray(String[]::new))
			.build();

		assertTrue(result.getOutput().contains(
			"test: narrowed by a filter, not checked for missing results"), result.getOutput());
		assertFalse(result.getOutput().contains("test classes left"), result.getOutput());
		assertFalse(resultFile(project, "test", "org.example.guarded.SecondTest").exists(),
			"a class the run left out would have counted as missing");
	}

	static Stream<Arguments> narrowedRuns()
	{
		return gradleVersions().flatMap(version -> Stream.of(
			Arguments.of(version, "--tests on the command line", "",
				List.of("test", "--tests", "org.example.guarded.FirstTest")),
			Arguments.of(version, "a pattern of the build naming a method", NARROWED_TO_A_METHOD,
				List.of("test"))));
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a test task without test classes and without results says so, and passes")
	void aTaskWithoutTestClassesOrResults_saysSo_andPasses(String gradleVersion,
		@TempDir File project) throws IOException
	{
		consumer(project, NO_TEST_DISCOVERED_IS_FINE);
		write(new File(project, "src/test/java/org/example/Helper.java"), HELPER);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertEquals(TaskOutcome.SUCCESS, result.task(":test").getOutcome(), result.getOutput());
		assertTrue(result.getOutput().contains("test: no test classes, nothing to check"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("results, but no class the check recognises: the task fails, because the check cannot tell")
	void resultsWithoutARecognisedTestClass_failTheTask(String gradleVersion, @TempDir File project)
		throws IOException
	{
		consumer(project, "");
		write(new File(project, "src/test/java/org/example/Check.java"), META_ANNOTATION);
		write(new File(project, "src/test/java/org/example/MetaAnnotatedTest.java"),
			META_ANNOTATED);

		BuildResult result = runner(project, gradleVersion, "test").buildAndFail();

		assertTrue(resultFile(project, "test", "org.example.MetaAnnotatedTest").exists(),
			"JUnit Jupiter ran the meta-annotated test");
		assertTrue(result.getOutput()
			.contains("test: 1 result files, but no compiled class "
				+ "refers to a test annotation this check knows (JUnit Jupiter, JUnit 4, TestNG), "
				+ "so it cannot tell whether every test class ran"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a class declared in ext.testClassesWithoutResult after the plugin was applied is excused, with its reason")
	void aClassDeclaredAfterApply_isExcused_withItsReason(String gradleVersion,
		@TempDir File project) throws IOException
	{
		declaredConsumer(project);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertTrue(result.getOutput().contains(
			"test: org.example.ManualTest leaves no result, as declared: runs only by hand, its tag is excluded"),
			result.getOutput());
		assertTrue(
			result.getOutput()
				.contains("test: all 1 test classes left a result, 1 declared without one\n"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}, {1}")
	@MethodSource("configurationCacheCases")
	@DisplayName("with the configuration cache, a reused configuration still checks every test task, and still fails")
	void withTheConfigurationCache_aReusedConfigurationStillChecks(String gradleVersion,
		ConfigurationCacheCase cacheCase, @TempDir File project) throws IOException
	{
		cacheCase.consumer().write(project);

		BuildResult stored = runner(project, gradleVersion, cacheCase.arguments()).run();
		BuildResult reused = runner(project, gradleVersion, cacheCase.arguments()).run();

		assertTrue(stored.getOutput().contains("Configuration cache entry stored."),
			stored.getOutput());
		assertTrue(reused.getOutput().contains("Reusing configuration cache."), reused.getOutput());
		assertEquals(cacheCase.outcome(), reused.task(":test").getOutcome(), reused.getOutput());
		for (String line : cacheCase.lines())
		{
			assertTrue(stored.getOutput().contains(line), line + ": " + stored.getOutput());
			assertTrue(reused.getOutput().contains(line), line + ": " + reused.getOutput());
		}
		assertNoConfigurationCacheProblem(project, stored);
		assertNoConfigurationCacheProblem(project, reused);
	}

	static Stream<Arguments> configurationCacheCases()
	{
		List<ConfigurationCacheCase> cases = List.of(
			new ConfigurationCacheCase("every class left a result",
				TestResultsCompletePluginFunctionalTest::lateTaskConsumer,
				new String[] { "test", "lateTest", "--rerun-tasks", "--configuration-cache" },
				TaskOutcome.SUCCESS,
				List.of("test: all 2 test classes left a result\n",
					"lateTest: all 1 test classes left a result\n")),
			new ConfigurationCacheCase("a class left no result",
				project -> endsTheJvmConsumer(project, ""),
				new String[] { "test", "--configuration-cache" }, TaskOutcome.FAILED,
				List.of(TWO_LEFT_NO_RESULT)),
			new ConfigurationCacheCase("a class declared without one",
				TestResultsCompletePluginFunctionalTest::declaredConsumer,
				new String[] { "test", "--rerun-tasks", "--configuration-cache" },
				TaskOutcome.SUCCESS,
				List.of("test: org.example.ManualTest leaves no result, as declared: ",
					"test: all 1 test classes left a result, 1 declared without one\n")));
		return gradleVersions()
			.flatMap(version -> cases.stream().map(each -> Arguments.of(version, each)));
	}

	@ParameterizedTest(name = "Gradle {0}, {1}")
	@MethodSource("otherFrameworks")
	@DisplayName("a TestNG and a JUnit 4 test class are recognised as test classes")
	void testNgAndJUnit4Classes_areRecognised(String gradleVersion, String framework,
		String dependency, String use, String template, @TempDir File project) throws IOException
	{
		write(new File(project, "settings.gradle"), "rootProject.name = 'consumer'\n");
		write(new File(project, "build.gradle"),
			OTHER_FRAMEWORK_BUILD_FILE.formatted(dependency, use));
		testClass(project, "test", "org.example.OtherFrameworkTest", template);

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertTrue(result.getOutput().contains("test: all 1 test classes left a result\n"),
			result.getOutput());
	}

	static Stream<Arguments> otherFrameworks()
	{
		return gradleVersions().flatMap(version -> Stream.of(
			Arguments.of(version, "TestNG", "org.testng:testng:7.10.2", "useTestNG()", TESTNG),
			Arguments.of(version, "JUnit 4", "junit:junit:4.13.2", "useJUnit()", JUNIT4)));
	}

	/** A configuration cache case: its consumer, its command line, and what both runs show */
	record ConfigurationCacheCase(String name, ConsumerSetup consumer, String[] arguments,
		TaskOutcome outcome, List<String> lines) {

		@Override
		public String toString()
		{
			return name;
		}
	}

	/** Writes a consuming project into a directory */
	@FunctionalInterface
	interface ConsumerSetup
	{

		void write(File project) throws IOException;
	}

	/**
	 * Neither run may report a configuration cache problem: not in the output, and not in a report
	 * Gradle wrote. A report's data stands between two marker lines, and each problem in it is a
	 * diagnostic with a {@code "problem"} part; the report's script outside the data names that key
	 * too, so only the data is read, and a report without the markers fails rather than passes.
	 */
	private static void assertNoConfigurationCacheProblem(File project, BuildResult result)
		throws IOException
	{
		assertFalse(result.getOutput().matches("(?s).*\\bproblems? (was|were) found\\b.*"),
			result.getOutput());
		for (File report : configurationCacheReports(project))
		{
			String content = Files.readString(report.toPath(), StandardCharsets.UTF_8);
			int begin = content.indexOf("// begin-report-data");
			int end = content.indexOf("// end-report-data");
			assertTrue(0 <= begin && begin < end, report + " has no report data to read");
			String reportData = content.substring(begin, end);
			assertFalse(reportData.contains("\"problem\":"), report + ": " + reportData);
		}
	}

	private static List<File> configurationCacheReports(File project) throws IOException
	{
		File reports = new File(project, "build/reports/configuration-cache");
		if (!reports.isDirectory())
		{
			return List.of();
		}
		try (Stream<java.nio.file.Path> paths = Files.walk(reports.toPath()))
		{
			return new ArrayList<>(paths.map(java.nio.file.Path::toFile)
				.filter(file -> file.getName().endsWith(".html")).toList());
		}
	}

	/** The consumer with its build file; the case writes its test classes */
	private static void consumer(File project, String addition) throws IOException
	{
		write(new File(project, "settings.gradle"), "rootProject.name = 'consumer'\n");
		write(new File(project, "build.gradle"), BUILD_FILE.formatted(addition));
	}

	/** Three classes, the first and the last ending the test JVM */
	private static void endsTheJvmConsumer(File project, String addition) throws IOException
	{
		consumer(project, addition);
		testClass(project, "test", "org.example.AExitTest", ENDS_THE_JVM);
		testClass(project, "test", "org.example.BRunsTest", RUNS);
		testClass(project, "test", "org.example.CExitTest", ENDS_THE_JVM);
	}

	/** Two classes in the guarded package, one outside it */
	private static void guardedConsumer(File project, String addition) throws IOException
	{
		consumer(project, addition);
		testClass(project, "test", "org.example.guarded.FirstTest", RUNS);
		testClass(project, "test", "org.example.guarded.SecondTest", RUNS);
		testClass(project, "test", "org.example.other.OutsideTest", RUNS);
	}

	/** A class that runs, and one whose tag is excluded and which is declared without a result */
	private static void declaredConsumer(File project) throws IOException
	{
		consumer(project, DECLARED_WITHOUT_RESULT);
		testClass(project, "test", "org.example.RunsTest", RUNS);
		testClass(project, "test", "org.example.ManualTest", TAGGED_MANUAL);
	}

	/** Two classes for the test task, one for a test task with a source set of its own */
	private static void lateTaskConsumer(File project) throws IOException
	{
		consumer(project, LATE_TASK);
		testClass(project, "test", "org.example.FirstTest", RUNS);
		testClass(project, "test", "org.example.SecondTest", RUNS);
		testClass(project, "late", "org.example.LateTest", RUNS);
	}

	/** A test class from a template that takes the package and the simple name */
	private static void testClass(File project, String sourceSet, String className, String template)
		throws IOException
	{
		int dot = className.lastIndexOf('.');
		write(
			new File(project,
				"src/" + sourceSet + "/java/" + className.replace('.', '/') + ".java"),
			template.formatted(className.substring(0, dot), className.substring(dot + 1)));
	}

	/** The result file Gradle writes for a class whose name it does not shorten */
	private static File resultFile(File project, String task, String className)
	{
		return new File(project, "build/test-results/" + task + "/TEST-" + className + ".xml");
	}

	/**
	 * A runner for the consumer. The consumer's test JVMs run headless and inherit no
	 * {@code DISPLAY}: this build's test task removes it from the environment it hands on
	 */
	private static GradleRunner runner(File project, String gradleVersion, String... arguments)
	{
		return GradleRunner.create().withProjectDir(project).withGradleVersion(gradleVersion)
			.withPluginClasspath().withArguments(arguments).forwardOutput();
	}

	private static void write(File file, String content) throws IOException
	{
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
	}
}
