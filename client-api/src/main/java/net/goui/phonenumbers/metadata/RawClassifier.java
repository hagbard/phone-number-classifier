/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.metadata;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import net.goui.phonenumbers.DigitSequence;
import net.goui.phonenumbers.LengthResult;
import net.goui.phonenumbers.MatchResult;

/**
 * The underlying classifier API from which type-safe user-facing phone number APIs can be built.
 *
 * <p>Different implementations of this class should exist for different data packaging schemes
 * (e.g. protocol buffer or JSON data). However since this class is provided by the {@link
 * ClassifierLoader}, there's no need for anyone the care about those details.
 *
 * <p>This interface is not intended for use outside the "Phone Numbers" library, and in general
 * users should avoid accessing any classes in the {@code metadata} package.
 */
public interface RawClassifier {
  /**
   * Each type specific classifier either supports single-valued classification (i.e. a number is
   * identified as a single value), or multi-valued classification (i.e. a number is classified as
   * belonging to a set of values). For ease of use, an extended API exists only for single-valued
   * classifiers.
   */
  enum ReturnType {
    /** Phone numbers are associated with a single value (e.g. the phone number type). */
    SINGLE_VALUED,
    /** Phone numbers can be associated with multiple values (e.g. region codes). */
    MULTI_VALUED,
    /** Indicates a serious error in library configuration. */
    UNKNOWN
  }

  /**
   * Value matchers are returned by the underlying classifier implementation to encapsulate matching
   * functionality for a specific number type (e.g. "TYPE" or "REGION") of a calling code. They are
   * the basic building block for higher level match operations.
   */
  interface ValueMatcher {
    /**
     * Matches a number, or partial number, to a given value within the context of a known country
     * calling code and number type. If a number is matched against multiple values for the same
     * matcher, a single valid {@link MatchResult} can be determined by calling {@link
     * MatchResult#combine(MatchResult, MatchResult)}.
     */
    MatchResult matchValue(DigitSequence nationalNumber, String value);

    /**
     * Returns the possible values for this matcher (i.e. values for which {@link
     * #matchValue(DigitSequence, String)} could return something other than {@link
     * MatchResult#INVALID}).
     */
    ImmutableSet<String> getPossibleValues();
  }

  /**
   * Returns the version information of the underlying metadata used to build the raw classifier.
   */
  VersionInfo getVersion();

  /**
   * Returns the country calling codes supported by the metadata schema. Different metadata schemas
   * can make different promises about which calling codes are supported, and without knowledge of
   * the schema being used, there are no guarantees about what is in this set.
   */
  ImmutableSet<DigitSequence> getSupportedCallingCodes();

  /**
   * Returns the names of the number types supported by the metadata schema. Types are either the
   * "built in" basic types (e.g. "TYPE", "TARIFF", "REGION") or custom type names of the form
   * "PREFIX:TYPE".
   *
   * <p>Users should never need to know about this information since it is expected that they will
   * use a type-safe API derived from {@code AbstractPhoneNumberClassifier}, where there is no need
   * to enumerate the available types.
   */
  ImmutableSet<String> getSupportedNumberTypes();

  ParserData getParserData(DigitSequence callingCode);

  /**
   * Returns whether a phone number could be valid according only to its length.
   *
   * <p>This is a fast test, but it can produce significant false positive results (e.g. suggesting
   * that invalid numbers are possible). It should only be used to reject impossible numbers before
   * further checking is performed.
   */
  LengthResult testLength(DigitSequence callingCode, DigitSequence nationalNumber);

  /** Matches a phone number against all valid ranges of a country calling code. */
  MatchResult match(DigitSequence callingCode, DigitSequence nationalNumber);

  /**
   * Returns whether this classifier is single- or multi- valued. This is used to ensure that the
   * APIs available to users are correct for the underlying metadata, but users should never need to
   * call this method directly.
   */
  boolean isSingleValued(String numberType);

  /**
   * Returns whether this classifier supports matching operations on partial numbers via the {@link
   * ValueMatcher} interface. This is used to ensure that the APIs available to users are correct
   * for the underlying metadata, but users should never need to call this method directly.
   */
  boolean supportsValueMatcher(String numberType);

  /**
   * Returns the possible values for a given number type. Not all number plans will use all these
   * types, and its even possible (due to metadata customization) that some values do not appear for
   * any country calling code. Having this set is useful to ensure that higher level, type-safe APIs
   * account for every value.
   */
  ImmutableSet<String> getPossibleValues(String numberType);

  Set<String> classify(DigitSequence callingCode, DigitSequence nationalNumber, String numberType);

  /** */
  String classifyUniquely(
      DigitSequence callingCode, DigitSequence nationalNumber, String numberType);

  ValueMatcher getValueMatcher(DigitSequence callingCode, String numberType);
}
