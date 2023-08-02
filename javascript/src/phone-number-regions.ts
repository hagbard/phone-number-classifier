/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { PhoneNumber } from "./phone-number.js";
import { DigitSequence, Digits } from "./digit-sequence.js";
import { RawClassifier, ValueMatcher, ReturnType } from "./raw-classifier.js";
import { MatchResult, LengthResult } from "./match-results.js";

/**
 * Provides a format function for phone numbers based on a specific format type.
 *
 * When a metadata schema supports formatter metadata, an instance of this class can be returned to
 * the user from a subclass of `AbstractPhoneNumberClassifier`. Instances of this class cannot be
 * created for classifiers which do not have the required metadata.
 *
 * This class is deliberately lightweight and avoids holding pre-computed format data so that it can
 * be instantiated on demand (if needed). All formatting information is encoded into the format
 * specifier strings obtained from the raw classifier.
 */
export class PhoneNumberRegions {
  private readonly regionCodeMap: Map<string, ReadonlyArray<string>>;
  private readonly callingCodeMap: Map<string, DigitSequence>;

  constructor(rawClassifier: RawClassifier) {
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
      this.regionCodeMap.set(cc.toString(), regionCodes);
      for (let r of regionCodes) {
        this.callingCodeMap.set(r, cc);
      }
    }
  }

  getRegions(callingCode: DigitSequence): ReadonlyArray<string> {
    let regions = this.regionCodeMap.get(callingCode.toString());
    if (regions) {
      return regions;
    }
    throw new Error(`Unsupported calling code: ${callingCode}`);
  }

  getCallingCode(regionCode: string): DigitSequence {
    let cc = this.callingCodeMap.get(regionCode);
    if (cc) {
      return cc;
    }
    throw new Error(`Unsupported region code: ${regionCode}`);
  }
}