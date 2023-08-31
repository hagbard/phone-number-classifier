/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;
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
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;
import java.util.EnumMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.LengthResult;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumbers.proto.Metadata.MatcherDataProto;
import net.goui.phonenumber.shading.com.google.i18n.phonenumbers.metadata.finitestatematcher.DigitSequenceMatcher;

/**
 * Encapsulation of a function to match digit sequences, either partially or completely.
 *
 * <p>The underlying mechanism for matching is chosen by the metadata used, and may be based on
 * regular expressions or the "libhonenumber" {@code DigitSequenceMatcher}.
 */
abstract class MatcherFunction {

  private static final ImmutableMap<DigitSequenceMatcher.Result, MatchResult> RESULT_MAP;

  static {
    EnumMap<DigitSequenceMatcher.Result, MatchResult> resultMap =
        new EnumMap<>(DigitSequenceMatcher.Result.class);
    resultMap.put(DigitSequenceMatcher.Result.MATCHED, MATCHED);
    resultMap.put(DigitSequenceMatcher.Result.INVALID, INVALID);
    resultMap.put(DigitSequenceMatcher.Result.TOO_SHORT, PARTIAL_MATCH);
    resultMap.put(DigitSequenceMatcher.Result.TOO_LONG, EXCESS_DIGITS);
    RESULT_MAP = ImmutableMap.copyOf(resultMap);
    Sets.SetView<DigitSequenceMatcher.Result> unknownValues =
        Sets.difference(
            ImmutableSet.copyOf(DigitSequenceMatcher.Result.values()), RESULT_MAP.keySet());
    checkState(
        unknownValues.isEmpty(),
        "unknown values in DigitSequenceMatcher.Result: %s",
        unknownValues);
  }

  private static final MatcherFunction EMPTY_MATCHER = new MatcherFunction(0) {
    @Override
    public MatchResult match(DigitSequence s) {
      return INVALID;
    }

    @Override
    public boolean isMatch(DigitSequence s) {
      return false;
    }
  };

  static MatchResult resultOf(DigitSequenceMatcher.Result r) {
    return RESULT_MAP.get(r);
  }

  // The returned functions will be held for a long time, so should avoid referencing the proto
  // itself.

  static MatcherFunction fromProto(MatcherDataProto proto) {
    int lengthMask = proto.getPossibleLengthsMask();
    ByteString dfaBytes = proto.getMatcherData();
    if (!dfaBytes.isEmpty()) {
      return new DfaMatcher(lengthMask, dfaBytes);
    }
    // Regex is expected to take up considerably more memory than the DFA matcher.
    String regex = proto.getRegexData();
    if (!regex.isEmpty()) {
      return new RegexMatcher(lengthMask, regex);
    }
    return EMPTY_MATCHER;
  }

  static MatcherFunction combine(List<MatcherFunction> functions) {
    return functions.size() > 1 ? new CombinedMatcher(functions) : functions.get(0);
  }

  private final int lengthMask;

  MatcherFunction(int lengthMask) {
    this.lengthMask = lengthMask;
  }

  /**
   * Returns matcher information about a given {@link DigitSequence}.
   *
   * <p>This is more useful than {@link #isMatch(DigitSequence)} since it returns information
   * regarding the state of unmatched sequences. If you only care about matching vs non-matching,
   * call {@link #isMatch(DigitSequence)} instead.
   */
  public abstract MatchResult match(DigitSequence s);

  /**
   * Returns whether a given {@link DigitSequence} matches this function. This is functionally
   * equivalent to {@code match(s) == MatchResult.MATCHED}, but is potentially faster.
   *
   * <p>This can be thought of as testing whether the given sequence is "in the set of all matched
   * sequences for this function" (i.e. it can be thought of as a set containment test).
   */
  public abstract boolean isMatch(DigitSequence s);

