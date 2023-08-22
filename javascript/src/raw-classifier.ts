/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { DigitSequence, Digits } from "./digit-sequence.js";
import { DigitSequenceMatcher } from "./digit-sequence-matcher.js";
import { MatchResult, LengthResult } from "./match-results.js";
import {
    MetadataJson,
    VersionJson,
    CallingCodeJson,
    NationalNumberDataJson,
    MatcherDataJson,
    ParserDataJson } from "./metadata-json.js";
import { Buffer } from 'buffer';

export class SchemaVersion {
  constructor(readonly uri: string, readonly ver: number) {}
}

export enum ReturnType {
    /** Phone numbers are associated with a single value (e.g. the phone number type). */
    SingleValued,
    /** Phone numbers can be associated with multiple values (e.g. region codes). */
    MultiValued,
    /** Indicates a serious error in library configuration. */
    Unknown,
}

export interface ValueMatcher {
  matchValues(nationalNumber: DigitSequence, ...values: string[]): MatchResult;
  getPossibleValues(): ReadonlyArray<string>;
}

export class RawClassifier {
  // Data version which must be updated in client code when JSON structure changes.
  // Major (semantic) version number; if different, implies incompatible versions.
  private static readonly MAJOR_DATA_VERSION: number = 1;
  // Minor version number. A classifier can only use data with a minor version that's
  // greater than, or equal to, the one it expects.
  private static readonly MINOR_DATA_VERSION: number = 0;

  public static create(
      jsonString: string, schema: SchemaVersion, ...rest: SchemaVersion[]): RawClassifier {
    let json: MetadataJson = JSON.parse(jsonString) as MetadataJson;

    let verStr = JSON.stringify(json.ver);
    if (!RawClassifier.dataVersionIsCompatible(json.ver)) {
      throw new Error(
          `Metadata (version=${verStr}) has incompatible data version`);
    }
    let schemas: SchemaVersion[] = [schema, ...rest];
    if (!schemas.some(s => RawClassifier.versionSatisfiesSchema(json.ver, s))) {
      let schStr = schemas.map(s => JSON.stringify(s)).join("\n  version=");
      throw new Error(
          `Metadata (version=${verStr}) does not satisfy any allowed schema:\n  version=${schStr}`);
    }

    let version: VersionJson = json.ver;
    // Token decoding function.
    let decode: (i: number) => string = (i) => json.tok[i];
    let callingCodes: ReadonlySet<string> = new Set(json.ccd.map(ccd => ccd.c.toString()));
    let typeList = json.typ !== undefined ? json.typ : [];
    let types: Map<string, number> = new Map(typeList.map((t, i) => [decode(t), i]));
    let singleValuedTypeMask: number = json.svm !== undefined ? json.svm : 0;
    let classifierOnlyTypeMask: number = json.com !== undefined ? json.com : 0;
    let classifiers: Map<string, CallingCodeClassifier> =
        new Map(json.ccd.map(ccd => [ccd.c.toString(), CallingCodeClassifier.create(ccd, decode)]));
    let possibleValues: Map<string, Set<string>> = new Map();
    Array.from(classifiers.values()).forEach(
        ccd => types.forEach(
            (index, type) => ccd.getTypeClassifier(index).getPossibleValues().forEach(
                value => RawClassifier.addToSet(possibleValues, type, value))));
    return new RawClassifier(
        callingCodes, types, singleValuedTypeMask, classifierOnlyTypeMask, classifiers, possibleValues);
  }

  private static addToSet(map: Map<string, Set<string>>, k: string, v: string) {
    let s = map.get(k);
    if (s === undefined) {
      s = new Set();
      map.set(k, s);
    }
    s.add(v);
  }

  private static dataVersionIsCompatible(version: VersionJson) {
    return version.maj === RawClassifier.MAJOR_DATA_VERSION
        && version.min >= RawClassifier.MINOR_DATA_VERSION;
  }

  private static versionSatisfiesSchema(version: VersionJson, schema: SchemaVersion) {
    return version.uri === schema.uri && version.ver >= schema.ver;
  }

