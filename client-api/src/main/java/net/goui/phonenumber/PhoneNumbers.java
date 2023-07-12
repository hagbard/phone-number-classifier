/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.CharMatcher.whitespace;
import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.CharMatcher;

/** <a href="https://en.wikipedia.org/wiki/E.164">E.164 specification</a>. */
public final class PhoneNumbers {
  private static final CharMatcher SEPARATOR = CharMatcher.whitespace().or(CharMatcher.anyOf("-."));
  private static final CharMatcher LENIENT_E164_DIGIT = CharMatcher.inRange('0', '9').or(SEPARATOR);

  public static PhoneNumber parseE164(String e164) {
    String toEncode = e164.startsWith("+") ? e164.substring(1) : e164;
    checkArgument(toEncode.length() >= 3, "E.164 numbers must have at least three digits");
    checkArgument(
        LENIENT_E164_DIGIT.matchesAllOf(toEncode),
        "E.164 numbers must contain only decimal digits and allowed separators: %s",
        e164);
    return SimpleEncodedE164.of(whitespace().removeFrom(toEncode));
  }

  @AutoValue
  abstract static class SimpleEncodedE164 implements PhoneNumber {
    abstract long encoded();

    private static PhoneNumber of(String digits) {
      return new AutoValue_PhoneNumbers_SimpleEncodedE164(DigitSequenceEncoder.encode(digits));
    }

    @Memoized
    @Override
    public DigitSequence getDigits() {
      return DigitSequence.ofEncoded(encoded());
    }

    @Override
    public final int getDigit(int n) {
      return DigitSequenceEncoder.getDigit(n, encoded());
    }

    @Override
    public final int length() {
      return DigitSequenceEncoder.getLength(encoded());
    }

    @Memoized
    @Override
    public String toString() {
      return "+" + DigitSequenceEncoder.toString(encoded());
    }
  }

  private PhoneNumbers() {}
}
