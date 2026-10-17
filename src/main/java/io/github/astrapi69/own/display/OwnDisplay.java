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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * The build's own display, borrowed from the harness the first time a test task asks for it and
 * given back when the build ends.
 * <p>
 * The harness and its hold command ship as resources of the plugin. They are unpacked into the work
 * directory, and the harness runs as a child of the build with the hold command as its command: it
 * starts its Xvfb and a window manager it has seen answering, the hold command reports that display
 * on one line and holds it until its input ends, and the harness watches every test JVM of the
 * build meanwhile. Closing the service closes that input, and the harness stops what it started.
 * Which display it is stays the harness's one decision: its own Xvfb, or the inherited one only
 * with {@code E2E_USE_CURRENT_DISPLAY=1}.
 */
public abstract class OwnDisplay implements BuildService<OwnDisplay.Parameters>, AutoCloseable
{

	/** Where the harness unpacks to and writes its output */
	public interface Parameters extends BuildServiceParameters
	{

		/**
		 * The directory the harness works in
		 *
		 * @return the directory property
		 */
		DirectoryProperty getWorkDirectory();
	}

	/** The line the hold command reports the display on */
	private static final String HANDED_OUT = "E2E_DISPLAY=";

	/** How long the harness may take to hand out a display */
	private static final long WAIT_SECONDS = 90;

	/** The harness's exit code after it stopped a run because a test JVM was on another display */
	private static final int STOPPED = 96;

	/** The harness, the hold command it runs, and what each is unpacked as */
	private static final List<String> SCRIPTS = List.of("e2e-harness.sh", "hold-display.sh");

	private Process harness;

	private String display;

	/**
	 * The display of the build, started on the first call
	 *
	 * @return the display, such as {@code :437}
	 * @throws GradleException
	 *             when the harness hands out no display, or has stopped the run since it did
	 */
	public synchronized String display()
	{
		if (display == null)
		{
			display = borrow();
		}
		else if (!harness.isAlive() || stoppedLine().isPresent())
		{
			// not only once the harness has ended: it says why it stops a run before it stops
			// anything, and is still alive for a moment while it takes the display down
			// (astrapi69/mystic-crypt-ui#600)
			String state = harness.isAlive()
				? "is taking its display down"
				: "has ended, with exit " + harness.exitValue();
			throw new GradleException("No display for the tests: the own-display harness " + state
				+ " (astrapi69/mystic-crypt-ui#597, #600). Its output, from " + log() + ":\n"
				+ logText());
		}
		return display;
	}

	/**
	 * The harness's output: which display it started, which test JVM ran on it, and why it stopped
	 * a run
	 *
	 * @return the log file
	 */
	public File log()
	{
		return new File(workDirectory(), "harness.log");
	}

	@Override
	public void close()
	{
		if (harness == null)
		{
			return;
		}
		endTheHoldCommandsInput();
		awaitTheHarness();
		if (!harness.isAlive() && harness.exitValue() == STOPPED)
		{
			Logging.getLogger(OwnDisplay.class).error("the own-display harness {} - its output: {}",
				stoppedLine().orElse("stopped the run, exit " + STOPPED), log());
		}
	}

	private String borrow()
	{
		File bin = new File(workDirectory(), "bin");
		File harnessScript = unpack(bin, SCRIPTS.get(0));
		File holdCommand = unpack(bin, SCRIPTS.get(1));
		ProcessBuilder builder = new ProcessBuilder(harnessScript.getPath(), "hold")
			.redirectErrorStream(true).redirectOutput(log());
		builder.environment().put("E2E_RUN_COMMAND", holdCommand.getPath());
		try
		{
			harness = builder.start();
		}
		catch (IOException cannotStart)
		{
			throw new GradleException("No display for the tests: the own-display harness "
				+ harnessScript + " could not be started", cannotStart);
		}
		return awaitTheHandedOutDisplay();
	}

	private String awaitTheHandedOutDisplay()
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
		while (System.nanoTime() < deadline)
		{
			Optional<String> handedOut = logLines().stream()
				.filter(line -> line.startsWith(HANDED_OUT)).findFirst();
			if (handedOut.isPresent())
			{
				return handedOut.get().substring(HANDED_OUT.length());
			}
			if (!harness.isAlive())
			{
				throw new GradleException("No display for the tests: the own-display harness "
					+ "ended with exit " + harness.exitValue() + " before handing one out "
					+ "(astrapi69/mystic-crypt-ui#505). Its output, from " + log() + ":\n"
					+ logText());
			}
			pause();
		}
		close();
		throw new GradleException("No display for the tests: the own-display harness handed none "
			+ "out within " + WAIT_SECONDS + "s. Its output, from " + log() + ":\n" + logText());
	}

	/** Closing the hold command's input ends it, and the harness then stops what it started */
	private void endTheHoldCommandsInput()
	{
		try
		{
			harness.getOutputStream().close();
		}
		catch (IOException alreadyClosed)
		{
			Logging.getLogger(OwnDisplay.class).info(
				"the own-display harness's input was closed already: {}",
				alreadyClosed.getMessage());
		}
	}

	private void awaitTheHarness()
	{
		try
		{
			if (!harness.waitFor(30, TimeUnit.SECONDS))
			{
				harness.destroy();
				harness.waitFor(10, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException interrupted)
		{
			harness.destroy();
			Thread.currentThread().interrupt();
		}
	}

	private Optional<String> stoppedLine()
	{
		return logLines().stream().filter(line -> line.contains("stopped the run")).findFirst()
			.map(line -> line.replaceFirst("^e2e-harness: ", ""));
	}

	private File workDirectory()
	{
		return getParameters().getWorkDirectory().get().getAsFile();
	}

	private List<String> logLines()
	{
		try
		{
			return log().exists()
				? Files.readAllLines(log().toPath(), StandardCharsets.UTF_8)
				: List.of();
		}
		catch (IOException unreadable)
		{
			throw new UncheckedIOException(
				"the own-display harness's log " + log() + " cannot be read", unreadable);
		}
	}

	private String logText()
	{
		return String.join("\n", logLines());
	}

	private static void pause()
	{
		try
		{
			Thread.sleep(100);
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new GradleException("interrupted while waiting for the own-display harness",
				interrupted);
		}
	}

	/** Copies one of the plugin's scripts out of its jar, executable */
	private static File unpack(File directory, String name)
	{
		File script = new File(directory, name);
		try (InputStream resource = OwnDisplay.class.getResourceAsStream(name))
		{
			if (resource == null)
			{
				throw new GradleException("the own-display plugin's jar carries no " + name);
			}
			Files.createDirectories(directory.toPath());
			Files.copy(resource, script.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException cannotUnpack)
		{
			throw new GradleException(
				"the own-display plugin cannot unpack " + name + " to " + directory, cannotUnpack);
		}
		if (!script.setExecutable(true))
		{
			throw new GradleException(
				"the own-display plugin cannot make " + script + " executable");
		}
		return script;
	}
}
