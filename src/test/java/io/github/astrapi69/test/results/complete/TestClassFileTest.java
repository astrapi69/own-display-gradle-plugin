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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reads class files javac wrote a moment before, from sources in a temporary directory: the check
 * asks of each one whether it refers to a test annotation and whether it is concrete, which it
 * reads from the access flags behind the constant pool.
 * <p>
 * The fixtures are compiled here rather than written as nested classes of this test, because JUnit
 * would run a nested class's tests as its own.
 */
class TestClassFileTest
{

	/**
	 * Constants of eight bytes take two slots of the constant pool, and lambdas bring three tags
	 */
	private static final String CONSTANT_POOL_OF_EVERY_KIND = """
		    static final long BIG = 1234567890123L;
		    static final double RATIO = 0.125;
		    static final float FLOAT = 0.5f;
		    static final int INT = 123456789;

		    String every(int count) {
		        Runnable lambda = () -> {
		        };
		        lambda.run();
		        java.util.function.Supplier<String> reference = String::new;
		        return "count " + count + BIG + RATIO + reference.get();
		    }
		""";

	static Stream<Arguments> classFiles()
	{
		return Stream.of(Arguments.of("a concrete JUnit Jupiter test class", "ConcreteTest", """
			class ConcreteTest {
			    @org.junit.jupiter.api.Test
			    void runs() {
			    }
			}
			""", true), Arguments.of("an abstract class with a test", "AbstractTest", """
			abstract class AbstractTest {
			    @org.junit.jupiter.api.Test
			    void runs() {
			    }
			}
			""", false), Arguments.of("an interface with a default test", "ContractTest", """
			interface ContractTest {
			    @org.junit.jupiter.api.Test
			    default void runs() {
			    }
			}
			""", false),
			Arguments.of("a class with only a parameterized test", "ParameterizedOnlyTest", """
				class ParameterizedOnlyTest {
				    @org.junit.jupiter.params.ParameterizedTest
				    @org.junit.jupiter.params.provider.ValueSource(ints = { 1, 2 })
				    void runs(int value) {
				    }
				}
				""", true), Arguments.of("a class with only a test factory", "FactoryOnlyTest", """
				class FactoryOnlyTest {
				    @org.junit.jupiter.api.TestFactory
				    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest> runs() {
				        return java.util.stream.Stream.empty();
				    }
				}
				""", true),
			Arguments.of("a concrete test class with a constant pool of every kind javac writes",
				"EveryKindTest", "class EveryKindTest {\n" + CONSTANT_POOL_OF_EVERY_KIND + """
					    @org.junit.jupiter.api.RepeatedTest(2)
					    void runs() {
					    }
					}
					""", true),
			Arguments.of("an abstract test class with a constant pool of every kind javac writes",
				"AbstractEveryKindTest",
				"abstract class AbstractEveryKindTest {\n" + CONSTANT_POOL_OF_EVERY_KIND + """
					    @org.junit.jupiter.api.Test
					    void runs() {
					    }
					}
					""", false),
			Arguments.of("a class without a test annotation", "Helper",
				"class Helper {\n" + CONSTANT_POOL_OF_EVERY_KIND + "}\n", false));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("classFiles")
	@DisplayName("a class is a test class when it refers to a test annotation and is neither abstract nor an interface")
	void isConcreteTestClass_tellsTestClassesFromTheRest(String kind, String className,
		String source, boolean testClass, @TempDir File directory) throws IOException
	{
		byte[] classFile = compiled(directory, className, source);

		assertEquals(testClass, TestClassFile.isConcreteTestClass(classFile, className), kind);
	}

	@Test
	@DisplayName("a constant pool tag the class file format does not know stops the check, naming the tag and the file")
	void isConcreteTestClass_fails_whenTheConstantPoolHasAnUnknownTag() throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes))
		{
			out.writeInt(0xCAFEBABE);
			out.writeShort(0);
			out.writeShort(61);
			out.writeShort(3);
			out.writeByte(1);
			out.writeUTF("org/junit/jupiter/api/Test");
			out.writeByte(2);
		}

		GradleException failure = assertThrows(GradleException.class,
			() -> TestClassFile.isConcreteTestClass(bytes.toByteArray(), "Broken.class"));

		assertEquals("unknown constant pool tag 2 in Broken.class", failure.getMessage());
	}

	@Test
	@DisplayName("a class file that ends inside its constant pool stops the check, naming the file")
	void isConcreteTestClass_fails_whenTheClassFileIsTruncated(@TempDir File directory)
		throws IOException
	{
		byte[] classFile = compiled(directory, "ConcreteTest", """
			class ConcreteTest {
			    @org.junit.jupiter.api.Test
			    void runs() {
			    }
			}
			""");
		String marker = "org/junit/jupiter/api/Test";
		int end = new String(classFile, StandardCharsets.ISO_8859_1).indexOf(marker)
			+ marker.length();
		byte[] truncated = Arrays.copyOf(classFile, end);

		GradleException failure = assertThrows(GradleException.class,
			() -> TestClassFile.isConcreteTestClass(truncated, "ConcreteTest.class"));

		assertTrue(failure.getMessage().contains("ConcreteTest.class"), failure.getMessage());
	}

	/** Compiles one source with the JDK this test runs on, against this test's class path */
	private static byte[] compiled(File directory, String className, String source)
		throws IOException
	{
		File sourceFile = new File(directory, "src/" + className + ".java");
		File classes = new File(directory, "classes");
		Files.createDirectories(sourceFile.getParentFile().toPath());
		Files.createDirectories(classes.toPath());
		Files.writeString(sourceFile.toPath(), source, StandardCharsets.UTF_8);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		int exitCode = compiler.run(null, null, null, "--release", "25", "-proc:none", "-d",
			classes.getPath(), "-classpath", System.getProperty("java.class.path"),
			sourceFile.getPath());
		assertEquals(0, exitCode, "javac on " + className);
		return Files.readAllBytes(new File(classes, className + ".class").toPath());
	}
}
