/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static net.goui.phonenumber.LengthResult.INVALID_LENGTH;
import static net.goui.phonenumber.LengthResult.POSSIBLE;
import static net.goui.phonenumber.LengthResult.TOO_LONG;
import static net.goui.phonenumber.LengthResult.TOO_SHORT;
import static net.goui.phonenumber.MatchResult.EXCESS_DIGITS;
import static net.goui.phonenumber.MatchResult.INVALID;
import static net.goui.phonenumber.MatchResult.MATCHED;
import static net.goui.phonenumber.MatchResult.PARTIAL_MATCH;
import static net.goui.phonenumber.MatchResult.POSSIBLE_LENGTH;

import com.google.common.collect.ImmutableList;
import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.finitestatematcher.compiler.MatcherCompiler;
import com.google.i18n.phonenumbers.metadata.regex.RegexGenerator;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.function.Function;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumber.proto.Metadata;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class MatcherFunctionTest {
  private static final RegexGenerator REGEX_GENERATOR =
      RegexGenerator.basic()
          .withSubgroupOptimization()
          .withDfaFactorization()
          .withTailOptimization()
          .withDotMatch();

  private final Function<String[], MatcherFunction> newMatcherFn;

  public MatcherFunctionTest(Function<String[], MatcherFunction> fn) {
    this.newMatcherFn = fn;
  }

  @Parameters
  public static Iterable<?> getMatcherGenerators() {
    return ImmutableList.<Function<String[], MatcherFunction>>of(
        MatcherFunctionTest::getRegexMatcherFunction,
        MatcherFunctionTest::getDfaMatcherFunction,
        MatcherFunctionTest::getCombinedMatcherFunction);
  }

  MatcherFunction newMatcher(String... specs) {
    return newMatcherFn.apply(specs);
  }

  @Test
  public void testSimple() {
    MatcherFunction matcher = newMatcher("123_xxx", "456_xxx");
    assertMatched(matcher, "123456", "123000", "123999", "456000", "456999");
    assertPartialMatch(matcher, "1", "123", "12300", "45");
    assertExcessDigits(matcher, "1230000", "4569999");
    assertMatchResult(matcher, INVALID, "7", "789");
    assertMatchResult(matcher, POSSIBLE_LENGTH, "789000", "122999", "124000");
  }

  @Test
  public void testMultiLength() {
    MatcherFunction matcher = newMatcher("123_xxx", "123_xxx_xxx", "456_xxx_xxx_xxx");
    // Two matched numbers where one is a prefix of the other (rare but possible).
    assertMatched(matcher, "123546", "123456789");
    // Unmatched (but possible) prefixes with lengths between the two possible length are "partial
    // matches" and not "excess digits".
    assertPartialMatch(matcher, "12345", "1234567", "12345678");
    // Only once the longest possible length is exceeded do we return "excess digits".
    assertExcessDigits(matcher, "1234567890", "4561112223330");
  }

  private static void assertMatched(MatcherFunction matcher, String... numbers) {
    for (String s : numbers) {
      DigitSequence number = DigitSequence.parse(s);
      assertThat(matcher.testLength(number)).isEqualTo(POSSIBLE);
      assertThat(matcher.match(number)).isEqualTo(MATCHED);
      assertThat(matcher.isMatch(number)).isTrue();
    }
  }

  private static void assertPartialMatch(MatcherFunction matcher, String... numbers) {
    for (String s : numbers) {
      DigitSequence number = DigitSequence.parse(s);
      assertThat(matcher.testLength(number)).isAnyOf(TOO_SHORT, INVALID_LENGTH);
      assertThat(matcher.match(number)).isEqualTo(PARTIAL_MATCH);
      assertThat(matcher.isMatch(number)).isFalse();
    }
  }

  private static void assertExcessDigits(MatcherFunction matcher, String... numbers) {
    for (String s : numbers) {
      DigitSequence number = DigitSequence.parse(s);
      assertThat(matcher.testLength(number)).isAnyOf(TOO_LONG, INVALID_LENGTH);
      assertThat(matcher.match(number)).isEqualTo(EXCESS_DIGITS);
      assertThat(matcher.isMatch(number)).isFalse();
    }
  }

  private static void assertMatchResult(
      MatcherFunction matcher, MatchResult expected, String... numbers) {
    for (String s : numbers) {
      DigitSequence number = DigitSequence.parse(s);
      assertThat(matcher.match(number)).isEqualTo(expected);
      assertThat(matcher.isMatch(number)).isEqualTo(expected == MATCHED);
    }
  }

  private static MatcherFunction getDfaMatcherFunction(String... specs) {
    RangeTree ranges = rangesOf(specs);
    Metadata.MatcherDataProto proto =
        Metadata.MatcherDataProto.newBuilder()
            .setPossibleLengthsMask(lengthMaskOf(ranges))
            .setMatcherData(ByteString.copyFrom(MatcherCompiler.compile(ranges)))
            .build();
    return MatcherFunction.fromProto(proto);
  }

  private static MatcherFunction getRegexMatcherFunction(String... specs) {
    return matcherFrom(rangesOf(specs));
  }

  private static MatcherFunction getCombinedMatcherFunction(String... specs) {
    ImmutableList<MatcherFunction> functions =
        Arrays.stream(specs)
            .map(RangeSpecification::parse)
            .map(RangeTree::from)
            .map(MatcherFunctionTest::matcherFrom)
            .collect(toImmutableList());
    return MatcherFunction.combine(functions);
  }

  private static MatcherFunction matcherFrom(RangeTree ranges) {
    Metadata.MatcherDataProto proto =
        Metadata.MatcherDataProto.newBuilder()
            .setPossibleLengthsMask(lengthMaskOf(ranges))
            .setRegexData(REGEX_GENERATOR.toRegex(ranges))
            .build();
    return MatcherFunction.fromProto(proto);
  }

  private static RangeTree rangesOf(String[] specs) {
    return RangeTree.from(Arrays.stream(specs).map(RangeSpecification::parse));
  }

  private static Integer lengthMaskOf(RangeTree ranges) {
    return ranges.getLengths().stream().reduce(0, (m, b) -> m | (1 << b));
  }
}
