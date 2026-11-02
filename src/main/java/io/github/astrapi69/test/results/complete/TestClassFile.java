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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.gradle.api.GradleException;

/**
 * Tells from a compiled class whether it is a test class that runs on its own: it refers to a JUnit
 * Jupiter, JUnit 4 or TestNG test annotation, and it is neither abstract nor an interface.
 * <p>
 * The annotation is looked for as text anywhere in the class file, which is where its constant pool
 * names it. The access flags follow the constant pool, so the pool is walked entry by entry to
 * reach them: no class is loaded, and nothing beyond the JDK is needed.
 */
final class TestClassFile
{

	/** The test annotations of JUnit Jupiter, JUnit 4 and TestNG, as a constant pool names them */
	static final List<String> TEST_ANNOTATIONS = List.of("org/junit/jupiter/api/Test",
		"org/junit/jupiter/params/ParameterizedTest", "org/junit/jupiter/api/TestFactory",
		"org/junit/jupiter/api/RepeatedTest", "org/junit/jupiter/api/TestTemplate",
		"org/junit/Test", "org/testng/annotations/Test");

	/** The access flag of an abstract class */
	private static final int ACC_ABSTRACT = 0x0400;

	/** The access flag of an interface */
	private static final int ACC_INTERFACE = 0x0200;

	private TestClassFile()
	{
	}

	/**
	 * Whether a compiled class is a test class that runs on its own
	 *
	 * @param classFile
	 *            the bytes of the class file
	 * @param source
	 *            where the bytes come from, named when they cannot be read
	 * @return true when the class refers to a test annotation and is neither abstract nor an
	 *         interface
	 * @throws GradleException
	 *             when the constant pool holds a tag the class file format does not know, or the
	 *             bytes end before the access flags
	 */
	static boolean isConcreteTestClass(byte[] classFile, String source)
	{
		return refersToATestAnnotation(classFile) && isConcrete(classFile, source);
	}

	private static boolean refersToATestAnnotation(byte[] classFile)
	{
		String text = new String(classFile, StandardCharsets.ISO_8859_1);
		return TEST_ANNOTATIONS.stream().anyMatch(text::contains);
	}

	private static boolean isConcrete(byte[] classFile, String source)
	{
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(classFile)))
		{
			input.readInt();
			input.readUnsignedShort();
			input.readUnsignedShort();
			skipTheConstantPool(input, source);
			int access = input.readUnsignedShort();
			return (access & ACC_ABSTRACT) == 0 && (access & ACC_INTERFACE) == 0;
		}
		catch (IOException exception)
		{
			throw new GradleException("cannot read the access flags of " + source, exception);
		}
	}

	/**
	 * Reads past the constant pool, entry by entry (JVMS 4.4). A long and a double take two slots
	 * of the pool's count.
	 */
	private static void skipTheConstantPool(DataInputStream input, String source) throws IOException
	{
		int count = input.readUnsignedShort();
		for (int index = 1; index < count; index++)
		{
			int tag = input.readUnsignedByte();
			switch (tag)
			{
				case 1 -> input.skipBytes(input.readUnsignedShort());
				case 3, 4 -> input.skipBytes(4);
				case 5, 6 -> {
					input.skipBytes(8);
					index++;
				}
				case 7, 8, 16, 19, 20 -> input.skipBytes(2);
				case 9, 10, 11, 12, 17, 18 -> input.skipBytes(4);
				case 15 -> input.skipBytes(3);
				default -> throw new GradleException(
					"unknown constant pool tag " + tag + " in " + source);
			}
		}
	}
}
