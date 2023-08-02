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
import { PhoneNumberFormatter, FormatType } from "./phone-number-formatter.js";

/**
 * A base class from which custom, type-safe classifiers can be derived.
 *
 * This class maps the structure of a known metadata schema to set of a type-safe accessor methods
 * from which type specific classifiers and matchers can be obtained. In typical usage, a subclass
 * of this class will exist for each uniquely structured metadata schema, and will only be extended
 * as that schema evolves.
 *
 * In the subclass, new type-safe fields should be created for each supported phone number attribute
 * (e.g. "TARIFF" or "TYPE") via the `protected` `forXxx()` methods. Currently there exists support
 * for mapping to/from numeric or string based enums or (as a last resort) using string identifiers
 * directly.
 */
export abstract class AbstractPhoneNumberClassifier {
  private readonly rawClassifier: RawClassifier;

  protected constructor(json: string) {
    this.rawClassifier = RawClassifier.create(json);
  }

  /**
   * Tests a phone number against the possible lengths of any number in its numbering plan. This
   * method is fast, but takes no account of the number type.
   *
   * If this returns `LengthResult.TooShort`, `LengthResult.TooLong` or `LengthResult.InvalidLength`
   * then the number definitely cannot be valid for any possible type.
   *
   * However, if it returns `LengthResult.Possible`, it may still not be a valid number.
   *
   * @param {PhoneNumber} number an E.164 phone number, including country calling code.
   * @returns {LengthResult} a simply classification based only on the number's length for its
   *     calling code.
   */
  testLength(number: PhoneNumber): LengthResult {
    return this.rawClassifier.testLength(number.getCallingCode(), number.getNationalNumber());
  }

  /**
   * Matches a phone number against the set of valid ranges.
   *
   * This is a more accurate test than `testLength(PhoneNumber)`, and returns useful information
   * about the phone number, but will take longer to perform.
   *
   * For example, if this returns `MatchResult.PartialMatch`, then the given number is a prefix of
   * at least one valid number (e.g. adding more digits may produce a valid number). However if it
   * returns `MatchResult.Invalid`, then no additional digits can make the number valid. This can be
   * useful for giving feedback to users when they are entering numbers.
   *
   * @param {PhoneNumber} number an E.164 phone number, including country calling code.
   * @returns {MatchResult} a classification based on the valid number ranges for its calling code.
   */
  public match(number: PhoneNumber): MatchResult {
    return this.rawClassifier.match(number.getCallingCode(), number.getNationalNumber());
  }

  // Converts "UpperCamelCase" enum names to "UPPER_SNAKE_CASE" number type names.
  private static toRawTypeName(camelCase: string) {
    return camelCase.replace(
        /([a-z]+)([A-Z]|$)/g,
        (_, g1: string, g2: string) => g1.toUpperCase() + (g2 ? "_" + g2 : ""));
  }

  protected getRawClassifier(): RawClassifier {
    return this.rawClassifier;
  }

  protected getFormatter(type: FormatType): PhoneNumberFormatter {
    return new PhoneNumberFormatter(this.getRawClassifier(), type);
  }

  getMainRegion(callingCode: DigitSequence): string {
    return this.rawClassifier.getMainRegion(callingCode);
  }

  getExampleNumber(callingCode: DigitSequence): PhoneNumber|null {
    let nn = this.rawClassifier.getExampleNationalNumber(callingCode);
    return nn ? PhoneNumber.of(callingCode, nn) : null;
  }

