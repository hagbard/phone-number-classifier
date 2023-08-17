/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

/** Results enum for matcher operations. */
export enum MatchResult {
  /**
   * The given number was matched exactly, and a "matched" number is likely to be valid.
   *
   * Note that `Matched` does not mean a number is definitely callable, or even valid. Numbering
   * plans (from which phone number metadata is typically sourced) may list number ranges
   * simplistically, including numbers which are either not yet connected or, in some cases, will
   * never be connected.
   *
   * Due to the unavoidable chance of false-positive matches, anyone using this library should
   * take additional steps to verify phone numbers (e.g. via an SMS) when "real world" validity is
   * important.
   */
  Matched,

  /**
   * The given number was unmatched, but could potentially become `Matched` if more digits
   * were appended to it.
   *
   * This is useful when considering incremental input of phone numbers and by distinguishing
   * partially matched numbers from invalid numbers, a user interface can offer better feedback as
   * numbers are entered.
   *
   * Note: In cases where number ranges have multiple valid lengths, it is possible (though very
   * unlikely) that as digits are added to a number it goes from being `Matched` to being a
   * `PartialMatch` again due to the existence of another longer match. In this respect,
   * `PartialMatch` has a higher precedence than `ExcessDigits`.
   */
  PartialMatch,

  /**
   * The given number was unmatched, but will become `Matched` if some number of trailing
   * digits are removed from it. Adding extra digits will never make this number valid.
   *
   * This is potentially useful when considering incremental input of phone numbers, but in
   * general is less likely to occur than `PartialMatch` if the user stops entering digits
   * when the number is complete.
   */
  ExcessDigits,

  /**
   * The given number was unmatched, but has the length of a valid number. No amount of adding or
   * removing digits could make this number `Matched`, but changing a digit, or digits, might.
   *
   * This result is useful for distinguishing types of match failure, but it has a lower
   * precedence than `PartialMatch` because when a user is correctly entering a number, we
   * want to see a sequence of partial matches followed by `Matched`, without ever returning
   * `PossibleLength`.
   *
   * If you wish to test numbers by length, it is better to call `testLength(PhoneNumber)` in
   * AbstractPhoneNumberClassifier directly than to infer length information from match results.
   */
  PossibleLength,

  /**
   * The given number was unmatched and should not be considered valid without further confirmation.
   * No amount of adding or removing digits could make this number `Matched`.
   *
   * This result can occur a number of any length once it can no longer satisfy any possible
   * valid number.
   *
   * This is the general case for an unmatched number and the default return value for any case
   * not described above, and can serve as the "identity" value when reducing multiple results.
   */
  Invalid,
}

/** Results enum for length tests. */
export enum LengthResult {
  /**
   * The given number's length was in the set of possible number lengths. This implies very little
   * about whether the number is valid.
   */
  Possible,
  /** The given number was shorter than any possible valid number. */
  TooShort,
  /** The given number was longer than any possible valid number. */
  TooLong,
  /**
   * The given number's length was between the shortest and longest possible number, but was not
   * itself possible.
   */
  InvalidLength,
}
