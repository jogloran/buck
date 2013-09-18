/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.dalvik;

import static org.junit.Assert.*;

import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Tests for {@link LinearAllocEstimator}.
 */
public class LinearAllocEstimatorTest {

  private static final String TEST_CLASS = createSource(
    "package test;",
    "",
    "import java.lang.StringBuilder;",
    "",
    "public class TestClass {",
    "",
    "  public String get() {",
    "    StringBuilder sb = new StringBuilder();",
    "    sb.append(\"foo\");",
    "    return sb.toString();",
    "  }",
    "}");

  private static final String TEST_CLASS_WITH_INNER = createSource(
      "package test;",
      "",
      "public class TestClassWithInner {",
      "",
      "  private long startTime;",
      "",
      "  public TestClassWithInner() {",
      "    startTime = System.currentTimeMillis();",
      "  }",
      "",
      "  public Object get() {",
      "    return new Object() {",
      "      public String toString() {",
      "        return Long.toString(startTime);",
      "      }",
      "    };",
      "  }",
      "}");

  @Rule
  public TemporaryFolder tmpDir = new TemporaryFolder();

  private File outputDir;

  @Before
  public void setUp() throws Exception {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    outputDir = tmpDir.newFolder("output");
    builder.add("-d", outputDir.toString());

    ImmutableList<JavaSourceFromString> inputs = ImmutableList.of(
        new JavaSourceFromString("TestClass", TEST_CLASS),
        new JavaSourceFromString("TestClassWithInner", TEST_CLASS_WITH_INNER));
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    JavaCompiler.CompilationTask task =
        compiler.getTask(null, null, null, builder.build(), null, inputs);
    assertTrue(task.call());
  }

  @Test
  public void testSimpleClass() throws Exception {
    File classFile = new File(outputDir, "test/TestClass.class");
    InputStream inputStream = new FileInputStream(classFile);
    LinearAllocEstimator.Stats stats = LinearAllocEstimator.getEstimate(inputStream);
    assertMethodReferences(
        stats.methodReferences,
        "test/TestClass.<init>:()V",
        "java/lang/StringBuilder.toString:()Ljava/lang/String;",
        "test/TestClass.get:()Ljava/lang/String;",
        "java/lang/StringBuilder.<init>:()V",
        "java/lang/StringBuilder.append:(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        "java/lang/Object.<init>:()V"
    );
    assertEquals(156, stats.estimatedLinearAllocSize);
  }

  @Test
  public void testClassWithInner() throws Exception {
    File classFileOuter = new File(outputDir, "test/TestClassWithInner.class");
    InputStream inputStreamOuter = new FileInputStream(classFileOuter);
    LinearAllocEstimator.Stats statsOuter = LinearAllocEstimator.getEstimate(inputStreamOuter);
    assertMethodReferences(
        statsOuter.methodReferences,
        "test/TestClassWithInner.<init>:()V",
        "java/lang/Object.<init>:()V",
        "test/TestClassWithInner.get:()Ljava/lang/Object;",
        "test/TestClassWithInner$1.<init>:(Ltest/TestClassWithInner;)V",
        "test/TestClassWithInner.access$000:(Ltest/TestClassWithInner;)J",
        "java/lang/System.currentTimeMillis:()J"
    );
    assertEquals(224, statsOuter.estimatedLinearAllocSize);

    File classFileInner = new File(outputDir, "test/TestClassWithInner$1.class");
    InputStream inputStreamInner = new FileInputStream(classFileInner);
    LinearAllocEstimator.Stats statsInner = LinearAllocEstimator.getEstimate(inputStreamInner);
    assertMethodReferences(
        statsInner.methodReferences,
        "test/TestClassWithInner$1.toString:()Ljava/lang/String;",
        "test/TestClassWithInner$1.<init>:(Ltest/TestClassWithInner;)V",
        "test/TestClassWithInner$1.get:()Ljava/lang/Object;",
        "java/lang/Object.<init>:()V",
        "java/lang/Long.toString:(J)Ljava/lang/String;",
        "test/TestClassWithInner.access$000:(Ltest/TestClassWithInner;)J" // visitOuterClass

    );
    assertEquals(172, statsInner.estimatedLinearAllocSize);
  }

  /**
   * A file object used to represent source coming from a string.
   */
  public class JavaSourceFromString extends SimpleJavaFileObject {
    /**
     * The source code of this "file".
     */
    final String code;

    /**
     * Constructs a new JavaSourceFromString.
     * @param name the name of the compilation unit represented by this file object
     * @param code the source code for the compilation unit represented by this file object
     */
    JavaSourceFromString(String name, String code) {
      super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }

  private static String createSource(String... args) {
    return Joiner.on("\n").join(args);
  }

  private static void assertMethodReferences(
      Set<LinearAllocEstimator.MethodReference> references,
      String... methods) {
    Set<String> actual = Sets.newHashSet(
        Iterables.transform(references, Functions.toStringFunction()));
    Set<String> expected = Sets.newHashSet(methods);

    Set<String> onlyInActual = Sets.difference(actual, expected);
    Set<String> onlyInExpected = Sets.difference(expected, actual);
    if (onlyInActual.isEmpty() && onlyInExpected.isEmpty()) {
      return;
    }
    StringBuilder sbError = new StringBuilder();
    if (!onlyInExpected.isEmpty()) {
      sbError.append("Missing method references:\n");
      for (String s : onlyInExpected) {
        sbError.append("  ").append(s).append("\n");
      }
    }
    if (!onlyInActual.isEmpty()) {
      sbError.append("Unexpected method references:\n");
      for (String s : onlyInActual) {
        sbError.append("  ").append(s).append("\n");
      }
    }
    fail(sbError.toString());
  }
}
