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
import { PhoneNumber } from "./phone-number.js";
import { MatchResult, LengthResult } from "./match-results.js";

/**
 * Phone number parsing API, available from subclasses of `AbstractPhoneNumberClassifier` when
 * parser metadata is available.
 */
export class PhoneNumberParser<T> {
  // This must include every character in any format specifier.
  // Also include Unicode variants of '-', '/', '.', '(' and ')'.
  private static readonly AllowedChars: RegExp =
      /[-+\s0-9０-９/.()\uFF0D\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uFF0F\u3000\u2060\uFF0E\uFF08\uFF09\u2768\u2769]*/;

  // When parsing Argentinian mobile numbers in national format, the 15 mobile token
  // must be switched for the leading '9' token, which comes before the area code.
  // For example, "0_11_15_33295195" should be parsed as "9_11_33295195".
  private static readonly CcArgentina: DigitSequence = DigitSequence.parse("54");
  private static readonly ArgentinaMobilePrefix: RegExp = /0?(.{2,4})15(.{6,8})/;

  private readonly regionCodeMap: Map<string, ReadonlyArray<T>>;
  private readonly callingCodeMap: Map<string, DigitSequence>;
  private readonly nationalPrefixMap: Map<string, ReadonlyArray<DigitSequence>>;
  private readonly nationalPrefixOptional: Set<string>;

  constructor(
      private readonly rawClassifier: RawClassifier,
      private readonly converter: Converter<T>) {
    this.regionCodeMap = new Map();
    this.callingCodeMap = new Map();
    this.nationalPrefixMap = new Map();
    this.nationalPrefixOptional = new Set();
    for (let ccStr of rawClassifier.getSupportedCallingCodes()) {
      let cc = DigitSequence.parse(ccStr);
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

  parseLeniently(text: string, region?: T): PhoneNumber|null {
    let result = this.parseImpl(text, region);
    return result ? result.getPhoneNumber() : null;
  }

  parseStrictly(text: string, region?: T): PhoneNumberResult {
    let result = this.parseImpl(text, region);
    if (!result) {
      throw new Error(
          `Cannot parse phone number text '${text}'${region ? " in region " + region : ""}`);
    }
    return result;
  }

  parseImpl(text: string, region?: T): PhoneNumberResult|null {
    if (!text.match(PhoneNumberParser.AllowedChars)) {
      return null;
    }
    // Should always succeed even if result is empty.
    let digitText: string = PhoneNumberParser.removeNonDigitsAndNormalizeToAscii(text);
    if (digitText.length === 0) {
      return null;
    }
    // Heuristic to look for things that are more likely to be attempts are writing an E.164 number.
    // This is true for things like "+1234", "(+12) 34" but NOT "+ 12 34", "++1234" or "+1234+"
    // If true, we try to extract a calling code from the number *before* using the given region.
    let plusIndex = text.indexOf('+');
    let looksLikeE164 =
        plusIndex >= 0
            && text.search(/[0-9０-９]/) === plusIndex + 1
            && plusIndex === text.lastIndexOf('+');

    let originalNumber = DigitSequence.parse(digitText);
    // Null if the region is not supported.
    let providedCc = region ? this.getCallingCode(region) : null;
    // We can extract all possible calling codes regardless of whether they are supported, but we
    // only want to attempt to classify supported calling codes.
    let extractedCc = PhoneNumber.extractCallingCode(digitText);
    let extractedCcIsSupported =
        extractedCc && this.rawClassifier.isSupportedCallingCode(extractedCc);
    let bestResult: PhoneNumberResult|null = null;
    if (providedCc && !looksLikeE164) {
      bestResult = this.getBestParseResult(providedCc, originalNumber);
      if (bestResult.getResult() !== MatchResult.Matched && extractedCcIsSupported) {
        let result = this.getBestParseResult(
            extractedCc!, PhoneNumberParser.removePrefix(originalNumber, extractedCc!.length()));
        bestResult = PhoneNumberParser.bestOf(bestResult, result);
      }
    } else if (extractedCcIsSupported) {
      bestResult = this.getBestParseResult(
          extractedCc!, PhoneNumberParser.removePrefix(originalNumber, extractedCc!.length()));
      if (bestResult.getResult() !== MatchResult.Matched && providedCc) {
        let result = this.getBestParseResult(providedCc, originalNumber);
        bestResult = PhoneNumberParser.bestOf(bestResult, result);
      }
    }
    // Fallback for cases where the calling code isn't supported in the metadata, but we can
    // still make a best guess at an E164 number without any validation.
    if (!bestResult && looksLikeE164 && extractedCc) {
      bestResult = new PhoneNumberResult(PhoneNumber.fromE164(digitText), MatchResult.Invalid);
    }
    return bestResult;
  }

  private getBestParseResult(cc: DigitSequence, nn: DigitSequence): PhoneNumberResult {
    if (cc.equals(PhoneNumberParser.CcArgentina)) {
      nn = this.maybeAdjustArgentineFixedLineNumber(cc, nn);
    }
    let bestNumber: DigitSequence = nn;
    let bestResult: MatchResult = this.rawClassifier.match(cc, nn);
    if (bestResult !== MatchResult.Matched) {
      let nationalPrefixes = this.nationalPrefixMap.get(cc.toString()) ?? [];
      for (let np of nationalPrefixes) {
        if (PhoneNumberParser.startsWith(np, nn)) {
          let candidateNumber = PhoneNumberParser.removePrefix(nn, np.length());
          let candidateResult = this.rawClassifier.match(cc, candidateNumber);
          if (candidateResult < bestResult) {
            bestNumber = candidateNumber;
            bestResult = candidateResult;
            if (bestResult === MatchResult.Matched) {
              break;
            }
          }
        }
      }
    }
    return new PhoneNumberResult(PhoneNumber.of(cc, bestNumber), bestResult);
  }

  private maybeAdjustArgentineFixedLineNumber(cc: DigitSequence, nn: DigitSequence): DigitSequence {
    if (this.rawClassifier.testLength(cc, nn) === LengthResult.TooLong) {
      let nnStr = nn.toString();
      let candidate = nnStr.replace(PhoneNumberParser.ArgentinaMobilePrefix, "9$1$2");
      if (candidate !== nnStr) {
        return DigitSequence.parse(candidate);
      }
    }
    return nn;
  }

  private static bestOf(a: PhoneNumberResult, b: PhoneNumberResult): PhoneNumberResult {
    return a.getResult() < b.getResult() ? a : b;
  }

  private static startsWith(prefix: DigitSequence, seq: DigitSequence): boolean {
    return prefix.length() <= seq.length() && seq.getPrefix(prefix.length()).equals(prefix);
  }

  private static removePrefix(seq: DigitSequence, length: number): DigitSequence {
    return seq.getSuffix(seq.length() - length);
  }

  private static removeNonDigitsAndNormalizeToAscii(s: string): string {
    if (s.match(/^[0-9]*$/)) {
      return s;
    }
    // Remove anything that's not a digit (ASCII or full width), leaving only 16-bit chars.
    // Then normalize digits to decimal values and reformat to a string.
    return [...s.replace(/[^0-9０-９]+/g, "")]
        .map(c => c.charCodeAt(0))
        // 0x30 = '0', 0xff10 = '０'
        .map(d => d >= 0xff10 ? d - 0xff10 : d - 0x30)
        .join("");
  }
}

export class PhoneNumberResult {
  constructor(private readonly phoneNumber: PhoneNumber, private readonly result: MatchResult) {}

  getPhoneNumber(): PhoneNumber {
    return this.phoneNumber;
  }

  getResult(): MatchResult {
    return this.result;
  }
}