  constructor(
      private readonly callingCodes: ReadonlySet<string>,
      private readonly types: Map<string, number>,
      private readonly singleValuedTypeMask: number,
      private readonly classifierOnlyTypeMask: number,
      private readonly classifiers: Map<string, CallingCodeClassifier>,
      private readonly possibleValues: Map<string, Set<string>>) { }

  /**
   * The country calling codes supported by the metadata schema. Different metadata schemas can make
   * different promises about which calling codes are supported, and without knowledge of the schema
   * being used, there are no guarantees about what is in this set.
   *
   * Note: In order to make the returned value actually useful for checking if a calling code is
   * supported, we have to return a `Set<string>` and not a `Set<DigitSequence>`. JavaScript sucks.
   */
  getSupportedCallingCodes(): ReadonlySet<string> {
    return this.callingCodes;
  }

  /**
   * Returns whether the given calling code is supported by this classifier.
   */
  isSupportedCallingCode(callingCode: DigitSequence): boolean {
    return this.callingCodes.has(callingCode.toString());
  }

  /**
   * The names of the number types supported by the metadata schema. Types are either the "built in"
   * basic types (e.g. "TYPE", "TARIFF", "REGION") or custom type names of the form "PREFIX:TYPE".
   *
   * Users should never need to know about this information since it is expected that they will use
   * a type-safe API derived from `AbstractPhoneNumberClassifier`, where there's no need to
   * enumerate the available types.
   */
  getSupportedNumberTypes(): ReadonlySet<string> {
    return new Set(this.types.keys());
  }

  /**
   * Returns the parser data for the given calling code. This is either present for all calling
   * codes, or omitted for all calling codes. The calling code should be implicitly aware (based
   * on the metadata schema) as to whether this will return a value.
   */
  getParserData(callingCode: DigitSequence): ParserData|null {
    return this.getCallingCodeClassifier(callingCode).getParserData();
  }

  /**
   * Returns an example number for the given calling code. The returned number will be valid, but
   * need not be of any specific number type (though common number types are likely). The returned
   * number is deterministic, and should not be callable. This value is omitted in the unlikely
   * event that no suitable example phone number was available (e.g. due to the classifier having
   * a restricted validation range).
   */
  getExampleNationalNumber(callingCode: DigitSequence): DigitSequence|null {
    return this.getCallingCodeClassifier(callingCode).getExampleNationalNumber();
  }

  /**
   * Returns whether a phone number could be valid according only to its length.
   *
   * This is a fast test, but it can produce significant false positive results (e.g. suggesting
   * that invalid numbers are possible). It should only be used to reject impossible numbers before
   * further checking is performed.
   */
  testLength(callingCode: DigitSequence, nationalNumber: DigitSequence): LengthResult {
    return this.getCallingCodeClassifier(callingCode).testLength(nationalNumber);
  }

  /** Matches a phone number against all valid ranges of a country calling code. */
  match(callingCode: DigitSequence, nationalNumber: DigitSequence): MatchResult {
    return this.getCallingCodeClassifier(callingCode).match(nationalNumber);
  }

  /**
   * Returns whether this classifier is single- or multi- valued. This is used to ensure that the
   * APIs available to users are correct for the underlying metadata, but users should never need
   * to call this method directly.
   */
  isSingleValued(numberType: string): boolean {
    let idx: number = this.getTypeClassifierIndex(numberType);
    return (this.singleValuedTypeMask & (1 << idx)) != 0;
  }

  /**
   * Returns whether this classifier supports matching operations on partial numbers via the
   * `ValueMatcher` interface. This is used to ensure that the APIs available to users are correct
   * for the underlying metadata, but users should never need to call this method directly.
   */
  supportsValueMatcher(numberType: string): boolean {
    let idx: number = this.getTypeClassifierIndex(numberType);
    return ((this.classifierOnlyTypeMask & (1 << idx)) == 0)
  }

