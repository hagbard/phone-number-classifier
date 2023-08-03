/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { DigitSequence } from "./digit-sequence.js";
import { RawClassifier } from "./raw-classifier.js";
import { Converter } from "./converter.js";

/**
 * Provides information about the mapping between country calling codes and CLDR region codes.
 *
 * This functionality is only available if the REGION classifier was included in the underlying
 * metadata.
 */
export class PhoneNumberRegions<T> {
  private readonly regionCodeMap: Map<string, ReadonlyArray<T>>;
  private readonly callingCodeMap: Map<string, DigitSequence>;

  constructor(rawClassifier: RawClassifier, private readonly converter: Converter<T>) {
    converter.ensureValues(rawClassifier.getPossibleValues("REGION"));
    this.regionCodeMap = new Map();
    this.callingCodeMap = new Map();
    for (let cc of rawClassifier.getSupportedCallingCodes()) {
      let mainRegion = rawClassifier.getMainRegion(cc);
      let regionCodes: string[] =
          rawClassifier.getValueMatcher(cc, "REGION").getPossibleValues().sort();
      let idx = regionCodes.indexOf(mainRegion);
      if (idx === -1) {
        throw new Error(`Error in region data; ${mainRegion} should be in: ${regionCodes}`);
      }
      regionCodes.splice(idx, 1);
      regionCodes.unshift(mainRegion);
      this.regionCodeMap.set(cc.toString(), regionCodes.map(s => this.converter.fromString(s)));
      for (let r of regionCodes) {
        this.callingCodeMap.set(r, cc);
      }
    }
  }

  /**
   * Returns a sorted list of the CLDR region codes for the given country calling code.
   *
   * This list is sorted alphabetically, except the first element, which is always the "main
   * region" associated with the calling code (e.g. "US" for "1", "GB" for "44").
   *
   * If the given calling code is not supported by the underlying metadata, the empty array is
   * returned.
   */
  getRegions(callingCode: DigitSequence): ReadonlyArray<T> {
    let regions = this.regionCodeMap.get(callingCode.toString());
    return regions ? regions : [];
  }

  /**
   * Returns the country calling code for the specified CLDR region code.
   *
   * If the given region code is not supported by the underlying metadata, null is returned.
   */
  getCallingCode(regionCode: T): DigitSequence|null {
    let cc = this.callingCodeMap.get(this.converter.toString(regionCode));
    return cc ? cc : null;
  }
}