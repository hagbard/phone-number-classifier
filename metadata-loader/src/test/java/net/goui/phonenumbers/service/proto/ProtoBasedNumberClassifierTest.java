/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

  This program and the accompanying materials are made available under the terms of the
  Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
  Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.service.proto;

import static com.google.common.truth.Truth.assertThat;
import static net.goui.phonenumbers.LengthResult.INVALID_LENGTH;
import static net.goui.phonenumbers.LengthResult.POSSIBLE;
import static net.goui.phonenumbers.LengthResult.TOO_LONG;
import static net.goui.phonenumbers.MatchResult.EXCESS_DIGITS;
import static net.goui.phonenumbers.MatchResult.MATCHED;
import static net.goui.phonenumbers.MatchResult.PARTIAL_MATCH;
import static net.goui.phonenumbers.MatchResult.POSSIBLE_LENGTH;

import java.io.IOException;
import net.goui.phonenumbers.DigitSequence;
import net.goui.phonenumbers.metadata.RawClassifier;
import net.goui.phonenumbers.metadata.VersionInfo;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ProtoBasedNumberClassifierTest {

  public static final VersionInfo VERSION = VersionInfo.of("goui.net/test_metadata/dfa", 1, 1, 0);

  @Test
  public void test() throws IOException {

    class TestService extends AbstractResourceClassifierService {
      protected TestService() {
        super(VERSION, "/test_metadata.pb");
      }
    }
    TestService service = new TestService();
    RawClassifier classifier = service.load();
    assertThat(classifier.getSupportedCallingCodes()).containsAtLeast(seq("1"), seq("44"));
    assertThat(classifier.getSupportedNumberTypes()).containsAtLeast("TYPE", "REGION");
    assertThat(classifier.getVersion()).isEqualTo(VERSION);
    assertThat(classifier.getPossibleValues("TYPE"))
        .containsExactly(
            "FIXED_LINE",
            "FIXED_LINE_OR_MOBILE",
            "MOBILE",
            "PAGER",
            "PERSONAL_NUMBER",
            "UAN",
            "VOICEMAIL",
            "VOIP");

    DigitSequence us = DigitSequence.parse("1");
    // Classify a valid number to get its type (TYPE is single valued).
    assertThat(classifier.isSingleValued("TYPE")).isTrue();
    assertThat(classifier.classify(us, seq("6502123456"), "TYPE"))
        .containsExactly("FIXED_LINE_OR_MOBILE");
    assertThat(classifier.classifyUniquely(us, seq("6502123456"), "TYPE"))
        .isEqualTo("FIXED_LINE_OR_MOBILE");

    // Match a partial number.
    assertThat(classifier.match(us, seq("650212345"))).isEqualTo(PARTIAL_MATCH);
    // This is NOT "TOO_SHORT" because in the US, there are 7-digit UAN numbers.
    assertThat(classifier.testLength(us, seq("650212345"))).isEqualTo(INVALID_LENGTH);

    // Match a valid number.
    assertThat(classifier.match(us, seq("6502123456"))).isEqualTo(MATCHED);
    assertThat(classifier.testLength(us, seq("6502123456"))).isEqualTo(POSSIBLE);

    // Match a number with excess digits.
    assertThat(classifier.match(us, seq("65021234567"))).isEqualTo(EXCESS_DIGITS);
    assertThat(classifier.testLength(us, seq("65021234567"))).isEqualTo(TOO_LONG);

    // Match for a specific value.
    assertThat(
            classifier
                .getValueMatcher(us, "TYPE")
                .matchValue(seq("650212345"), "FIXED_LINE_OR_MOBILE"))
        .isEqualTo(PARTIAL_MATCH);
    assertThat(
            classifier
                .getValueMatcher(us, "TYPE")
                .matchValue(seq("6502123456"), "FIXED_LINE_OR_MOBILE"))
        .isEqualTo(MATCHED);
    assertThat(
            classifier
                .getValueMatcher(us, "TYPE")
                .matchValue(seq("65021234567"), "FIXED_LINE_OR_MOBILE"))
        .isEqualTo(EXCESS_DIGITS);

    // For a different value this has a possible length, but is not a match.
    assertThat(classifier.getValueMatcher(us, "TYPE").matchValue(seq("6502123456"), "FIXED_LINE"))
        .isEqualTo(POSSIBLE_LENGTH);

    // Match for region(s) (this is multi-valued).
    assertThat(classifier.isSingleValued("REGION")).isFalse();
    assertThat(classifier.classify(us, seq("6502123456"), "REGION")).containsExactly("US");
    // Cannot classify uniquely for multi-valued types (even if there's only one result).
    Assert.assertThrows(
        UnsupportedOperationException.class,
        () -> classifier.classifyUniquely(us, seq("6502123456"), "REGION"));

    DigitSequence gb = DigitSequence.parse("44");
    assertThat(classifier.classify(gb, seq("7691123456"), "REGION"))
        .containsExactly("GB", "GG", "JE");
    assertThat(classifier.classify(gb, seq("7924123456"), "REGION")).containsExactly("GB", "IM");
  }

  static DigitSequence seq(String s) {
    return DigitSequence.parse(s);
  }
}