  /**
   * Returns the possible values for a given number type. Not all number plans will use all these
   * types, and its even possible (due to metadata customization) that some values do not appear for
   * any country calling code. Having this set is useful to ensure that higher level, type-safe APIs
   * account for every value.
   */
  getPossibleValues(numberType: string): ReadonlySet<string> {
    let values = this.possibleValues.get(numberType);
    return ((values !== undefined) ? values : new Set<string>()) as ReadonlySet<string>;
  }

  classify(callingCode: DigitSequence, nationalNumber: DigitSequence, numberType: string): ReadonlySet<string> {
    let ccd: CallingCodeClassifier = this.getCallingCodeClassifier(callingCode);
    let idx: number = this.getTypeClassifierIndex(numberType);
    let classifier: NationalNumberClassifier = ccd.getTypeClassifier(idx);
    return ((this.singleValuedTypeMask & (1 << idx)) != 0)
        ? classifier.classifySingleValueAsSet(nationalNumber)
        : classifier.classifyMultiValue(nationalNumber);
  }

  classifyUniquely(callingCode: DigitSequence, nationalNumber: DigitSequence, numberType: string): string|null {
    let ccd: CallingCodeClassifier = this.getCallingCodeClassifier(callingCode);
    let idx: number = this.getTypeClassifierIndex(numberType);
    return ccd.getTypeClassifier(idx).classifySingleValue(nationalNumber);
  }

  getValueMatcher(callingCode: DigitSequence, numberType: string): ValueMatcher {
    let ccd: CallingCodeClassifier = this.getCallingCodeClassifier(callingCode);
    let idx: number = this.getTypeClassifierIndex(numberType);
    return ccd.getTypeClassifier(idx);
  }

  private getCallingCodeClassifier(callingCode: DigitSequence): CallingCodeClassifier {
    let ccd = this.classifiers.get(callingCode.toString());
    if (ccd !== undefined) {
      return ccd;
    }
    throw new Error(`unsupported calling code: ${callingCode}`);
  }

  private getTypeClassifierIndex(numberType: string): number {
    let idx = this.types.get(numberType);
    if (idx !== undefined) {
      return idx;
    }
    throw new Error(`unsupported number type: ${numberType}`);
  }
}

type MatcherFactory = (i?: number[] | number) => MatcherFunction

class CallingCodeClassifier {
  static create(json: CallingCodeJson, decode: (i: number) => string): CallingCodeClassifier {
    // Matchers ordered to match indices in JSON. Build instances here so they are shared
    // rather than being rebuilt each time.
    let matchers: MatcherFunction[] = json.m.map(m => MatcherFunction.create(m));
    let matcherFactory: MatcherFactory =
        idx => Array.isArray(idx)
            ? idx.length > 0 ? MatcherFunction.of(idx.map(i => matchers[i])) : matchers[0]
            : idx !== undefined ? matchers[idx as number] : matchers[0];

    let validityMatcher: MatcherFunction = matcherFactory(json.v);
    let typeClassifiers: NationalNumberClassifier[] =
        (json.n !== undefined)
            ? json.n.map(n => NationalNumberClassifier.create(n, decode, matcherFactory)) : [];
    let parserData = json.p ? ParserData.create(json.p, decode) : null;
    let exampleNumber = json.e ? DigitSequence.parse(json.e) : null;
    return new CallingCodeClassifier(
        validityMatcher, typeClassifiers, parserData, exampleNumber);
  }

  constructor(
      private readonly validityMatcher: MatcherFunction,
      private readonly typeClassifiers: NationalNumberClassifier[],
      private readonly parserData: ParserData|null,
      private readonly exampleNationalNumber: DigitSequence|null) {}

  getParserData(): ParserData|null {
    return this.parserData;
  }

  getExampleNationalNumber(): DigitSequence|null {
    return this.exampleNationalNumber;
  }

  testLength(nationalNumber: DigitSequence): LengthResult {
    return this.validityMatcher.testLength(nationalNumber);
  }