  /**
   * Returns a new classifier factory which can be used to obtain a type-safe matcher or classifier
   * for the given string based enum. The enum used MUST use constant names which match the
   * underlying metadata values (after case conversion):
   *
   * ```
   * enum MyEnum {
   *   Foo,     // matches underlying value "FOO"
   *   BarBaz,  // matches underlying value "BAR_BAZ"
   * }
   * ```
   *
   * In typical usage this is expected to look something like:
   *
   * `private readonly myMatcher: Matcher<MyEnum> = forNumericEnum("MY_TYPE", MyEnum).matcher();`
   *
   * Where the returned classifier factory is just used as part of the fluent expression to obtain
   * the desired matcher/classifier.
   */
  // https://github.com/microsoft/TypeScript/issues/30611#issuecomment-570773496
  protected forNumericEnum<T extends string, V extends number>(
      numberType: string, enumType: { [key in T]: V }): ClassifierFactory<V> {
    // Numeric enums are "double mapped" objects, so:
    //   enum MyEnum { Foo, Bar }
    // ends up as:
    //   { "Foo": 0, "Bar": 1, "0": "Foo", "1": "Bar" }
    // Note that numeric values are number, but when reversed they are strings.
    let keys: string[] =
        Object.keys(enumType).filter(key => typeof enumType[key as T] === "number");
    let map: Map<V, string> =
        new Map(keys.map(k =>
            [enumType[k as T] as V, AbstractPhoneNumberClassifier.toRawTypeName(k)]));
    let converter: Converter<V> =
        Converter.fromMap(map, this.rawClassifier.getPossibleValues(numberType));
    return new ClassifierFactory<V>(
        () => new TypedClassifier<V>(this.rawClassifier, numberType, converter));
  }

  /**
   * Returns a new classifier factory which can be used to obtain a type-safe matcher or classifier
   * for the given string based enum. The enum used MUST have string values which match the
   * underlying metadata values (using `UPPER_SNAKE_CASE`):
   *
   * ```
   * enum MyEnum {
   *   Foo = "FOO",
   *   BarBaz = "BAR_BAZ",
   * }
   * ```
   *
   * In typical usage this is expected to look something like:
   *
   * `private readonly myMatcher: Matcher<MyEnum> = forStringEnum("MY_TYPE", MyEnum).matcher();`
   *
   * Where the returned classifier factory is just used as part of the fluent expression to obtain
   * the desired matcher/classifier.
   */
  protected forStringEnum<T extends string, V extends string>(
      numberType: string, enumType: { [key in T]: V }): ClassifierFactory<V> {
    // String enums just map the keys to the values:
    //   enum MyEnum { Foo = "FOO", Bar = "BAR" }
    // ends up as:
    //   { "Foo": "FOO", "Bar": "BAR" }
    // they do NOT provide a double mapping.
    //
    // We say that users should define the string value as the raw value name, but we can convert
    // from UpperCamelCase as well.
    let keys: string[] = Object.keys(enumType);
    let map: Map<V, string> =
        new Map(keys.map(k =>
            [enumType[k as T] as V, AbstractPhoneNumberClassifier.toRawTypeName(k)]));
    let converter: Converter<V> =
        Converter.fromMap(map, this.rawClassifier.getPossibleValues(numberType));
    return new ClassifierFactory<V>(
        () => new TypedClassifier<V>(this.rawClassifier, numberType, converter));
  }

  /**
   * Returns a new classifier factory which can be used to obtain a string based (non type-safe)
   * matcher or classifier instance. This matcher must be used with keys which match the underlying
   * metadata values (e.g. "FIXED_LINE").
   *
   * In typical usage this is expected to look something like:
   *
   * `private myMatcher: Matcher<string> = forStrings("MY_TYPE").matcher();`
   *
   * Where the returned classifier factory is just used as part of the fluent expression to obtain
   * the desired matcher/classifier.
   */
   protected forStrings(numberType: string): ClassifierFactory<string> {
    return new ClassifierFactory<string>(
        () => new TypedClassifier(this.rawClassifier, numberType, Converter.identity()));
  }
}

class Converter<V> {
  static fromMap<V>(map: Map<V, string>, requiredValues: ReadonlySet<string>): Converter<V> {
    let inverse: Map<string, V> = new Map(Array.from(map.keys()).map(k => [map.get(k)!, k]));
    if (map.size != inverse.size) {
      throw new Error(`enum map is not a bijection: ${map}`);
    }
    // The enum is allowed to have more values than a possible in any given piece of metadata.
    for (let s of requiredValues) {
      if (!inverse.has(s)) {
        throw new Error(`raw value '${s}' is not correctly mapped by enum map: ${map}`);
      }
    }
    return new Converter<V>((v: V) => map.get(v), (s: string) => inverse.get(s));
  }

