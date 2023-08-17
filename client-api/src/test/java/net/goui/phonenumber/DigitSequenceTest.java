/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

  This program and the accompanying materials are made available under the terms of the
  Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
  Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Most of the core functionality is tested via {@link DigitSequenceEncoderTest}. */
@RunWith(JUnit4.class)
public class DigitSequenceTest {
  @Test
  public void testParse() {
    assertDigits(DigitSequence.parse(""));
    assertDigits(DigitSequence.parse("0"), 0);
    assertDigits(DigitSequence.parse("0000"), 0, 0, 0, 0);
    assertDigits(DigitSequence.parse("1234"), 1, 2, 3, 4);
    assertDigits(DigitSequence.parse("123456789"), 1, 2, 3, 4, 5, 6, 7, 8, 9);
  }

  private void assertDigits(DigitSequence seq, int... digits) {
    assertThat(seq.length()).isEqualTo(digits.length);
    for (int i = 0; i < seq.length(); i++) {
      assertThat(seq.getDigit(i)).isEqualTo(digits[i]);
    }
  }

  @Test
  public void testParseErrors() {
    assertThrows(NullPointerException.class, () -> DigitSequence.parse(null));
    assertThrows(IllegalArgumentException.class, () -> DigitSequence.parse("x"));
    assertThrows(IllegalArgumentException.class, () -> DigitSequence.parse("01234x567"));
    // 20 digits (too long).
    assertThrows(IllegalArgumentException.class, () -> DigitSequence.parse("01234567890123456789"));
  }

  @Test
  public void testToString() {
    assertThat(DigitSequence.parse("").toString()).isEqualTo("");
    assertThat(DigitSequence.parse("012345").toString()).isEqualTo("012345");
  }
}
