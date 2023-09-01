/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.testing;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.function.Function.identity;
import static net.goui.phonenumbers.FormatType.INTERNATIONAL;
import static net.goui.phonenumbers.FormatType.NATIONAL;
import static net.goui.phonenumbers.MatchResult.INVALID;
import static net.goui.phonenumbers.MatchResult.MATCHED;

import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Expect;
import com.google.common.truth.StandardSubjectBuilder;
import com.google.common.truth.Truth;
import java.util.Set;
import java.util.stream.Stream;
import net.goui.phonenumbers.AbstractPhoneNumberClassifier;
import net.goui.phonenumbers.DigitSequence;
import net.goui.phonenumbers.FormatType;
import net.goui.phonenumbers.PhoneNumber;
import net.goui.phonenumbers.PhoneNumberFormatter;
import net.goui.phonenumbers.PhoneNumberParser;
import net.goui.phonenumbers.PhoneNumberResult;
import net.goui.phonenumbers.PhoneNumbers;
import net.goui.phonenumbers.metadata.RawClassifier;
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

    public PhoneNumberParser<String> getParser() {
      return parser;
    }
  }

  private final TestClassifier classifier;
  private final StandardSubjectBuilder truthStrategy;

  private RegressionTester(RawClassifier rawClassifier, StandardSubjectBuilder truthStrategy) {
    this.classifier = new TestClassifier(rawClassifier);
    this.truthStrategy = truthStrategy;
  }

  public void assertGoldenData(String json) {
    JsObject jsonTestData = JsonParser.parse(json).asObject();
    objectsOf(jsonTestData, "testdata").forEach(this::assertResults);
  }

  private void assertResults(JsObject jsonResults) {
    DigitSequence cc = DigitSequence.parse(getString(jsonResults, "cc"));
    DigitSequence nn = DigitSequence.parse(getString(jsonResults, "number"));
    PhoneNumber number = PhoneNumbers.fromE164("+" + cc + nn);
    // Null if unsupported calling code.
    if (classifier.isSupportedCallingCode(cc)) {
      // Supported numbers can be classified, formatted and re-parsed correctly.
      objectsOf(jsonResults, "result").forEach(r -> assertResult(number, r));
      objectsOf(jsonResults, "format").forEach(f -> assertFormatAndParse(number, f, cc));
    } else {
      // Unsupported numbers cannot be classified, and can only be parsed from international format.
      objectsOf(jsonResults, "format")
          .filter(f -> getString(f, "type").equals("INTERNATIONAL_FORMAT"))
          .forEach(f -> assertParseUnsupported(number, f, cc));
    }
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

  private void assertFormatAndParse(PhoneNumber number, JsObject jsonResult, DigitSequence cc) {
    String type = getString(jsonResult, "type");
    String expected = getString(jsonResult, "value");
    truthStrategy
        .withMessage("formatting %s as %s", number, type)
        .that(classifier.format(number, type))
        .isEqualTo(expected);
    // If a valid number is supported in the metadata it is parsed successfully from any format.
    PhoneNumberResult<String> parseResult = classifier.getParser().parseStrictly(expected, cc);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getPhoneNumber())
        .isEqualTo(number);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getMatchResult())
        .isEqualTo(MATCHED);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getInferredFormat())
        .isEqualTo(formatTypeOf(type));
  }

  private void assertParseUnsupported(PhoneNumber number, JsObject jsonResult, DigitSequence cc) {
    String type = getString(jsonResult, "type");
    String expected = getString(jsonResult, "value");
    // If a valid number is unsupported in the metadata it can be parsed from international format,
    // but cannot be classified (it's always considers "invalid").
    PhoneNumberResult<String> parseResult = classifier.getParser().parseStrictly(expected, cc);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getPhoneNumber())
        .isEqualTo(number);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getMatchResult())
        .isEqualTo(INVALID);
    truthStrategy
        .withMessage("parsing [%s] as %s for original number: %s", expected, type, number)
        .that(parseResult.getInferredFormat())
        .isEqualTo(INTERNATIONAL);
  }

  private static String getString(JsObject json, String field) {
    return json.get(field).asString().value();
  }

  private static Stream<JsObject> objectsOf(JsObject json, String field) {
    return json.get(field).asArray().stream().map(JsValue::asObject);
  }

  private static FormatType formatTypeOf(String id) {
    if (id.equals("NATIONAL_FORMAT")) {
      return NATIONAL;
    } else if (id.equals("INTERNATIONAL_FORMAT")) {
      return INTERNATIONAL;
    }
    throw new IllegalArgumentException("Bad format ID: " + id);
  }
}
