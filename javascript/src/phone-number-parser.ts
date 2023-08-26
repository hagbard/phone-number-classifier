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
import { FormatType } from "./phone-number-formatter.js";

/**
 * Encapsulates a parsed phone number and an associated match result. This is returned for strict
 * parsing and the match result can be used to provide user feedback for phone number entry.
 */
export class PhoneNumberResult {
  constructor(
      private readonly phoneNumber: PhoneNumber,
      private readonly result: MatchResult,
      private readonly formatType: FormatType) {}

  /** Returns the parsed phone number (which need not be valid). */
  getPhoneNumber(): PhoneNumber {
    return this.phoneNumber;
  }

  /**
   * Returns the match result for the phone number, according to the parser's metadata. If the
   * result is `MatchResult.Matched`, then parsing was completely successful and unambiguous.
   */
  getMatchResult(): MatchResult {
    return this.result;
  }

  /**
   * Returns the format assumed by the parser. This can be useful for feeding information back to
   * users.
   */
  getInferredFormat(): FormatType {
    return this.formatType;
  }
}

/**
 * Phone number parsing API, available from subclasses of `AbstractPhoneNumberClassifier` when
 * parser metadata is available.
 *
 * Important: This parser is deliberately simpler than the corresponding parser in Google's
 * Libphonenumber library. It is designed to be a robust parser for cases where the input is a
 * formatted phone number, but it will not handle all the edge cases that Libphonenumber can
 * (e.g. parsing U.S. vanity numbers such as "1-800-BIG-DUCK").
 *
 * However, when given normally formatted national/international phone number text, this parser
 * produces exactly the same, or better results than Libphonenumber for valid/supported ranges.
 *
 * If a calling code/region is supported in the metadata for the parser, then numbers are
 * validated with/without national prefixes, and the best match is chosen. Examples of input which
 * can be parsed:
 *
 * "056/090 93 19" (SK)
 * "+687 71.49.28" (NC)
 * "(8108) 6309 390 906" (RU/KZ)
 *
 * In the final example, note that the national is given without the (optional) national prefix,
 * which is also '8'. In this case Libphonenumber gets confused and mis-parses the number, whereas
 * this parser will correctly handle it (https://issuetracker.google.com/issues/295677348).
 *
 * If a calling code/region is not supported in the metadata for the parser, then only
 * internationally formatted numbers (with a leading '+' and country calling code) can be parsed,
 * and no validation is performed (the match result is always `Invalid`).
 *
 * As a special case, Argentinian national mobile numbers formatted with the mobile token '15'
 * after the area code (e.g. "0 11 15-3329-5195") will be transformed to use the international
 * mobile token prefix '9' (e.g. +54 9 11 3329-5195). This results in 11-digit mobile numbers in
 * Argentina, which is not strictly correct (the leading '9' is not part of the national number)
 * but it is the only reasonable way to preserve the distinction between mobile and fixed-line
 * numbers when the numbers are subsequently formatted again.
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
  private readonly exampleNumberMap: Map<string, PhoneNumber>;
  private readonly nationalPrefixMap: Map<string, ReadonlyArray<DigitSequence>>;
  private readonly nationalPrefixOptional: Set<string>;

  constructor(
      private readonly rawClassifier: RawClassifier,
      private readonly converter: Converter<T>) {
    this.regionCodeMap = new Map();
    this.callingCodeMap = new Map();
    this.exampleNumberMap = new Map();
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
      this.regionCodeMap.set(ccString, regions.map(r => this.converter.fromString(r)));

      let exampleNationalNumbers = parserData.getExampleNationalNumbers();
      if (exampleNationalNumbers.length > 0) {
        if (exampleNationalNumbers.length !== regions.length) {
          throw new Error(
              `Invalid example numbers (should match available regions): ${exampleNationalNumbers}`);
        }
        // This gets a bit complicated due to the existence of the "world" region 001, for which
        // multiple calling codes and multiple regions exist. To address this we store example
        // numbers keyed by both calling code and region code (in string form). Luckily it's
        // impossible for keys to overlap, so we can share the same map.
        regions.forEach((r, i) => {
          let nn = exampleNationalNumbers[i];
          // Empty example numbers are possible and must just be ignored.
          if (nn.length() > 0) {
            let example = PhoneNumber.of(cc, nn);
            if (i === 0) {
              // The "main" region is also keyed by its calling code (this is how "world" region
              // examples can be returned to the user).
              this.exampleNumberMap.set(ccString, example);
            }
            if (r !== "001") {
              // Non-world regions are keyed here.
              this.exampleNumberMap.set(r, example);
            }
          }
        });
      }

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

  /**
   * Returns an example phone number for the given CLDR region code (if available).
   *
   * It is not always possible to guarantee example numbers will exist for every metadata
   * configuration, and it is unsafe to invent example numbers at random (since they might be
   * accidentally callable, which can cause problems).
   *
   * Note: The special "world" region "001" is associated with more than one example number,
   * so it cannot be resolved by this method. Use `getExampleNumber(callingCode)` instead.
   */
  getExampleNumberForRegion(regionCode: T): PhoneNumber|null {
    let key: string = this.converter.toString(regionCode);
    return this.callingCodeMap.has(key) ? this.getExampleNumberImpl(key) : null;
  }

  /**
   * Returns an example phone number for the given calling code (if available).
   *
   * It is not always possible to guarantee example numbers will exist for every metadata
   * configuration, and it is unsafe to invent example numbers at random (since they might be
   * accidentally callable, which can cause problems).
   *
   * Note: This method will return the example number of the main region of the calling code.
   */
  getExampleNumber(callingCode: DigitSequence): PhoneNumber|null {
    let key: string = callingCode.toString();
    return this.regionCodeMap.has(key) ? this.getExampleNumberImpl(key) : null;
  }

  private getExampleNumberImpl(key: string): PhoneNumber|null {
    let ex = this.exampleNumberMap.get(key);
    return ex ? ex : null;
  }

  parseLeniently(text: string, callingCode?: DigitSequence): PhoneNumber|null {
    let result = this.parseImpl(text, callingCode);
    return result ? result.getPhoneNumber() : null;
  }

  parseLenientlyForRegion(text: string, region: T): PhoneNumber|null {
    return this.parseLeniently(text, this.toCallingCode(region));
  }

  parseStrictly(text: string, callingCode?: DigitSequence): PhoneNumberResult {
    let result = this.parseImpl(text, callingCode);
    if (!result) {
      throw new Error(`Cannot parse phone number text '${text}'`);
    }
    return result;
  }

  parseStrictlyForRegion(text: string, region: T): PhoneNumberResult {
    return this.parseStrictly(text, this.toCallingCode(region));
  }

  private toCallingCode(region: T): DigitSequence {
    let callingCode = this.getCallingCode(region);
    if (!callingCode) {
      throw new Error(`Unknown region code: ${region}`);
    }
    return callingCode;
  }

  /*
   * The algorithm tries to parse the input assuming both "national" and "international"
   * formatting of the given text.
   *    * For national format, the given calling code is used ("NAT").
   *    * For international format, the calling code is extracted from the number ("INT").
   * 1. If neither result can be obtained, parsing fails
   * 2. If only one result can be obtained, it is returned
   * 3. If the national result match is strictly better than the international one, return the
   *    national result.
   * 4. In the remaining cases we check the input ("CHK"), and return the international result if
   *    either:
   *    * The extracted calling code was the same as the given calling code: ("41 xxxx xxxx", cc=41)
   *    * If the input text is internationally formatted: e.g. ("+41 xxxx xxxx", cc=34)
   * Otherwise return the national format.
   *
   * Note: Step 4 is only reached if the international parse result is a better match than the
   * national one, and even then we might return the national result if we aren't sure the extracted
   * calling code looks trustworthy.
   *
   * National   /----------------- International Result ------------------\
   *  Result  || MATCHED | PARTIAL | EXCESS  | LENGTH  | INVALID |  N/A    |
   * =========||============================================================
   *  MATCHED || CHK [4] | NAT [3] | NAT [3] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||-=--=--=-+---------+---------+---------+---------+---------+
   *  PARTIAL || CHK [4] | CHK [4] | NAT [3] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||---------+-=--=--=-+---------+---------+---------+---------+
   *  EXCESS  || CHK [4] | CHK [4] | CHK [4] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||---------+---------+-=--=--=-+---------+---------+---------+
   *  LENGTH  || CHK [4] | CHK [4] | CHK [4] | CHK [4] | NAT [3] | NAT [2] |
   * ---------||---------+---------+---------+-=--=--=-+---------+---------+
   *  INVALID || CHK [4] | CHK [4] | CHK [4] | CHK [4] | CHK [4] | NAT [2] |
   * ---------||---------+---------+---------+---------+-=--=--=-+-=--=--=-+
   *   N/A    || INT [2] | INT [2] | INT [2] | INT [2] | INT [2] | --- [1] |
   * ---------||---------+---------+---------+---------+---------+---------+
   */
  parseImpl(text: string, callingCode?: DigitSequence): PhoneNumberResult|null {
    if (!text.match(PhoneNumberParser.AllowedChars)) {
      return null;
    }
    // Should always succeed even if result is empty.
    let digitText: string = PhoneNumberParser.removeNonDigitsAndNormalizeToAscii(text);
    if (digitText.length === 0) {
      return null;
    }
    let digits: DigitSequence = DigitSequence.parse(digitText);
    let extractedCc: DigitSequence|null = PhoneNumber.extractCallingCode(digitText);
    let nationalParseResult: PhoneNumberResult|null =
        callingCode ? this.getBestResult(callingCode, digits, FormatType.National) : null;
    if (!extractedCc) {
      // This accounts for step [1] (no results) and step [2] with only the national result.
      return nationalParseResult;
    }
    let withoutCc: DigitSequence = PhoneNumberParser.removePrefix(digits, extractedCc.length());
    let internationalParseResult: PhoneNumberResult =
        this.getBestResult(extractedCc, withoutCc, FormatType.International)
    if (!nationalParseResult) {
      // This accounts for step [2] with only the international result.
      return internationalParseResult;
    }
    if (nationalParseResult.getMatchResult() < internationalParseResult.getMatchResult()) {
      // This accounts for step [3].
      return nationalParseResult;
    }
    if (extractedCc.equals(callingCode)
        || PhoneNumberParser.looksLikeInternationalFormat(text, extractedCc)) {
      // This accounts for step [4] when the input strongly suggest international format.
      return internationalParseResult;
    }
    return nationalParseResult;
  }

  private static looksLikeInternationalFormat(text: string, cc: DigitSequence) {
    let firstDigit = text.search(/[0-9]/);
    return firstDigit > 0
        && text.at(firstDigit - 1) === '+'
        && text.indexOf('+', firstDigit) == -1
        && text.substring(firstDigit, firstDigit + cc.length()) === cc.toString();
  }

  private getBestResult(cc: DigitSequence, nn: DigitSequence, formatType: FormatType): PhoneNumberResult {
    if (cc.equals(PhoneNumberParser.CcArgentina)) {
      nn = this.maybeAdjustArgentineFixedLineNumber(cc, nn);
    }
    if (!this.rawClassifier.isSupportedCallingCode(cc)) {
      return new PhoneNumberResult(PhoneNumber.of(cc, nn), MatchResult.Invalid, formatType);
    }
    let nationalPrefixes = this.nationalPrefixMap.get(cc.toString()) ?? [];
    let bestResult: MatchResult = MatchResult.Invalid;
    // We can test the given number (without attempting to remove a national prefix) under some
    // conditions, but avoid doing so when a national prefix is required for national dialling.
    let ccStr = cc.toString();
    if (formatType === FormatType.International
        || nationalPrefixes.length === 0
        || this.nationalPrefixOptional.has(ccStr)) {
      bestResult = this.rawClassifier.match(cc, nn);
    }
    let bestNumber: DigitSequence = nn;
    if (bestResult !== MatchResult.Matched) {
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
    return new PhoneNumberResult(PhoneNumber.of(cc, bestNumber), bestResult, formatType);
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