  match(nationalNumber: DigitSequence): MatchResult {
    let result: MatchResult = this.validityMatcher.match(nationalNumber);
    if (result === MatchResult.Invalid
        && this.testLength(nationalNumber) === LengthResult.Possible) {
      result = MatchResult.PossibleLength;
    }
    return result;
  }

  getTypeClassifier(index: number): NationalNumberClassifier {
    return this.typeClassifiers[index];
  }
}

export class ParserData {
  static create(json: ParserDataJson, decode: (i: number) => string) {
    // Main region index, possibly followed by additional regions.
    let r: number = json.r;
    // Total number of regions (at least 1).
    let n: number = json.n ? json.n : 1;
    let regions: string[] = [...Array(n).keys()].map((i) => decode(r + i));
    let npi: number[] = Array.isArray(json.p) ? json.p : json.p ? [json.p] : [];
    let nationalPrefixes: DigitSequence[] = npi.map(i => DigitSequence.parse(decode(i)));
    let nationalPrefixOptional: boolean = !!json.o;
    return new ParserData(regions, nationalPrefixes, nationalPrefixOptional);
  }

  constructor(
      private readonly regions: string[],
      private readonly nationalPrefixes: DigitSequence[],
      private readonly nationalPrefixOptional: boolean) {}

  getRegions(): ReadonlyArray<string> {
    return this.regions;
  }

  getNationalPrefixes(): ReadonlyArray<DigitSequence> {
    return this.nationalPrefixes;
  }

  isNationalPrefixOptional(): boolean {
    return this.nationalPrefixOptional;
  }
}

class NationalNumberClassifier implements ValueMatcher {
  static create(
      json: NationalNumberDataJson,
      decode: (v: number) => string,
      matcherFactory: MatcherFactory): NationalNumberClassifier {
    let matcherCount = json.f.length;
    let matchers = new Map<string, MatcherFunction>();
    for (let i = 0; i < matcherCount; i++) {
      let nnJson = json.f[i];
      matchers.set(decode(nnJson.v), matcherFactory(nnJson.r));
    }
    let defaultValue = json.v ? decode(json.v) : null;
    return new NationalNumberClassifier(matchers, defaultValue);
  }

  constructor(
      private readonly matchers: Map<string, MatcherFunction>,
      private readonly defaultValue: string|null) {}

  matchValues(nationalNumber: DigitSequence, ...values: string[]): MatchResult {
    if (this.defaultValue) {
      throw new Error("match operations are not supported by this classifier");
    }
    let result = MatchResult.Invalid;
    for (let v of values) {
      let matcher = this.matchers.get(v);
      if (matcher !== undefined) {
        result = Math.min(matcher.match(nationalNumber), result);
      }
    }
    return result;
  }

  getPossibleValues(): string[] {
    let values = Array.from(this.matchers.keys());
    if (this.defaultValue) {
      values.push(this.defaultValue);
    }
    return values;
  }

  classifySingleValue(nationalNumber: DigitSequence): string|null {
    for (let [value, matcher] of this.matchers) {
      if (matcher.isMatch(nationalNumber)) {
        return value;
      }
    }
    // This can be null and that's fine (it signals no match).
    return this.defaultValue;
  }

  classifySingleValueAsSet(nationalNumber: DigitSequence): ReadonlySet<string> {
    let values = new Set<string>();
    let value = this.classifySingleValue(nationalNumber);
    if (value) {
      values.add(value);
    }
    return values;
  }

  classifyMultiValue(nationalNumber: DigitSequence): ReadonlySet<string> {
    let values = new Set<string>();
    for (let [value, matcher] of this.matchers) {
      if (matcher.isMatch(nationalNumber)) {
        values.add(value);
      }
    }
    return values;
  }
}

abstract class MatcherFunction {
  private static readonly EmptyMatcher: MatcherFunction = new class extends MatcherFunction {
    match(s: DigitSequence): MatchResult {
      return MatchResult.Invalid;
    }

    isMatch(s: DigitSequence): boolean {
      return false;
    }
  }(0);

