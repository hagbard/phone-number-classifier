/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static net.goui.phonenumber.tools.ClassifierType.VALIDITY;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.proto.Types.ValidNumberType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encapsulates all the classifier data needed for a single numbering plan.
 *
 * <p>This is essentially just a mapping from classifier types to range classifiers, but is a core
 * part of the offline metadata representation. It conceptually corresponds to a {@link
 * com.google.i18n.phonenumbers.metadata.table.RangeTable RangeTable} in the Libphonenumber metadata
 * library, but is less strongly typed and with more limited functionality (however, this does make
 * it more flexible for use with custom classifiers).
 */
@AutoValue
abstract class RangeMap {
  /** A builder for a {@link RangeMap} with which classifiers can be mapped from types. */
  static final class Builder {
    private final Map<ClassifierType, RangeClassifier> map = new LinkedHashMap<>();
    private ImmutableMap<ValidNumberType, DigitSequence> exampleNumbers = ImmutableMap.of();

    Builder() {}

    /** Maps a type to a classifier. */
    @CanIgnoreReturnValue
    public Builder put(ClassifierType type, RangeClassifier classifier) {
      map.put(type, classifier);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setExampleNumbers(ImmutableMap<ValidNumberType, DigitSequence> exampleNumbers) {
      this.exampleNumbers = checkNotNull(exampleNumbers);
      return this;
    }

    /** Builds a new range map bounded by the given range. */
    public RangeMap build(RangeTree allRanges) {
      // Bound all classifiers by the outer range to ensure that individual classifiers don't
      // classify different sets of invalid numbers (which would be confusing).
      ImmutableMap<ClassifierType, RangeClassifier> trimmedMap =
          map.entrySet().stream()
              .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().intersect(allRanges)));
      // Remove any example numbers not valid within the restricted range.
      ImmutableMap<ValidNumberType, DigitSequence> filteredExamples =
          exampleNumbers.entrySet().stream()
              .filter(e -> allRanges.contains(e.getValue()))
              .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
      return new AutoValue_RangeMap(
          allRanges, trimmedMap, filteredExamples);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  Builder toBuilder() {
    return builder().setExampleNumbers(getExampleNumbers());
  }

  /** Returns the bounding range for this range map. */
  public abstract RangeTree getAllRanges();

  // Internal field (shouldn't be needed by outside this class).
  abstract ImmutableMap<ClassifierType, RangeClassifier> classifiers();

  abstract ImmutableMap<ValidNumberType, DigitSequence> getExampleNumbers();

  /** Returns the classifiers in this range map. */
  public final ImmutableSet<ClassifierType> getTypes() {
    return classifiers().keySet();
  }

  /**
   * Returns the classifier associated with the given type.
   *
   * @throws IllegalArgumentException if the classifier type is not present in this range map.
   */
  public final RangeClassifier getClassifier(ClassifierType type) {
    RangeClassifier classifier = classifiers().get(type);
    checkArgument(classifier != null, "no such type '%s' in range map: %s", type.id(), this);
    return classifier;
  }

  /**
   * Returns the ranges associated with the given key for the specified type. This returns the empty
   * range if not value exists for the key.
   *
   * @throws IllegalArgumentException if the classifier type is not present in this range map.
   */
  public final RangeTree getRanges(ClassifierType type, String key) {
    return getClassifier(type).getRanges(key);
  }

  /**
   * Trims a range map to an internally held (special) "validation" classifier. This is called as
   * part of "validation range restriction" and avoids either:
   *
   * <ul>
   *   <li>Removing ranges before simplification, and then overwriting them during simplification.
   *   <li>Restricting simplified metadata to some originally determined range, and leaving unwanted
   *       expanded range assignments in.
   * </ul>
   */
  public final RangeMap trimValidRanges() {
    RangeClassifier validityClassifier = classifiers().get(VALIDITY);
    if (validityClassifier == null) {
      // If there's no validation range restriction, do nothing.
      return this;
    }
    // The validation classifier has two values, "VALID" and "INVALID". By defining invalid ranges
    // explicitly we ensure that range simplification does not overwrite them. If we remove some
    // specific type before simplification, we encode it as an explicitly invalid range and don't
    // risk simplification expanding ranges over the top. Having done simplification we can now
    // restrict the map to the simplified valid ranges.
    RangeTree validRanges = validityClassifier.getRanges("VALID");
    Builder trimmedMap = toBuilder();
    for (Map.Entry<ClassifierType, RangeClassifier> e : classifiers().entrySet()) {
      if (e.getKey().equals(VALIDITY)) {
        // Don't re-add the validity classifier (there's no point).
        continue;
      }
      RangeClassifier classifier = e.getValue();
      RangeClassifier.Builder trimmedClassifier =
          RangeClassifier.builder()
              .setSingleValued(classifier.isSingleValued())
              .setClassifierOnly(classifier.isClassifierOnly());
      for (String key : classifier.orderedKeys()) {
        // The result is ignored if empty.
        trimmedClassifier.put(key, classifier.getRanges(key).intersect(validRanges));
      }
      trimmedMap.put(e.getKey(), trimmedClassifier.build());
    }
    return trimmedMap.build(validRanges);
  }
}