  /** Returns information about a digit sequence based only on the set of known possible lengths. */
  public LengthResult testLength(DigitSequence s) {
    int lengthBit = 1 << s.length();
    if ((lengthMask & lengthBit) != 0) {
      return POSSIBLE;
    }
    // (lengthBit - 1) is a bit-mask which retains only the possible lengths shorter than the input.
    int possibleShorterLengthMask = lengthMask & (lengthBit - 1);
    return possibleShorterLengthMask == 0
        // If there are no possible lengths shorter than the input, the input is too short.
        ? TOO_SHORT
        // If all the possible lengths are shorter than the input, the input is too long.
        : possibleShorterLengthMask == lengthMask ? TOO_LONG : INVALID_LENGTH;
  }

  static final class DfaMatcher extends MatcherFunction {
    private final DigitSequenceMatcher matcher;

    public DfaMatcher(int lengthMask, ByteString dfaBytes) {
      super(lengthMask);
      this.matcher = DigitSequenceMatcher.create(dfaBytes.toByteArray());
    }

    @Override
    public MatchResult match(DigitSequence s) {
      MatchResult result = resultOf(matcher.match(input(s)));
      if (result == INVALID && testLength(s) == POSSIBLE) {
        result = POSSIBLE_LENGTH;
      }
      return result;
    }

    @Override
    public boolean isMatch(DigitSequence s) {
      return testLength(s) == POSSIBLE
          && matcher.match(input(s)) == DigitSequenceMatcher.Result.MATCHED;
    }

    // Lightweight adapter from public type to libphonenumber type (over which we have no control).
    private static DigitSequenceMatcher.DigitSequence input(DigitSequence sequence) {
      return new DigitSequenceMatcher.DigitSequence() {
        final DigitSequence.Digits it = sequence.iterate();

        @Override
        public boolean hasNext() {
          return it.hasNext();
        }

        @Override
        public int next() {
          return it.next();
        }
      };
    }
  }

  static final class RegexMatcher extends MatcherFunction {
    private final Pattern pattern;

    public RegexMatcher(int lengthMask, String regex) {
      super(lengthMask);
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public MatchResult match(DigitSequence s) {
      Matcher matcher = pattern.matcher(s.toString());
      // hitEnd() can be true even when the matcher matched a shorter sequence (e.g. when a
      // regex matches multiple lengths). If it's true, then (from the docs):
      //   "it is possible that more input would have changed the result of the last search"
      // which is exactly what "PARTIAL_MATCH" means.
      if (matcher.lookingAt()) {
        return matcher.end() == s.length()
            ? MATCHED
            : matcher.hitEnd() ? PARTIAL_MATCH : EXCESS_DIGITS;
      } else {
        return matcher.hitEnd()
            ? PARTIAL_MATCH
            : testLength(s) == POSSIBLE ? POSSIBLE_LENGTH : INVALID;
      }
    }

    @Override
    public boolean isMatch(DigitSequence s) {
      return testLength(s) == POSSIBLE && pattern.matcher(s.toString()).matches();
    }
  }

  static final class CombinedMatcher extends MatcherFunction {
    private final ImmutableList<MatcherFunction> functions;

    CombinedMatcher(List<MatcherFunction> functions) {
      super(unionOfPossibleLengths(functions));
      this.functions = ImmutableList.copyOf(functions);
    }

    private static int unionOfPossibleLengths(List<MatcherFunction> functions) {
      return functions.stream().mapToInt(f -> f.lengthMask).reduce(0, (a, b) -> a | b);
    }

    @Override
    public MatchResult match(DigitSequence s) {
      // We could stream this, but that doesn't know it can stop once MATCHED is returned.
      // We also seek to avoid allocations at this low level.
      MatchResult combinedResult = INVALID;
      for (int i = 0, functionsSize = functions.size(); i < functionsSize; i++) {
        MatchResult result = functions.get(i).match(s);
        if (result == MATCHED) {
          return result;
        }
        combinedResult = MatchResult.combine(combinedResult, result);
      }
      return combinedResult;
    }

    @Override
    public boolean isMatch(DigitSequence s) {
      if (testLength(s) == POSSIBLE) {
        // Seek to avoid allocations at this low level.
        for (int i = 0, functionsSize = functions.size(); i < functionsSize; i++) {
          if (functions.get(i).isMatch(s)) {
            return true;
          }
        }
      }
      return false;
    }
  }
}
