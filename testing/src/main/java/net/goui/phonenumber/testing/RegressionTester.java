/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Expect;
import com.google.common.truth.StandardSubjectBuilder;
import com.google.common.truth.Truth;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.metadata.RawClassifier;
import org.typemeta.funcj.json.model.JsObject;
import org.typemeta.funcj.json.model.JsString;
import org.typemeta.funcj.json.model.JsValue;
import org.typemeta.funcj.json.parser.JsonParser;

public final class RegressionTester {
  public static RegressionTester forClassifier(RawClassifier classifier) {
    return new RegressionTester(classifier, Truth.assert_());
  }

  public static RegressionTester forClassifier(RawClassifier classifier, Expect expect) {
    return new RegressionTester(classifier, expect);
  }

  private final RawClassifier classifier;
  private final StandardSubjectBuilder truthStrategy;

  private RegressionTester(RawClassifier classifier, StandardSubjectBuilder truthStrategy) {
    this.classifier = checkNotNull(classifier);
    this.truthStrategy = truthStrategy;
  }

  public void assertGoldenData(Path jsonPath) throws IOException {
    JsObject jsonTestData;
    try (Reader reader = Files.newBufferedReader(jsonPath, UTF_8)) {
      jsonTestData = JsonParser.parse(reader).asObject();
    }
    objectsOf(jsonTestData, "testdata").forEach(this::assertResults);
  }

  public void assertGoldenData(String json) throws IOException {
    JsObject jsonTestData = JsonParser.parse(json).asObject();
    objectsOf(jsonTestData, "testdata").forEach(this::assertResults);
  }

  private void assertResults(JsObject jsonResults) {
    DigitSequence cc = DigitSequence.parse(getString(jsonResults, "cc"));
    DigitSequence nn = DigitSequence.parse(getString(jsonResults, "number"));
    objectsOf(jsonResults, "result").forEach(r -> assertResult(cc, nn, r));
  }

  private void assertResult(DigitSequence cc, DigitSequence nn, JsObject jsonResult) {
    String type = getString(jsonResult, "type");
    ImmutableSet<String> expected =
        jsonResult.get("values").asArray().stream()
            .map(JsValue::asString)
            .map(JsString::value)
            .collect(toImmutableSet());
    truthStrategy
        .withMessage("classifying '%s' for +%s%s", type, cc, nn)
        .that(classifier.classify(cc, nn, type))
        .containsExactlyElementsIn(expected);
  }

  private static String getString(JsObject json, String field) {
    return json.get(field).asString().value();
  }

  private static Stream<JsObject> objectsOf(JsObject json, String field) {
    return json.get(field).asArray().stream().map(JsValue::asObject);
  }
}
