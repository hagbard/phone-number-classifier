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

@RunWith(JUnit4.class)
public class DigitSequenceEncoderTest {
  @Test
  public void testRoundTrip() {
    assertEncoded("", 0L);
    assertEncoded("0", 1);
    assertEncoded("1", 2);
    assertEncoded("000", 111);
    assertEncoded("123", 234);
    assertEncoded("999", 1110);
    assertEncoded("0000000000000000000", 1111111111111111111L);
    assertEncoded("9999999999999999999", Long.parseUnsignedLong("11111111111111111110"));

    // Can't go up to 20 digits.
    assertThrows(
        IllegalArgumentException.class, () -> DigitSequenceEncoder.encode("10000000000000000000"));
  }

  static void assertEncoded(String digits, long encoded) {
    assertThat(DigitSequenceEncoder.encode(digits)).isEqualTo(encoded);
    assertThat(asString(DigitSequenceEncoder.iterate(encoded))).isEqualTo(digits);
    int length = DigitSequenceEncoder.getLength(encoded);
    assertThat(length).isEqualTo(digits.length());
    for (int n = 0; n < length; n++) {
      assertThat(DigitSequenceEncoder.getDigit(n, encoded)).isEqualTo(digits.charAt(n) - '0');
    }
  }

  @Test
  public void testAppend() {
    assertAppend("", "");
    assertAppend("", "123");
    assertAppend("123", "");
    assertAppend("000", "000");
    assertAppend("123", "456");
    assertAppend("0123456789", "987654321");
    assertAppend("9999999999", "999999999");
    assertAppend("1234567890987654321", "");
    assertAppend("", "1234567890987654321");

    long tenDigits = DigitSequenceEncoder.encode("1234567890");
    assertThrows(
        IllegalArgumentException.class, () -> DigitSequenceEncoder.append(tenDigits, tenDigits));
  }

  // Relies on encode() working as expected.
  static void assertAppend(String prefix, String suffix) {
    String expectedAppended = prefix + suffix;

    long prefixEncoded = DigitSequenceEncoder.encode(prefix);
    long suffixEncoded = DigitSequenceEncoder.encode(suffix);
    long appended = DigitSequenceEncoder.append(prefixEncoded, suffixEncoded);

    assertEncoded(expectedAppended, appended);
  }

  @Test
  public void testTrim() {
    assertSplit("", 0);
    assertSplit("123456", 0);
    assertSplit("123456", 3);
    assertSplit("123456", 6);
    assertSplit("1234567890987654321", 0);
    assertSplit("1234567890987654321", 10);
    assertSplit("1234567890987654321", 19);
  }

  // Relies on encode() working as expected.
  static void assertSplit(String digits, int index) {
    String expectedPrefix = digits.substring(0, index);
    String expectedSuffix = digits.substring(index);

    long encoded = DigitSequenceEncoder.encode(digits);
    long prefix = DigitSequenceEncoder.split(encoded, index, true);
    long suffix = DigitSequenceEncoder.split(encoded, digits.length() - index, false);

    assertEncoded(expectedPrefix, prefix);
    assertEncoded(expectedSuffix, suffix);
    assertThat(DigitSequenceEncoder.append(prefix, suffix)).isEqualTo(encoded);
  }

  static String asString(DigitSequence.Digits seq) {
    StringBuilder decimal = new StringBuilder();
    while (seq.hasNext()) {
      decimal.append((char) ('0' + seq.next()));
    }
    return decimal.toString();
  }
}
