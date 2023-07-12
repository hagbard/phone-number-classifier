/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.CharMatcher;
import java.util.Arrays;

/** https://en.wikipedia.org/wiki/E.164 */
final class DigitSequenceEncoder {
  private static final CharMatcher E164_DIGIT = CharMatcher.inRange('0', '9');
  // Maximum encoded digits in a 64-bit long (includes overflow).
  private static final int MAX_DIGITS = 19;
  // 1, 10, 100, 1000 ...
  private static final long[] POWERS_OF_TEN = new long[MAX_DIGITS];
  // 1, 11, 111, 1111 ....
  private static final long[] THRESHOLDS = new long[MAX_DIGITS];
  // 999...999 - 19 digits long (but -ve due to overflow).
  private static final long MAX_VALUE_UNSIGNED;
  // 111...1110 - 20 digits long (but -ve due to overflow).
  private static final long MAX_ENCODED_UNSIGNED;

  static {
    long powerOfTen = 1;
    long threshold = 1;
    for (int n = 0; n < MAX_DIGITS; n++) {
      POWERS_OF_TEN[n] = powerOfTen;
      THRESHOLDS[n] = threshold;
      powerOfTen *= 10;
      threshold += powerOfTen;
    }
    MAX_VALUE_UNSIGNED = powerOfTen - 1;
    MAX_ENCODED_UNSIGNED = threshold - 1;
  }

  static long encode(String digits) {
    checkArgument(E164_DIGIT.matchesAllOf(digits), "invalid digit sequence: '%s'", digits);
    int length = digits.length();
    checkArgument(length <= MAX_DIGITS, "digit sequence too long (max=%s): %s", MAX_DIGITS, digits);
    return length > 0 ? Long.parseUnsignedLong(digits) + THRESHOLDS[length - 1] : 0L;
  }

  static long encode(long value, int length) {
    checkArgument(length > 0 && length <= MAX_DIGITS, "invalid sequence length: %s", length);
    checkArgument(
        Long.compareUnsigned(value, MAX_VALUE_UNSIGNED) <= 0, "value too large: %s", value);
    checkArgument(
        length == MAX_DIGITS || POWERS_OF_TEN[length - 1] > value,
        "value too large for length (%s): %s",
        length,
        value);
    return value + THRESHOLDS[length - 1];
  }

  static DigitSequence.Digits iterate(long encoded) {
    // MAX_ENCODED is a negative value due to wrap around, so be careful about range checking.
    checkArgument(encoded >= 0 || encoded <= MAX_ENCODED_UNSIGNED);
    int length = getLength(encoded);
    long digits = length > 0 ? encoded - THRESHOLDS[length - 1] : 0;
    return new EncodedDigits(digits, length);
  }

  static int getLength(long encoded) {
    // Need special case for MAX_DIGITS since it's negative, so we cannot binary search for it.
    if (encoded >= THRESHOLDS[MAX_DIGITS - 1] || encoded < 0) {
      return MAX_DIGITS;
    }
    // Search index:
    // +ve: exact threshold, length = idx + 1 (e.g. '111' encodes '000' and idx == 2).
    // -ve: below threshold, length = ~idx (e.g. '110' encodes '99' and ~idx == 2).
    int idx = Arrays.binarySearch(THRESHOLDS, 0, MAX_DIGITS - 1, encoded);
    return idx >= 0 ? idx + 1 : ~idx;
  }

  static int compareLengthOf(long encoded, int length) {
    checkArgument(length >= 0 && length <= MAX_DIGITS, "invalid sequence length: %s", length);
    if (length > 0 && encoded < THRESHOLDS[length - 1]) {
      return -1;
    } else if (length < MAX_DIGITS && encoded >= THRESHOLDS[length]) {
      return 1;
    } else {
      return 0;
    }
  }

  static int getDigit(int n, long encoded) {
    int length = getLength(encoded);
    if (n < 0 || n >= length) {
      throw new IndexOutOfBoundsException(
          "invalid index (" + n + ") for sequence: " + toString(encoded));
    }
    long digits = encoded - THRESHOLDS[length - 1];
    // Be careful here since digits *and* scaled can both sometimes be -ve.
    long scaled = Long.divideUnsigned(digits, POWERS_OF_TEN[(length - 1) - n]);
    return (int) (scaled - (10 * Long.divideUnsigned(scaled, 10)));
  }

  static long append(long prefix, long suffix) {
    if (suffix == 0) {
      return prefix;
    } else if (prefix == 0) {
      return suffix;
    } else {
      int suffixLength = getLength(suffix);
      if (getLength(prefix) + suffixLength > MAX_DIGITS) {
        throw new IllegalArgumentException(
            "appended sequence too long (prefix="
                + toString(prefix)
                + ", suffix="
                + toString(suffix)
                + ")");
      }
      return prefix * POWERS_OF_TEN[suffixLength] + suffix;
    }
  }

  static long split(long encoded, int maxLength, boolean prefix) {
    if (maxLength == 0) {
      return 0;
    } else if (compareLengthOf(encoded, maxLength) <= 0) {
      return encoded;
    } else {
      int length = getLength(encoded);
      // Implies (0 < maxLength < length <= MAX_DIGITS) here.
      long digits = encoded - THRESHOLDS[length - 1];
      long truncatedDigits;
      if (prefix) {
        long scale = POWERS_OF_TEN[length - maxLength];
        truncatedDigits = Long.divideUnsigned(digits, scale);
      } else {
        long scale = POWERS_OF_TEN[maxLength];
        truncatedDigits = digits - (scale * Long.divideUnsigned(digits, scale));
      }
      return truncatedDigits + THRESHOLDS[maxLength - 1];
    }
  }

  static String toString(long encoded) {
    int length = getLength(encoded);
    StringBuilder buf = new StringBuilder(length);
    for (DigitSequence.Digits seq = iterate(encoded); seq.hasNext(); ) {
      buf.append((char) ('0' + seq.next()));
    }
    return buf.toString();
  }

  private static class EncodedDigits implements DigitSequence.Digits {
    private long value;
    private long modulo;

    EncodedDigits(long digits, int length) {
      value = digits;
      modulo = length > 0 ? POWERS_OF_TEN[length - 1] : 0;
    }

    @Override
    public boolean hasNext() {
      return modulo > 0;
    }

    @Override
    public int next() {
      checkState(modulo > 0, "no next digit in sequence");
      long digit = Long.divideUnsigned(value, modulo);
      value -= digit * modulo;
      modulo = Long.divideUnsigned(modulo, 10);
      return (int) digit;
    }
  }

  private DigitSequenceEncoder() {}
}
