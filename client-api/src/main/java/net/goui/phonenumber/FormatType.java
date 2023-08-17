/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

/**
 * The possible types of format used by the phone number library. This is deliberately more limited
 * than the types available in Libphonenumber, since that library must handle all edge cases, while
 * this library is specifically limited to the commonest use cases.
 */
public enum FormatType {
  /**
   * A format in which the country calling code is not explicitly present in the phone number text;
   * for example {@code 020 8743 8000}.
   *
   * <p>A nationally formatted number can only be parsed if the correct country calling code is
   * used.
   */
  NATIONAL("NATIONAL_FORMAT"),

  /**
   * A format in which the country calling code is explicitly present in the phone number text; for
   * example {@code "+44 20 8743 8000"} or an E.164 formatted number.
   *
   * <p>An internationally formatted number can be parsed without a country calling code, and even
   * when a different calling code was used (depending on the validity of the number).
   */
  INTERNATIONAL("INTERNATIONAL_FORMAT");

  final String id;

  FormatType(String id) {
    this.id = id;
  }
}
