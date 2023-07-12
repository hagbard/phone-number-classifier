/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

/** Designed to accommodate expected features for "inline classes" and "records". */
public interface PhoneNumber {
  /** Returns an efficient representation of the digits in this phone number. */
  DigitSequence getDigits();

  /** Returns the Nth digit value (in the range {@code {0..9}}) in this phone number. */
  int getDigit(int n);

  /** Returns the number of digits in this phone number. */
  int length();

  /**
   * Returns the canonical, unformatted, E.164 representation of this phone number, prefixed with a
   * leading ASCII {@code '+'}.
   */
  String toString();
}
