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
import static java.util.function.Function.identity;
import static net.goui.phonenumber.MatchResult.MATCHED;
import static net.goui.phonenumber.PhoneNumberFormatter.FormatType.INTERNATIONAL;
import static net.goui.phonenumber.PhoneNumberFormatter.FormatType.NATIONAL;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Expect;
import com.google.common.truth.StandardSubjectBuilder;
import com.google.common.truth.Truth;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import net.goui.phonenumber.AbstractPhoneNumberClassifier;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.PhoneNumber;
import net.goui.phonenumber.PhoneNumberFormatter;
import net.goui.phonenumber.PhoneNumberParser;
import net.goui.phonenumber.PhoneNumberResult;
import net.goui.phonenumber.PhoneNumbers;
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

  private static final class TestClassifier extends AbstractPhoneNumberClassifier {
    final PhoneNumberFormatter national = createFormatter(NATIONAL);
    final PhoneNumberFormatter international = createFormatter(INTERNATIONAL);
    final PhoneNumberParser<String> parser = createParser(identity());

    TestClassifier(RawClassifier rawClassifier) {
      super(rawClassifier);
    }

    Set<String> classify(PhoneNumber number, String type) {
      return rawClassifier().classify(number.getCallingCode(), number.getNationalNumber(), type);
    }

    String format(PhoneNumber number, String type) {
      switch (type) {
        case "NATIONAL_FORMAT":
          return national.format(number);
        case "INTERNATIONAL_FORMAT":
          return international.format(number);
        default:
          throw new AssertionError("Unknown format type: " + type);
      }
    }

    public PhoneNumberResult parse(String formatted, String type, DigitSequence callingCode) {
      switch (type) {
        case "NATIONAL_FORMAT":
          String mainRegion = checkNotNull(parser.getRegions(callingCode).get(0));
          return parser.parseStrictly(formatted, mainRegion);
        case "INTERNATIONAL_FORMAT":
          return parser.parseStrictly(formatted);
        default:
          throw new AssertionError("Unknown format type: " + type);
      }
    }
  }

  private final TestClassifier classifier;
  private final StandardSubjectBuilder truthStrategy;

  private RegressionTester(RawClassifier rawClassifier, StandardSubjectBuilder truthStrategy) {
    this.classifier = new TestClassifier(rawClassifier);
    this.truthStrategy = truthStrategy;
  }

  public void assertGoldenData(Path jsonPath) throws IOException {
    JsObject jsonTestData;
    try (Reader reader = Files.newBufferedReader(jsonPath, UTF_8)) {
      jsonTestData = JsonParser.parse(reader).asObject();
    }
    objectsOf(jsonTestData, "testdata").forEach(this::assertResults);
  }

  public void assertGoldenData(String json) {
    JsObject jsonTestData = JsonParser.parse(json).asObject();
    objectsOf(jsonTestData, "testdata").forEach(this::assertResults);
  }

  private void assertResults(JsObject jsonResults) {
    DigitSequence cc = DigitSequence.parse(getString(jsonResults, "cc"));
    DigitSequence nn = DigitSequence.parse(getString(jsonResults, "number"));
    PhoneNumber number = PhoneNumbers.fromE164("+" + cc + nn);
    objectsOf(jsonResults, "result").forEach(r -> assertResult(number, r));
    objectsOf(jsonResults, "format").forEach(f -> assertFormatAndParse(number, f));
  }

  private void assertResult(PhoneNumber number, JsObject jsonResult) {
    String type = getString(jsonResult, "type");
    ImmutableSet<String> expected =
        jsonResult.get("values").asArray().stream()
            .map(JsValue::asString)
            .map(JsString::value)
            .collect(toImmutableSet());
    truthStrategy
        .withMessage("classifying '%s' for %s", type, number)
        .that(classifier.classify(number, type))
        .containsExactlyElementsIn(expected);
  }

  private void assertFormatAndParse(PhoneNumber number, JsObject jsonResult) {
    String type = getString(jsonResult, "type");
    String expected = getString(jsonResult, "value");
    truthStrategy
        .withMessage("formatting %s as %s", number, type)
        .that(classifier.format(number, type))
        .isEqualTo(expected);
    PhoneNumberResult parseResult = classifier.parse(expected, type, number.getCallingCode());
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getPhoneNumber())
        .isEqualTo(number);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getResult())
        .isEqualTo(MATCHED);
  }

  private static String getString(JsObject json, String field) {
    return json.get(field).asString().value();
  }

  private static Stream<JsObject> objectsOf(JsObject json, String field) {
    return json.get(field).asArray().stream().map(JsValue::asObject);
  }
}
