/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

/**
 * Top-level metadata used to define matcher metadata for multiple number types across a range of
 * calling codes. Non-public API. DO NOT import.
 */
export interface MetadataJson {
  /** Schema version information. */
  ver: VersionJson;

  /**
   * List of tokenized type names shared by all calling codes in the order they appear in every
   * `CallingCodeJson` instance. This can be omitted if only validation data is present.
   */
  typ?: number[];  // TYPes

  /**
   * Bitmask of type indices names for classifiers which are defined to return single values. If
   * this is not present, the mask value is zero.
   *
   * Note that this DOES NOT imply that classifiers are disjoint, since allowing classifiers to
   * overlap can reduce data size greatly. Classifiers are ordered such that the first matching
   * matcher defines the result, even if later matchers would also match.
   */
  svm?: number;  // Single-Valued type Mask.

  /**
   * Bitmask of type indices for classifiers which DO NOT support matcher functionality. If this
   * is not present, the mask value is zero.
   */
  com?: number;  // Classifier Only type Mask.

  /**
   * Ordered list of calling code specific range data. This is ordered by the digit sequence
   * prefix, and not the numerical value of the calling code (e.g. 41, < 420 ... < 43).
   * However, code processing the data should not assume any specific ordering.
   */
  ccd: CallingCodeJson[];  // Calling Code Data

  /**
   * Indexed array of strings used in classifier types and range keys (in an arbitrary order).
   * This exists to save space for repeated strings used in most/all regions.
   * We do not attempt to assign enums for these values since they can be user defined.
   */
  tok: string[];  // TOKens
}

/**
 * Version information used to ensure that loaded metadata is compatible with the expectations
 * of the class using it.
 */
export interface VersionJson {
  /**
   * Data structure major version, increased when incompatible changes are made to the protocol
   * buffer structure (e.g. removing fields). Code should test this version explicitly and reject
   * any unsupported/unknown major versions.
   *
   * This relates only to the protocol buffer structure, and is unrelated to the semantic meaning
   * of the data held by this message.
   */
  maj: number;  // unsigned integer

  /**
   * Data structure minor version, increased when backwards compatible changes are made (e.g. adding
   * new fields which are optional for existing code). Code may rely on a larger minor version than
   * it was expecting, but not smaller.
   *
   * This relates only to the protocol buffer structure, and is unrelated to the semantic meaning
   * of the data held by this message.
   */
  min: number;  // unsigned integer

  /**
   * A URL/URI or other unique label identifying the data schema. This defines the available data,
   * such as the available classifiers and their semantic meaning.
   *
   * Within a schema, all classifiers have a local namespace and keys such as "FIXED_LINE" can mean
   * semantically different things for different schemas.
   *
   * This value may or may not reference a real web page, but if it does, that page should explain
   * the schema semantics.
   *
   * Once published, a data schema should always retain the same semantics for existing classifiers.
   *
   * Code may accept any one of multiple schemas if their meanings are sufficiently similar, but
   * should not assume that the same classifier/key name(s) will imply the same semantics.
   */
  uri: string;

  /**
   * The data schema version defining which classifiers are present. This should be increased when
   * new data is added to an existing schema. Code can rely on later versions of a schema being
   * backwards compatible with earlier code.
   */
  ver: number;  // unsigned integer
}

/**
 * Matcher data for a single calling code.
 *
 * Apart from some shared type information (`typ:` and `svt:`) and the tokenized string values,
 * which are held at the top-level, this structure represents everything you need to know about a
 * single calling code.
 */
export interface CallingCodeJson {
  /**
   * Digit sequence 1 to 3 digits long as its integer value (not tokenized). This only works
   * because calling codes never have leading zeros.
   */
  c: number;

  /**
   * Index of national prefix(es) for this calling code. Most regions have either one or no national
   * prefix, but some can have several. The first listed prefix is the default for formatting.
   */
  p: number[] | number;

  /*
   * Valid example national number. This is derived from libphonenumber, and is NOT arbitrary.
   * Example numbers should not be callable, otherwise you risk causing problems for real people.
   * The exact type of this number is not well defined, and can change over time. It is up to the
   * person generating the metadata to configure the example number type appropriately.
   */
  e?: string;

  /**
   * Index of range(s) which defined the set of valid numbers in this region. For "simplified"
   * data, this may greatly exceed the original "raw" ranges, but must always be a superset.
   *
   * However, since the original data can often includes many ranges which are not associated with
   * "active"/callable numbers, users should not treat false-positives with any great significance.
   */
  r?: number[] | number;

  /**
   * Classifiers for different phone number types. This list has a one-to-one association with the
   * `typ:` list, and every calling code has the same number of elements in this list.
   */
  n?: NationalNumberDataJson[];

  /**
   * List of anonymous matcher data shared by both validity data and national number matchers.
   *
   * The index in this list corresponds to the indices used in the 'r:' (ranges) lists.
   */
  m: MatcherDataJson[];
}

/**
 * Matcher data for a single phone number type and calling code (e.g. phone number Tariff in calling
 * code +41).
 */
export interface NationalNumberDataJson {
  /**
   * The tokenized string index for the default value of this classifier.
   *
   * For single valued matchers, this is the value we assign if none of the other matchers match.
   * This value does not apply for multi-valued matchers and should not be set.
   *
   * This need not be the default value specified from "custom" classifiers and may change
   * arbitrarily as range data changes.
   */
  v?: number;

  /**
   * List of matcher functions to test in order.
   *
   * For single valued matchers, the first matching value is returned. For multi-valued matchers,
   * the set of all matched values is returned.
   */
  f: MatcherFunctionJson[];
}

/** Represents a function to match a digit sequence with a single value. */
export interface MatcherFunctionJson {
  /** The tokenized string index for the value associated with this function's range data. */
  v: number;

  /**
   * Index(es) for the range data of this function. If more than one index is present, a composite
   * matcher is created. The index is local per CallingCodeJson.
   */
  r?: number[] | number;
}

/**
 * Represents a single anonymous matcher. These can be combined to make composite matchers.
 * 
 * Currently JavaScript DOES NOT support regular expressions (since there's no equivalent to the
 * `hitEnd()` functionality found in Java regular expressions and needed for proper matching).
 */
export interface MatcherDataJson {
  /**
   * A bit-mask of possible digit sequence lengths encoded in the matcher data. Use this as a fast
   * rejection test before executing a full match.
   */
  l: number;

  /** Byte data defining a `DigitSequenceMatcher`, densely encoded in a UTF-16 string. */
  b: string;
}