  static identity(): Converter<string> {
    return new Converter<string>((s: string) => s, (s: string) => s);
  }

  constructor(
      private readonly forward: (v: V) => string | undefined,
      private readonly backward: (s: string) => V | undefined) {}

  doForward(v: V): string {
    let s = this.forward(v);
    if (s !== undefined) {
      return s;
    }
    throw new Error(`illegal value for converter: ${v}`);
  }

  doBackward(s: string): V {
    let v = this.backward(s);
    if (v !== undefined) {
      return v;
    }
    throw new Error(`unexpected raw value for converter: ${s}`);
  }
}

// Implementation class which provides all matcher/classifier APIs. We don't return this publicly
// as it has methods which will fail at runtime. Instead we return a potentially narrowed API which
// can be relied upon by the caller.
class TypedClassifier<V> implements SingleValuedMatcher<V> {
  constructor(
      private readonly rawClassifier: RawClassifier,
      private readonly numberType: string,
      private readonly converter: Converter<V>) {}

  ensureMatcher(): TypedClassifier<V> {
    if (this.rawClassifier.isClassifierOnly(this.numberType)) {
      throw new Error(
          `underlying classifier does not support partial matching for: ${this.numberType}`);
    }
    return this;
  }

  ensureSingleValued(): TypedClassifier<V> {
    if (this.rawClassifier.getClassifierReturnType(this.numberType) != ReturnType.SingleValued) {
      throw new Error(`underlying classifier is not single valued for: ${this.numberType}`);
    }
    return this;
  }

  classify(number: PhoneNumber): ReadonlySet<V> {
    let rawSet: ReadonlySet<string> = this.rawClassifier.classify(
        number.getCallingCode(), number.getNationalNumber(), this.numberType);
    return new Set<V>(Array.from(rawSet).map(s => this.converter.doBackward(s)));
  }

  getPossibleValues(number: PhoneNumber): ReadonlySet<V> {
    let matcher: ValueMatcher =
        this.rawClassifier.getValueMatcher(number.getCallingCode(), this.numberType);
    return new Set(Array.from(this.rawClassifier.getPossibleValues(this.numberType))
        .filter(v => matcher.matchValues(number.getNationalNumber(), v) <= MatchResult.PartialMatch)
        .map((s: string) => this.converter.doBackward(s)));
  }

  matchValues(number: PhoneNumber, ...values: V[]): MatchResult {
    let rawValues: string[] = values.map(v => this.converter.doForward(v));
    let matcher: ValueMatcher =
        this.rawClassifier.getValueMatcher(number.getCallingCode(), this.numberType);
    return matcher.matchValues(number.getNationalNumber(), ...rawValues);
  }

  identify(number: PhoneNumber): V | null {
    let rawValue = this.rawClassifier.classifyUniquely(
        number.getCallingCode(), number.getNationalNumber(), this.numberType);
    return rawValue ? this.converter.doBackward(rawValue) : null;
  }
}

/**
 * Factory class for creating type-safe classifiers. This is used as part of a fluent statement to
 * create type-safe classifiers in subclasses. It does not need to be exported since it should
 * never be assigned to a field or otherwise referenced outside a fluent expression.
 */
class ClassifierFactory<V> {
  constructor(private readonly newFn: () => TypedClassifier<V>) {}

  /**
   * Returns a simple (non partial matching) classifier. It is necessary to use this API when the
   * underlying metadata has disabled partial matching.
   */
  classifier(): Classifier<V> { return this.newFn(); }

  /**
   * Returns a classifier capable of partial matching. Use this API if the underlying metadata
   * supports partial matching to expose additional methods to users.
   */
  matcher(): Matcher<V> { return this.newFn().ensureMatcher(); }

