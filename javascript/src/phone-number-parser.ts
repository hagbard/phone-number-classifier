/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { DigitSequence } from "./digit-sequence.js";
import { RawClassifier, ParserData } from "./raw-classifier.js";
import { Converter } from "./converter.js";

/**
 * Provides information about the mapping between country calling codes and CLDR region codes.
 *
 * This functionality is only available if the REGION classifier was included in the underlying
 * metadata.
 */
export class PhoneNumberParser<T> {
  private readonly regionCodeMap: Map<string, ReadonlyArray<T>>;
  private readonly callingCodeMap: Map<string, DigitSequence>;
  private readonly nationalPrefixMap: Map<string, ReadonlyArray<DigitSequence>>;
  private readonly nationalPrefixOptional: Set<string>;

  constructor(rawClassifier: RawClassifier, private readonly converter: Converter<T>) {
    this.regionCodeMap = new Map();
    this.callingCodeMap = new Map();
    this.nationalPrefixMap = new Map();
    this.nationalPrefixOptional = new Set();
    for (let cc of rawClassifier.getSupportedCallingCodes()) {
      let parserData: ParserData|null = rawClassifier.getParserData(cc);
      if (!parserData) {
        throw new Error(`Parser data unavailable for: ${cc}`);
      }
      let regions = parserData.getRegions();
      // Region 001 is treated specially since it's the only region with more than one calling code,
      // so it cannot be put into the calling code map. It cannot also appear with other regions.
      if (!regions.includes("001")) {
        for (let r of regions) {
          this.callingCodeMap.set(r, cc);
        }
      } else if (regions.length > 1) {
        throw new Error(`Region 001 must never appear with other region codes: ${regions}`);
      }
      let ccString = cc.toString();
      this.regionCodeMap.set(ccString, regions.map(s => this.converter.fromString(s)));

      if (parserData.getNationalPrefixes().length > 0) {
        this.nationalPrefixMap.set(ccString, parserData.getNationalPrefixes());
        if (parserData.isNationalPrefixOptional()) {
          this.nationalPrefixOptional.add(ccString);
        }
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

  getNationalPrefixes(callingCode: DigitSequence): ReadonlyArray<DigitSequence> {
    let np = this.nationalPrefixMap.get(callingCode.toString());
    return np ? np : [];
  }
}