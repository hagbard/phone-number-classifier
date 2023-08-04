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
      let regions: string[] =
          [...rawClassifier.getValueMatcher(cc, "REGION").getPossibleValues()];
      // Note: Since region data is associated with ranges, and ranges can be restricted by
      // configuration, it's possible to get a calling codes in which some or all of the original
      // region code are missing. This is fine for classification, but this API promises to have
      // the "main" region present in the list, so we make sure (if it's missing) to add it here.
      if (!regions.includes(mainRegion)) {
        regions.push(mainRegion);
      }
      // Region 001 is treated specially since it's the only region with more than one calling code
      // so it cannot be put into the calling code map. It's also not expected to ever appear with
      // any other region codes in the data.
      let hasWorldRegion: boolean = regions.includes("001");
      // We only need to do any work if there's more than one region for this calling code.
      if (regions.length > 1) {
        if (hasWorldRegion) {
          throw new Error(`Region 001 must never appear with other region codes: ${regions}`);
        }
        // Sort regions, and then move the main region to the front (if not already there).
        regions.sort();
        let idx = regions.indexOf(mainRegion);
        if (idx > 0) {
          regions.splice(idx, 1);
          regions.unshift(mainRegion);
        }
      }
      // At this point, if (hasWorldRegion == true) it's the only region,
      // so we aren't dropping any other regions here.
      if (!hasWorldRegion) {
        for (let r of regions) {
          this.callingCodeMap.set(r, cc);
        }
      }
      this.regionCodeMap.set(cc.toString(), regions.map(s => this.converter.fromString(s)));
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