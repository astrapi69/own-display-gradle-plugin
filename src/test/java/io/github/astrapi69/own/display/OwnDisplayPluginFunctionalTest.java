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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs the plugin the way a consuming repository does: in a Gradle build of its own, through
 * TestKit, on every Gradle version in {@code functionalTestGradleVersions} - the oldest and the
 * newest the family's repositories use (astrapi69/mystic-crypt-ui#599) - and asks which display the
 * build's test JVMs got.
 * <p>
 * <b>No test here opens a window.</b> The consumer's one test writes down the environment its JVM
 * was started with and waits, so that a wrong display is recorded, never drawn on. The consumer
 * build is started with {@code DISPLAY=:0}, the desktop's display, which it must not hand on.
 */
class OwnDisplayPluginFunctionalTest
{

	/** The consumer's build file, with the placeholder for what a case adds */
	private static final String BUILD_FILE = """
		plugins {
		    id 'java'
		    id 'io.github.astrapi69.own-display'
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
		    systemProperty 'seen', file("build/seen-${name}.properties").absolutePath
		    systemProperty 'linger', '2000'
		}
		%s
		""";

	/** The consumer's one test: it records its environment and lingers, so the harness sees it */
	private static final String RECORDING_TEST = """
		import java.io.FileWriter;
		import java.io.Writer;
		import java.util.Properties;

		class RecordTheEnvironmentTest {
		    @org.junit.jupiter.api.Test
		    void record() throws Exception {
		        Properties seen = new Properties();
		        seen.setProperty("DISPLAY", System.getenv().getOrDefault("DISPLAY", ""));
		        seen.setProperty("E2E_HARNESS_DISPLAY", System.getenv().getOrDefault("E2E_HARNESS_DISPLAY", ""));
		        try (Writer writer = new FileWriter(System.getProperty("seen"))) {
		            seen.store(writer, null);
		        }
		        Thread.sleep(Long.getLong("linger"));
		    }
		}
		""";

	/**
	 * A test task that ends up on another display than the one the plugin handed out: set after the
	 * plugin's own action, as a build script that overrides it would, on a display nobody serves
	 */
	private static final String WRONG_DISPLAY_TASK = """
		def wrongDisplay = tasks.register('wrongDisplay', Test) {
		    testClassesDirs = sourceSets.test.output.classesDirs
		    classpath = sourceSets.test.runtimeClasspath
		    systemProperty 'linger', '20000'
		}
		tasks.named('test') { mustRunAfter wrongDisplay }
		gradle.taskGraph.whenReady { graph ->
		    if (graph.hasTask(':wrongDisplay')) {
		        tasks.getByName('wrongDisplay').actions.add(1, { t -> t.environment('DISPLAY', ':4242') } as Action)
		    }
		}
		""";

