/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.goui.phonenumbers.MatchResult.INVALID;
import static net.goui.phonenumbers.MatchResult.MATCHED;
import static net.goui.phonenumbers.MatchResult.PARTIAL_MATCH;

import com.google.common.io.CharStreams;
import com.google.common.truth.Expect;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Optional;
import net.goui.phonenumbers.DigitSequence;
import net.goui.phonenumbers.MatchResult;
import net.goui.phonenumbers.PhoneNumber;
import net.goui.phonenumbers.PhoneNumberParser;
import net.goui.phonenumbers.PhoneNumbers;
import net.goui.phonenumbers.testing.RegressionTester;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ExampleClassifierTest {
  @Rule public final Expect expect = Expect.create();

  private static final ExampleClassifier SIMPLE_CLASSIFIER = ExampleClassifier.getInstance();

  @Test
  public void testValidation() {
    // BBC enquiries.
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438000"))).isEqualTo(MATCHED);
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438"))).isEqualTo(PARTIAL_MATCH);
    assertThat(SIMPLE_CLASSIFIER.match(e164("+442087438000000")))
        .isEqualTo(MatchResult.EXCESS_DIGITS);

    assertThat(SIMPLE_CLASSIFIER.match(e164("+440000"))).isEqualTo(INVALID);
  }

  @Test
  public void testParser() {
    PhoneNumberParser<String> parser = SIMPLE_CLASSIFIER.getParser();
    assertThat(parser.parseLeniently("(079) 555 1234", "CH")).hasValue(PhoneNumbers.fromE164("+41795551234"));
    assertThat(parser.parseLeniently("(+41) 079 555-1234")).hasValue(PhoneNumbers.fromE164("+41795551234"));
  }

  @Test
  public void testParserRegions() {
    PhoneNumberParser<String> parser = SIMPLE_CLASSIFIER.getParser();
    assertThat(parser.getRegions(seq("44"))).containsExactly("GB", "GG", "IM", "JE").inOrder();
    assertThat(parser.getRegions(seq("41"))).containsExactly("CH");
    assertThat(parser.getRegions(seq("888"))).containsExactly("001");

    assertThat(parser.getCallingCode("IM")).hasValue(seq("44"));
    assertThat(parser.getCallingCode("CH")).hasValue(seq("41"));
    // Special case since there are several calling codes associated with "001".
    assertThat(parser.getCallingCode("001")).isEmpty();
  }

  @Test
  public void testParserExampleNumbers() {
    PhoneNumberParser<String> parser = SIMPLE_CLASSIFIER.getParser();

    Optional<PhoneNumber> exampleNumber = parser.getExampleNumber("GB");
    assertThat(exampleNumber).hasValue(e164("+447400123456"));
    assertThat(parser.getExampleNumber(seq("44"))).isEqualTo(exampleNumber);
    // Jersey (also +44) example number is not equal.
    assertThat(parser.getExampleNumber("JE")).isNotEqualTo(exampleNumber);
    // Check the example number is considered valid.
    assertThat(SIMPLE_CLASSIFIER.match(exampleNumber.get())).isEqualTo(MATCHED);
  }

  @Test
  public void testFormatting() {
    assertThat(SIMPLE_CLASSIFIER.national().format(e164("+442087438000")))
        .isEqualTo("020 8743 8000");
    assertThat(SIMPLE_CLASSIFIER.international().format(e164("+442087438000")))
        .isEqualTo("+44 20 8743 8000");
  }

  @Test
  public void testGoldenData() throws IOException {
    RegressionTester regressionTester =
        RegressionTester.forClassifier(SIMPLE_CLASSIFIER.rawClassifierForTesting(), expect);
    try (Reader goldenData =
        new InputStreamReader(
            checkNotNull(
                ExampleClassifierTest.class.getResourceAsStream("/simple_golden_data.json")),
            UTF_8)) {
      regressionTester.assertGoldenData(CharStreams.toString(goldenData));
    }
  }

  private static DigitSequence seq(String seq) {
    return DigitSequence.parse(seq);
  }

  private static PhoneNumber e164(String e164) {
    return PhoneNumbers.fromE164(e164);
  }
}
