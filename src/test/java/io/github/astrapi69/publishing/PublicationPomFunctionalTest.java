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
package io.github.astrapi69.publishing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Generates the POMs this repository publishes, through TestKit, in a copy of its own build, and
 * reads them: the plugin's publication ({@code pluginMaven}) and, for each plugin ID, the marker by
 * which a build finds the plugin ({@code <name>PluginMarkerMaven}). A marker's POM is what Central
 * shows for a plugin ID, so each one describes its own plugin, and the plugin's publication
 * describes both (#14).
 * <p>
 * The build's files are read from the project directory, as {@code HarnessDisplayTest} reads the
 * harness, and the test task declares them as inputs, so a change to them runs this test again. An
 * init script writes down every {@code gradlePlugin} entry of the same build; that is what each
 * marker is compared with. The copy runs on the Gradle version of this build, without a signing
 * key.
 */
class PublicationPomFunctionalTest
{

	/** The files of this build its publications are configured in, relative to its directory */
	private static final List<String> BUILD_FILES = List.of("settings.gradle", "build.gradle",
		"gradle.properties", "gradle/libs.versions.toml", "gradle/formatting.gradle",
		"gradle/publishing.gradle", "gradle/license-header.txt");

	/** The publication java-gradle-plugin creates for the plugin's jar */
	private static final String PLUGIN_PUBLICATION = "pluginMaven";

	/** The suffix java-gradle-plugin gives a marker publication after its gradlePlugin entry */
	private static final String MARKER_SUFFIX = "PluginMarkerMaven";

	/** The gradlePlugin entries of this build, one marker publication each */
	private static final List<String> PLUGIN_ENTRIES = List.of("ownDisplay", "testResultsComplete");

	/** The plugin IDs a description of the whole artifact names */
	private static final List<String> PLUGIN_IDS = List.of("own-display", "test-results-complete");

	/** Writes the display name and the description of every gradlePlugin entry to a file */
	private static final String DECLARATIONS_INIT_SCRIPT = """
		gradle.projectsEvaluated { evaluated ->
		    def declarations = new Properties()
		    evaluated.rootProject.gradlePlugin.plugins.each { declaration ->
		        declarations.setProperty(declaration.name + '.displayName', declaration.displayName ?: '')
		        declarations.setProperty(declaration.name + '.description', declaration.description ?: '')
		    }
		    def file = evaluated.rootProject.layout.buildDirectory.file('declarations.properties').get().asFile
		    file.parentFile.mkdirs()
		    file.withWriter('UTF-8') { declarations.store(it, null) }
		}
		""";

	@TempDir
	static File project;

	@BeforeAll
	static void generateThePoms() throws IOException
	{
		for (String buildFile : BUILD_FILES)
		{
			File copy = new File(project, buildFile);
			Files.createDirectories(copy.getParentFile().toPath());
			Files.copy(new File(buildFile).toPath(), copy.toPath(),
				StandardCopyOption.REPLACE_EXISTING);
		}
		File initScript = new File(project, "declarations.init.gradle");
		Files.writeString(initScript.toPath(), DECLARATIONS_INIT_SCRIPT, StandardCharsets.UTF_8);
		List<String> arguments = Stream.concat(Stream.of("--init-script", initScript.getPath()),
			publications().map(PublicationPomFunctionalTest::generatePomTask)).toList();
		GradleRunner.create().withProjectDir(project).withArguments(arguments).forwardOutput()
			.build();
	}

	static Stream<String> publications()
	{
		return Stream.concat(Stream.of(PLUGIN_PUBLICATION), markers());
	}

	static Stream<String> markers()
	{
		return PLUGIN_ENTRIES.stream().map(entry -> entry + MARKER_SUFFIX);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("markers")
	@DisplayName("a marker POM carries the display name and the description of its gradlePlugin entry")
	void aMarkerPom_carriesItsPluginEntrysNameAndDescription(String marker) throws Exception
	{
		String entry = marker.substring(0, marker.length() - MARKER_SUFFIX.length());
		Properties declarations = declarations();
		Element pom = pom(marker);

		assertFalse(declarations.getProperty(entry + ".displayName", "").isBlank(), entry);
		assertFalse(declarations.getProperty(entry + ".description", "").isBlank(), entry);
		assertEquals(declarations.getProperty(entry + ".displayName"), child(pom, "name"), marker);
		assertEquals(declarations.getProperty(entry + ".description"), child(pom, "description"),
			marker);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("markers")
	@DisplayName("a marker POM does not carry the description of the whole artifact")
	void aMarkerPom_doesNotCarryTheArtifactsDescription(String marker) throws Exception
	{
		assertNotEquals(child(pom(PLUGIN_PUBLICATION), "description"),
			child(pom(marker), "description"), marker);
	}

	@Test
	@DisplayName("the plugin's POM keeps the project's name, and its description names both plugins")
	void thePluginPom_keepsTheProjectName_andNamesBothPlugins() throws Exception
	{
		Element pom = pom(PLUGIN_PUBLICATION);

		assertEquals("own-display-gradle-plugin", child(pom, "name"));
		assertEquals(projectProperties().getProperty("projectDescription"),
			child(pom, "description"));
		for (String pluginId : PLUGIN_IDS)
		{
			assertTrue(child(pom, "description").contains(pluginId),
				pluginId + ": " + child(pom, "description"));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("publications")
	@DisplayName("every POM carries the metadata Central validates")
	void everyPom_carriesTheMetadataCentralValidates(String publication) throws Exception
	{
		Element pom = pom(publication);

		for (String part : List.of("name", "description", "url", "licenses", "developers", "scm"))
		{
			assertFalse(child(pom, part).isBlank(), publication + " has no " + part);
		}
	}

	private static String generatePomTask(String publication)
	{
		return "generatePomFileFor" + Character.toUpperCase(publication.charAt(0))
			+ publication.substring(1) + "Publication";
	}

	private static Element pom(String publication)
		throws ParserConfigurationException, SAXException, IOException
	{
		File pom = new File(project, "build/publications/" + publication + "/pom-default.xml");
		assertTrue(pom.isFile(), pom + " was not generated");
		return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
			.getDocumentElement();
	}

	/** The text of a top-level element of a POM, empty when there is none */
	private static String child(Element pom, String name)
	{
		NodeList children = pom.getChildNodes();
		for (int index = 0; index < children.getLength(); index++)
		{
			Node child = children.item(index);
			if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(name))
			{
				return child.getTextContent().trim();
			}
		}
		return "";
	}

	private static Properties declarations() throws IOException
	{
		return load(new File(project, "build/declarations.properties"));
	}

	private static Properties projectProperties() throws IOException
	{
		return load(new File(project, "gradle.properties"));
	}

	private static Properties load(File file) throws IOException
	{
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			properties.load(reader);
		}
		return properties;
	}
}
