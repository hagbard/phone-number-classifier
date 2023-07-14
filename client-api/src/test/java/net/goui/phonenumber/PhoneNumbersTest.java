/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Most of the core functionality is tested via {@link DigitSequenceTest}. */
@RunWith(JUnit4.class)
public class PhoneNumbersTest {
  @Test
  public void testFromE164() {
    PhoneNumber number = PhoneNumbers.fromE164("+44123456789");
    assertThat(number.length()).isEqualTo(11);

    // Test contents of toString().
    String s = number.toString();
    assertThat(s).isEqualTo("+44123456789");
    DigitSequence digits = number.getDigits();
    for (int n = 0; n < number.length(); n++) {
      assertThat(digits.getDigit(n)).isEqualTo(s.charAt(n + 1) - '0');
    }
  }

  @Test
  public void testCallingCodeAndNationalNumber() {
    PhoneNumber oneDigitCc = PhoneNumbers.fromE164("+15515817989");
    assertThat(oneDigitCc.getCallingCode()).isEqualTo(DigitSequence.parse("1"));
    assertThat(oneDigitCc.getNationalNumber()).isEqualTo(DigitSequence.parse("5515817989"));

    PhoneNumber twoDigitCc = PhoneNumbers.fromE164("+447471920002");
    assertThat(twoDigitCc.getCallingCode()).isEqualTo(DigitSequence.parse("44"));
    assertThat(twoDigitCc.getNationalNumber()).isEqualTo(DigitSequence.parse("7471920002"));

    PhoneNumber threeDigitCc = PhoneNumbers.fromE164("+962707952933");
    assertThat(threeDigitCc.getCallingCode()).isEqualTo(DigitSequence.parse("962"));
    assertThat(threeDigitCc.getNationalNumber()).isEqualTo(DigitSequence.parse("707952933"));
  }

  @Test
  public void testEquality() {
    PhoneNumber number = PhoneNumbers.fromE164("+447471920002");
    // Parse from same input is equal.
    assertThat(number).isEqualTo(PhoneNumbers.fromE164("+447471920002"));
    assertThat(number).isNotEqualTo(PhoneNumbers.fromE164("+447471929999"));
  }
}
