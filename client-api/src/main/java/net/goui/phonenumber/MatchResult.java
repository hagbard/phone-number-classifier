/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import com.google.common.collect.Comparators;

/**
 * Result values for "match" operations on phone numbers.
 *
 * <p>When testing phone numbers via methods such as {@link
 * AbstractPhoneNumberClassifier#match(PhoneNumber)}, the returned value can provide information about
 * partially matched numbers, allowing callers to give useful feedback to the user (e.g. when phone
 * numbers are being entered).
 */
public enum MatchResult {
  /**
   * The given number was matched exactly, and a "matched" number is likely to be valid.
   *
   * <p>Note that {@code #MATCHED} does not mean a number is definitely callable, or even valid.
   * Numbering plans (from which phone number metadata is typically sourced) may list number ranges
   * simplistically, including numbers which are either not yet connected or, in some cases, will
   * never be connected.
   *
   * <p>Due to the unavoidable chance of false-positive matches, anyone using this library should
   * take additional steps to verify phone numbers (e.g. via an SMS) when "real world" validity is
   * important.
   */
  MATCHED,

  /**
   * The given number was unmatched, but could potentially become {@link #MATCHED} if more digits
   * were appended to it.
   *
   * <p>This is useful when considering incremental input of phone numbers and by distinguishing
   * partially matched numbers from invalid numbers, a user interface can offer better feedback as
   * numbers are entered.
   *
   * <p>Note: In cases where number ranges have multiple valid lengths, it is possible (though very
   * unlikely) that as digits are added to a number it goes from being {@code MATCHED} to being a
   * {@code PARTIAL_MATCH} again due to the existence of another longer match. In this respect,
   * {@code #PARTIAL_MATCH} has a higher precedence than {@link #EXCESS_DIGITS}.
   */
  PARTIAL_MATCH,

  /**
   * The given number was unmatched, but will become {@link #MATCHED} if some number of trailing
   * digits are removed from it. Adding extra digits will never make this number valid.
   *
   * <p>This is potentially useful when considering incremental input of phone numbers, but in
   * general is less likely to occur than {@link #PARTIAL_MATCH} if the user stops entering digits
   * when the number is complete.
   */
  EXCESS_DIGITS,

  /**
   * The given number was unmatched and should not be considered valid without further confirmation.
   * No amount of adding or removing digits could make this number {@link #MATCHED}.
   *
   * <p>This result can occur a number of any length once it can no longer satisfy any possible
   * valid number.
   *
   * <p>This is the general case for an unmatched number and the default return value for any case
   * not described above, and can serve as the "identity" value when reducing multiple results.
   */
  INVALID;

  /**
   * Combines the results from two matching operations on a single number in an order-independent
   * way. This is useful when composing matchers to build new semantics. The result is the same as
   * the number were tested against the union of ranges represented by both matchers.
   *
   * <p>This can also be used to "reduce" a stream of results via something like:
   *
   * <pre>{@code
   * MatchResult combinedResult =
   *     matchers.stream().map(m -> m.match(number)).reduce(UNMATCHED, MatchResult::combine);
   * }</pre>
   */
  public static MatchResult combine(MatchResult first, MatchResult second) {
    // This works because the enum is ordered by precedence.
    // 1. A MATCHED result in one set means the number would be matched in the union of both sets
    //    (and this take precedence over any other results).
    // 2. A PARTIAL_MATCH result in one set would be a PARTIAL_MATCH in the union of both sets
    //    if it wasn't MATCHED (even if the other result was EXCESS_DIGITS).
    // 3. An EXCESS_DIGITS result in one set takes precedence over UNMATCHED in the other.
    return Comparators.min(first, second);
  }

  /** Whether this match is "better" than the given match. */
  public boolean isBetterThan(MatchResult r) {
    return compareTo(r) < 0;
  }
}
