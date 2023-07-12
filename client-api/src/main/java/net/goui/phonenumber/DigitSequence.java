/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;

@AutoValue
public abstract class DigitSequence implements Comparable<DigitSequence> {

  public interface Digits {
    boolean hasNext();

    int next();
  }

  private static final DigitSequence EMPTY = new AutoValue_DigitSequence(0);

  static DigitSequence ofEncoded(long encoded) {
    return encoded != 0 ? new AutoValue_DigitSequence(encoded) : EMPTY;
  }

  public static DigitSequence parse(String digits) {
    return ofEncoded(DigitSequenceEncoder.encode(digits));
  }

  abstract long encoded();

  public final Digits iterate() {
    return DigitSequenceEncoder.iterate(encoded());
  }

  public final int getDigit(int n) {
    return DigitSequenceEncoder.getDigit(n, encoded());
  }

  public final int length() {
    return DigitSequenceEncoder.getLength(encoded());
  }

  public final boolean isEmpty() {
    return encoded() == 0;
  }

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

  public final DigitSequence getPrefix(int length) {
    return getPrefixOrSuffix(length, true);
  }

  public final DigitSequence getSuffix(int length) {
    return getPrefixOrSuffix(length, false);
  }

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
