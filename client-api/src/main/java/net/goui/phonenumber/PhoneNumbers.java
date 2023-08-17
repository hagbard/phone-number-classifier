/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.base.CharMatcher;

/**
 * Static factory class for obtaining {@link PhoneNumber} instances from E.164 strings.
 *
 * <p>See <a href="https://en.wikipedia.org/wiki/E.164">the E.164 specification</a>.
 */
public final class PhoneNumbers {
  private static final CharMatcher ASCII_DIGIT = CharMatcher.inRange('0', '9');

  // Obtained from running GenerateMetadata tool (encoded string is output to console).
  private static final String CC_MASK =
      "\u0082\uc810\ufb97\uf7fb\u0007\ufc56\u0004\u0000\u0000\u0000\u0000\u0000\u0000\uf538"
          + "\uffff\uffff\u3ff7\u0000\u0e0c\u0000\u0000\uc000\u00ff\uf7fc\u002e\u0000\u00b0\u0000"
          + "\u0000\u0000\u0000\u3ff0\u0000\u0000\u0000\u0000\uc000\u00ff\u0000\u0000\u0000\u4000"
          + "\uefff\u001f\u0000\u0000\u0000\u0000\u0000\u0000\u0101\u0000\u0000\u01b4\u4040\u014f"
          + "\u0000\u0000\u0000\u0000\ufdff\u000b\u005f";

  /**
   * Returns a new phone number instance from the given E.164 formatted string. This method is the
   * exact inverse of {@link PhoneNumber#toString()}.
   *
   * <p>This method does no specific parsing and will not do things like removing erroneously added
   * national prefixes etc.
   *
   * <p><em>Note</em>: While E.164 is technically only defined for international numbers, this
   * method will accept and correct parse any number of the form {@code
   * "+<calling-code><national-number>"}, including things like national only free phone numbers
   * etc. This is a slight abuse of the E.164 specification, but it is well-defined and never risks
   * being ambiguous.
   */
  public static PhoneNumber fromE164(String e164) {
    String toEncode = e164.startsWith("+") ? e164.substring(1) : e164;
    checkArgument(toEncode.length() >= 3, "E.164 numbers must have at least three digits");
    checkArgument(
        ASCII_DIGIT.matchesAllOf(toEncode),
        "E.164 numbers must contain only decimal digits and allowed separators: %s",
        e164);
    DigitSequence cc = extractCallingCode(toEncode);
    checkArgument(cc != null, "Unknown calling code %s in E.164 number: %s", cc, e164);
    return E164PhoneNumber.of(cc, DigitSequence.parse(toEncode.substring(cc.length())));
  }

  static PhoneNumber of(DigitSequence callingCode, DigitSequence nationalNumber) {
    checkArgument(isCallingCode(callingCode), "Invalid calling code: %s", callingCode);
    return E164PhoneNumber.of(callingCode, nationalNumber);
  }

  // Visible to PhoneNumberParser, so can be given arbitrary sequence a input.
  static DigitSequence extractCallingCode(String seq) {
    int len = seq.length();
    if (len == 0) return null;
    int cc = digitOf(seq, 0);
    if (cc == 0) return null;
    if (!isCallingCode(cc)) {
      if (len == 1) return null;
      cc = (10 * cc) + digitOf(seq, 1);
      if (!isCallingCode(cc)) {
        if (len == 2) return null;
        cc = (10 * cc) + digitOf(seq, 2);
        if (!isCallingCode(cc)) return null;
      }
    }
    return DigitSequence.parse(Integer.toString(cc));
  }

  private static int digitOf(String s, int n) {
    int d = s.charAt(n) - '0';
    checkArgument(d >= 0 && d <= 9, "Invalid decimal digit in: %s", s);
    return d;
  }

  private static boolean isCallingCode(int cc) {
    int bits = CC_MASK.charAt(cc >>> 4);
    return (bits & (1 << (cc & 0xF))) != 0;
  }

  private static boolean isCallingCode(DigitSequence cc) {
    return !cc.isEmpty()
        && cc.length() <= 3
        && cc.getDigit(0) != 0
        && isCallingCode(Integer.parseInt(cc.toString()));
  }

  @AutoValue
  abstract static class E164PhoneNumber implements PhoneNumber {
    static PhoneNumber of(DigitSequence callingCode, DigitSequence nationalNumber) {
      return new AutoValue_PhoneNumbers_E164PhoneNumber(callingCode, nationalNumber);
    }

    @Override
    public abstract DigitSequence getCallingCode();

    @Override
    public abstract DigitSequence getNationalNumber();

    @Override
    public final DigitSequence getDigits() {
      return getCallingCode().append(getNationalNumber());
    }

    @Override
    public final int length() {
      return getCallingCode().length() + getNationalNumber().length();
    }

    @Memoized
    @Override
    public String toString() {
      return "+" + getCallingCode() + getNationalNumber();
    }
  }

  private PhoneNumbers() {}
}
