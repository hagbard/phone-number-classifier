/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

/**
 * Result values for length tests.
 *
 * <p>Length tests are a very weak signal for validity, and serve only as a quick rejection test.
 */
public enum LengthResult {
  /**
   * The given number's length was in the set of possible number lengths. This implies very little
   * about whether the number is valid.
   */
  POSSIBLE,
  /** The given number was shorter than any possible valid number. */
  TOO_SHORT,
  /** The given number was longer than any possible valid number. */
  TOO_LONG,
  /**
   * The given number's length was between the shortest and longest possible number, but was not
   * itself possible.
   */
  INVALID_LENGTH
}
