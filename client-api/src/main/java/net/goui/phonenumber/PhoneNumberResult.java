/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import com.google.auto.value.AutoValue;

/**
 * Encapsulates a parsed phone number and an associated match result. This is returned for strict
 * parsing and the match result can be used to provide user feedback for phone number entry.
 */
@AutoValue
public abstract class PhoneNumberResult<T> {
  static <T> PhoneNumberResult<T> of(
      PhoneNumber phoneNumber, MatchResult matchResult, FormatType formatType) {
    return new AutoValue_PhoneNumberResult<T>(phoneNumber, matchResult, formatType);
  }

  /** Returns the parsed phone number (which need not be valid). */
  public abstract PhoneNumber getPhoneNumber();

  /**
   * Returns the match result for the phone number, according to the parser's metadata. If the
   * result is {@link MatchResult#MATCHED}, then parsing was completely successful and unambiguous.
   */
  public abstract MatchResult getMatchResult();

  public abstract FormatType getInferredFormat();

  boolean isBetterThan(PhoneNumberResult<T> other) {
    return getMatchResult().compareTo(other.getMatchResult()) <= 0;
  }
}
