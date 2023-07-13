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

@RunWith(JUnit4.class)
public class PhoneNumbersTest {
  @Test
  public void testParse() {
    PhoneNumber number = PhoneNumbers.fromE164("+44123456789");
    assertThat(number.length()).isEqualTo(11);
    String s = number.toString();
    assertThat(s).isEqualTo("+44123456789");
    DigitSequence digits = number.getDigits();
    for (int n = 0; n < number.length(); n++) {
      assertThat(digits.getDigit(n)).isEqualTo(s.charAt(n + 1) - '0');
    }
    assertThat(number).isEqualTo(PhoneNumbers.fromE164("+44123456789"));
    assertThat(number).isNotEqualTo(PhoneNumbers.fromE164("+44123456999"));
  }
}