	static Stream<String> gradleVersions()
	{
		return Arrays.stream(System.getProperty("functionalTestGradleVersions").split(","))
			.map(String::trim);
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a test task runs on the build's own display, measured, not on the DISPLAY the build was started with")
	void aTestTask_runsOnTheBuildsOwnDisplay(String gradleVersion, @TempDir File project)
		throws IOException
	{
		consumer(project, "");

		BuildResult result = runner(project, gradleVersion, "test").build();

		String display = seen(project, "test").getProperty("DISPLAY");
		assertNotEquals(":0", display, result.getOutput());
		assertTrue(display.matches(":\\d{3}"),
			"a display of the harness's, 100 to 999: " + display);
		assertEquals(display, seen(project, "test").getProperty("E2E_HARNESS_DISPLAY"));
		String log = harnessLog(project);
		assertTrue(log.contains("the socket of " + display + " belongs to pid"), log);
		assertTrue(log.matches("(?s).*test JVM pid \\d+ runs on " + display + "\\b.*"), log);
		assertTrue(result.getOutput().contains("test: tests run on display " + display),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a headless test task runs on no display at all")
	void aHeadlessTestTask_runsOnNoDisplay(String gradleVersion, @TempDir File project)
		throws IOException
	{
		consumer(project, "test { systemProperty 'java.awt.headless', 'true' }");

		BuildResult result = runner(project, gradleVersion, "test").build();

		assertEquals("", seen(project, "test").getProperty("DISPLAY"), result.getOutput());
		assertTrue(result.getOutput().contains("test: tests run headless, on no display"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("a test JVM on another display is stopped, every later test task refuses, and the build names why (mystic-crypt-ui#597)")
	void aTestJvmOnAnotherDisplay_stopsTheRun(String gradleVersion, @TempDir File project)
		throws IOException
	{
		consumer(project, WRONG_DISPLAY_TASK);

		BuildResult result = runner(project, gradleVersion, "wrongDisplay", "test", "--continue")
			.buildAndFail();

		assertEquals(TaskOutcome.FAILED, result.task(":wrongDisplay").getOutcome(),
			result.getOutput());
		assertEquals(TaskOutcome.FAILED, result.task(":test").getOutcome(), result.getOutput());
		assertTrue(result.getOutput().contains("No display for the tests"), result.getOutput());
		assertTrue(
			result.getOutput().matches(
				"(?s).*stopped the run: test JVM pid \\d+ had DISPLAY=:4242, not :\\d{3}.*"),
			result.getOutput());
	}

	@ParameterizedTest(name = "Gradle {0}")
	@MethodSource("gradleVersions")
	@DisplayName("with the configuration cache, a reused configuration still gets the build's own display")
	void withTheConfigurationCache_aReusedConfigurationGetsItsOwnDisplay(String gradleVersion,
		@TempDir File project) throws IOException
	{
		consumer(project, "");

		runner(project, gradleVersion, "test", "--configuration-cache").build();
		BuildResult reused = runner(project, gradleVersion, "test", "--configuration-cache",
			"--rerun-tasks").build();

		assertTrue(reused.getOutput().contains("Reusing configuration cache"), reused.getOutput());
		String display = seen(project, "test").getProperty("DISPLAY");
		assertTrue(display.matches(":\\d{3}"), display + ": " + reused.getOutput());
		assertEquals(display, seen(project, "test").getProperty("E2E_HARNESS_DISPLAY"));
	}

	/** A consuming project with the plugin applied and one test that records its environment */
	private static void consumer(File project, String addition) throws IOException
	{
		write(new File(project, "settings.gradle"), "rootProject.name = 'consumer'\n");
		write(new File(project, "build.gradle"), BUILD_FILE.formatted(addition));
		write(new File(project, "src/test/java/RecordTheEnvironmentTest.java"), RECORDING_TEST);
	}

	/**
	 * A runner for the consumer, started with {@code DISPLAY=:0} and nothing else of the display's:
	 * no opt-in for the current display, no Xvfb pid of an outer harness
	 */
	private static GradleRunner runner(File project, String gradleVersion, String... arguments)
	{
		Map<String, String> environment = new HashMap<>(System.getenv());
		environment.remove("E2E_USE_CURRENT_DISPLAY");
		environment.remove("E2E_HARNESS_XVFB_PID");
		environment.put("DISPLAY", ":0");
		return GradleRunner.create().withProjectDir(project).withGradleVersion(gradleVersion)
			.withPluginClasspath().withEnvironment(environment).withArguments(arguments)
			.forwardOutput();
	}

	private static Properties seen(File project, String task) throws IOException
	{
		Properties seen = new Properties();
		try (Reader reader = Files.newBufferedReader(
			new File(project, "build/seen-" + task + ".properties").toPath(),
			StandardCharsets.UTF_8))
		{
			seen.load(reader);
		}
		return seen;
	}

	private static String harnessLog(File project) throws IOException
	{
		return Files.readString(new File(project, "build/own-display/harness.log").toPath(),
			StandardCharsets.UTF_8);
	}

	private static void write(File file, String content) throws IOException
	{
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
	}
}
