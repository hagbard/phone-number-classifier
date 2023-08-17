/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
  Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

  This program and the accompanying materials are made available under the terms of the
  Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
  Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

  SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.metadata;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import net.goui.phonenumber.DigitSequence;

/** Simple value type for holding all data required for phone number parsing. */
@AutoValue
public abstract class ParserData {
  /** Creates parser data for an associated calling code. */
  public static ParserData create(
      ImmutableSet<String> regions,
      ImmutableSet<DigitSequence> nationalPrefixes,
      boolean isNationalPrefixOptional) {
    return new AutoValue_ParserData(regions, nationalPrefixes, isNationalPrefixOptional);
  }

  /**
   * Returns an ordered, non-empty list of CLDR region codes for the associated calling code. The
   * first element is the "main" region, and following regions are ordered alphabetically.
   */
  public abstract ImmutableSet<String> getRegions();

  /**
   * Returns an ordered, possibly empty, list of national prefixes. The first element is the
   * preferred national prefix for formatting.
   */
  public abstract ImmutableSet<DigitSequence> getNationalPrefixes();

  /**
   * Whether the national prefix for numbers in the associated calling code is optional. If the
   * calling code does not have national prefixes, this returns false.
   */
  public abstract boolean isNationalPrefixOptional();
}
