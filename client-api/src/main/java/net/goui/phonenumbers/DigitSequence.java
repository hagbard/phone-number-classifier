/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;

/**
 * Efficient encapsulation of an arbitrary, immutable digit sequence (e.g. "1234", "007" etc.) of up
 * to 19 digits.
 */
@AutoValue
public abstract class DigitSequence implements Comparable<DigitSequence> {
  /** Iterator API for returning digits (values in the range [0..9]) of a sequence. */
  public interface Digits {
    /** Returns whether this iterator has any more digits. */
    boolean hasNext();
    /** Returns the next digit (a value in the range [0..9]) from this sequence. */
    int next();
  }

  private static final DigitSequence EMPTY = new AutoValue_DigitSequence(0);

  /** Returns a digit sequence from the special encoded representation. */
  static DigitSequence ofEncoded(long encoded) {
    return encoded != 0 ? new AutoValue_DigitSequence(encoded) : EMPTY;
  }

  /** Returns a digit sequence for a string of ASCII digits (e.g. "1234", "007", etc.). */
  public static DigitSequence parse(String digits) {
    return ofEncoded(DigitSequenceEncoder.encode(digits));
  }

  abstract long encoded();

  public final Digits iterate() {
    return DigitSequenceEncoder.iterate(encoded());
  }

  /** Returns the Nth digit (a value in the range [0..9]) from this sequence. */
  public final int getDigit(int n) {
    return DigitSequenceEncoder.getDigit(n, encoded());
  }

  /** Returns the number of digits in this sequence. */
  public final int length() {
    return DigitSequenceEncoder.getLength(encoded());
  }

  /** Returns whether this is the empty sequence (no digits at all). */
  public final boolean isEmpty() {
    return encoded() == 0;
  }

  /**
   * Returns a new sequence which is the result appending the given suffix to this sequence.
   *
   * @throws IllegalArgumentException if the resulting sequence would be more than 19 digits.
   */
  public final DigitSequence append(DigitSequence suffix) {
    if (isEmpty()) {
      return suffix;
    } else if (suffix.isEmpty()) {
      return this;
    } else {
      return ofEncoded(DigitSequenceEncoder.append(encoded(), suffix.encoded()));
    }
  }

  private DigitSequence getPrefixOrSuffix(int length, boolean prefix) {
    switch (DigitSequenceEncoder.compareLengthOf(encoded(), length)) {
      case 0:
        return this;
      case 1:
        return ofEncoded(DigitSequenceEncoder.split(encoded(), length, prefix));
      default:
        throw new IllegalArgumentException(
            "length (" + length + ") must not exceed sequence length: " + this);
    }
  }

  /**
   * Returns the prefix of the given {@code length} from this sequence.
   *
   * @throws IllegalArgumentException if {@code length} is longer than the sequence length.
   */
  public final DigitSequence getPrefix(int length) {
    return getPrefixOrSuffix(length, true);
  }

  /**
   * Returns the suffix of the given {@code length} from this sequence.
   *
   * @throws IllegalArgumentException if {@code length} is longer than the sequence length.
   */
  public final DigitSequence getSuffix(int length) {
    return getPrefixOrSuffix(length, false);
  }

  /** Compares digit sequences in lexical order (e.g. "0" < "1" < ... < "9" < "00" ...). */
  @Override
  public int compareTo(DigitSequence other) {
    return Long.compareUnsigned(encoded(), other.encoded());
  }

  @Memoized
  @Override
  public String toString() {
    return DigitSequenceEncoder.toString(encoded());
  }
}