  static create(json: MatcherDataJson): MatcherFunction {
    // Sometimes you can get empty data (esp. when restricting validation ranges). This typically
    // means there are NO valid numbers of the restricted range for this calling code, so every
    // input (even the empty sequence) is Invalid.
    return json.b ? new DfaMatcherFunction(json) : MatcherFunction.EmptyMatcher;
  }

  static of(functions: MatcherFunction[]): MatcherFunction {
    if (functions.length == 0) {
      throw new Error("missing matcher function data");
    }
    return functions.length > 1 ? new CombinedMatcherFunction(functions) : functions[0];
  }

  constructor(readonly lengthMask: number) {
    this.lengthMask = lengthMask;
  }

  /** Returns information about a digit sequence based only on the set of known possible lengths. */
  testLength(s: DigitSequence): LengthResult {
    // Fine since DigitSequence is limited to 19 digits.
    let lengthBit = 1 << s.length();
    if ((this.lengthMask & lengthBit) != 0) {
      return LengthResult.Possible;
    }
    // (lengthBit - 1) is a bit-mask which retains only the possible lengths shorter than the input.
    let possibleShorterLengthMask = this.lengthMask & (lengthBit - 1);
    return possibleShorterLengthMask == 0
        // If there are no possible lengths shorter than the input, the input is too short.
        ? LengthResult.TooShort
        // If all the possible lengths are shorter than the input, the input is too long.
        : possibleShorterLengthMask == this.lengthMask ? LengthResult.TooLong : LengthResult.InvalidLength;
  }

  /**
   * Returns matcher information about a given `DigitSequence`.
   *
   * This is more useful than `isMatch(DigitSequence)` since it returns information regarding the
   * state of unmatched sequences. If you only care about matching vs non-matching, call
   * `isMatch(DigitSequence)` instead.
   */
  abstract match(s: DigitSequence): MatchResult;

  /**
   * Returns whether a given `DigitSequence` matches this function. This is functionally equivalent
   * to `match(s) == MatchResult.MATCHED`, but is potentially faster.
   *
   * This can be thought of as testing whether the given sequence is "in the set of all matched
   * sequences for this function" (i.e. it can be thought of as a set containment test).
   */
  abstract isMatch(s: DigitSequence): boolean;
}

class DfaMatcherFunction extends MatcherFunction {
  private readonly matcher: DigitSequenceMatcher;

  constructor(json: MatcherDataJson) {
    super(json.l);

    this.matcher = new DigitSequenceMatcher(DfaMatcherFunction.decodeBase64(json.b));
  }

  match(s: DigitSequence): MatchResult {
    return this.matcher.match(s.iterate());
  }

  isMatch(s: DigitSequence): boolean {
    if (this.testLength(s) != LengthResult.Possible) {
      return false;
    }
    return this.match(s) == MatchResult.Matched;
  }

  private static decodeBase64(base64: string): Buffer {
    let binaryString: string = Buffer.from(base64, 'base64').toString('binary');
    let bytes: Uint8Array = new Uint8Array(binaryString.length);
    for (var i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i);
    }
    return Buffer.from(bytes);
  }
}

class CombinedMatcherFunction extends MatcherFunction {
  constructor(private readonly functions: MatcherFunction[]) {
    super(functions.map(f => f.lengthMask).reduce((a, b) => a | b));
  }

  match(s: DigitSequence): MatchResult {
    // We could stream this, but that doesn't know it can stop once Matched is returned.
    let combinedResult = MatchResult.Invalid;
    for (let i: number = 0; i < this.functions.length; i++) {
      let result = this.functions[i].match(s);
      if (result == MatchResult.Matched) {
        return result;
      }
      combinedResult = Math.min(combinedResult, result);
    }
    return combinedResult;
  }

  isMatch(s: DigitSequence): boolean {
    if (this.testLength(s) == LengthResult.Possible) {
      for (let i: number = 0; i < this.functions.length; i++) {
        if (this.functions[i].match(s) == MatchResult.Matched) {
          return true;
        }
      }
    }
    return false;
  }
}