  /**
   * Returns a simple (non partial matching) classifier, for which values are unique. This adds
   * the `SingleValuedClassifier#identify(PhoneNumber)` method for identifying unique values
   * more simply.
   *
   * To use this classifier, the metadata it uses must have been built with
   * `is_single_valued: true` in the configuration, or be one of the known single valued basic
   * types (e.g. "TYPE" or "TARIFF", but not "REGION" etc.).
   */
  singleValuedClassifier(): SingleValuedClassifier<V> { return this.newFn().ensureSingleValued(); }

  /**
   * Returns a classifier capable of partial matching, for which values are unique. This adds the
   * `SingleValuedClassifier#identify(PhoneNumber)` method for identifying unique values more
   * simply.
   *
   * To use this classifier, the metadata it uses must have been built with
   * `is_single_valued: true` in the configuration, or be one of the known single valued basic
   * types (e.g. "TYPE" or "TARIFF", but not "REGION" etc.).
   */
  singleValuedMatcher(): SingleValuedMatcher<V> {
    return this.newFn().ensureSingleValued().ensureMatcher();
  }
}

/** Simple (non partial matching) classifier API supported by all classifiers. */
export interface Classifier<V> {
  /**
   * Classifies a complete phone number into a set of possible values for a number type.
   *
   * For example, when classifying regions, the number `+447691123456` is classified as  belonging
   * to any of the regions `{"GB", "GG", "JE"}`.
   *
   * If an invalid number is given, it will not be classified as any value, resulting in an
   * empty set. The also applies if the number is a partial number or has extra digits.
   *
   * If the underlying metadata for the type being used in "single valued", then the resulting set
   * will never contain more than one entry (though it may be empty). However, in this case it is
   * recommended to call `SingleValuedClassifier#identify(PhoneNumber)` instead.
   */
  classify(number: PhoneNumber): ReadonlySet<V>;
}

/** Extended classifier API which permits partial matching for number types. */
export interface Matcher<V> extends Classifier<V> {
  /**
   * Returns the possible values which a phone number or prefix could be classified as.
   *
   * This method is intended for use when phone numbers are being entered or analyzed for issues.
   * Most commonly it is expected that partial/incomplete phone numbers would be passed to this
   * method in order to determine what values are still possible as the number is entered. This
   * can be used to provided feedback to users, such as indicating likely errors at the point they
   * occur.
   *
   * If a complete phone number is given, this is the same as calling
   * `Classifier#classify(PhoneNumber)`.
   */
  getPossibleValues(number: PhoneNumber): ReadonlySet<V>;

  /**
   * Matches a phone number or prefix to determine its status with respect one or more values.

   * For example, a phone number may be a `MatchResult.PartialMatch` for "FIXED_LINE" but would be
   * `MatchResult.Invalid` for "MOBILE". This method is intended for use when phone numbers are
   * being entered or analyzed for issues.
   *
   * If more than one value is given, the results are merged consistent with testing the union of
   * all range sets associated with the given values. This is useful if business logic treats two
   * or more values in the same way (e.g. "MOBILE" and "FIXED_LINE_OR_MOBILE").
   */
  matchValues(number: PhoneNumber, ...values: V[]): MatchResult;
}

/** Single valued classifier API, which has an easier way to classify values uniquely. */
export interface SingleValuedClassifier<V> extends Classifier<V> {
  /**
   * Classifies a complete phone number as a unique value for a number type.
   *
   * If an invalid number is given, it will not be classified as any value, returning `null`. This
   * also applies if the number is a partial number or has extra digits.
   *
   * For single valued types, this is expected to be more convenient than calling
   * `Classifier#classify(PhoneNumber)` and having to deal with a set with zero or one elements in
   * it.
   */
  identify(number: PhoneNumber): V | null;
}

/** Single valued matcher API, which has an easier way to classify values uniquely. */
export interface SingleValuedMatcher<V> extends Matcher<V>, SingleValuedClassifier<V> {}
