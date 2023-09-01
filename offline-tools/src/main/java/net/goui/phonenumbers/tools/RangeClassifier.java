/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.tools;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Encapsulation of a single "column" in a range map in which multiple values are classified by
 * range information.
 *
 * <p>A classifier is really just an ordered sequence of ranges ({@link RangeTree}) and associated
 * values, but is a core part of the offline metadata representation.
 *
 * <p>A classifier can be single- or multi-valued as well as supporting additional partial matching
 * operations. Whether a classifier is single- or multi-valued is a natural consequence of the
 * semantics of the data it represents, but whether a classifier supports partial matching (or not)
 * is a user choice which trades off an increase in data size for additional useful functionality.
 *
 * <p>The classifier variants do not affect how a classifier is built here, but will affect how the
 * metadata is packaged for clients.
 */
@AutoValue
abstract class RangeClassifier {
  /**
   * A builder for a {@link RangeClassifier} with which values can be associated with a number
   * range.
   */
  static final class Builder {
    private final Map<String, RangeTree> map = new LinkedHashMap<>();
    private boolean isSingleValued = false;
    private boolean isClassifierOnly = false;

    private Builder() {}

    /**
     * Sets whether this is to be a single- or multi-valued classifier (see {@link
     * RangeClassifier#isSingleValued()}).
     */
    @CanIgnoreReturnValue
    public Builder setSingleValued(boolean isSingleValued) {
      this.isSingleValued = isSingleValued;
      return this;
    }

    /**
     * Sets whether this classifier will exclude partial matching functionality for the sake of
     * slightly smaller metadata (see {@link RangeClassifier#isClassifierOnly()}).
     */
    @CanIgnoreReturnValue
    public Builder setClassifierOnly(boolean isClassifierOnly) {
      this.isClassifierOnly = isClassifierOnly;
      return this;
    }

    /**
     * Adds a single non-empty range mapping to this classifier. Numbers in the specified range are
     * associated with the given key. However, if the given range is empty, this method has no
     * effect.
     */
    @CanIgnoreReturnValue
    public Builder put(String key, RangeTree ranges) {
      if (!ranges.isEmpty()) {
        map.merge(key, ranges, RangeTree::union);
      }
      return this;
    }

    /**
     * Adds the mappings of the given map to this classifier, preserving iteration order. It is not
     * advisable to pass a hash map to this method. Empty ranges in the map are ignored.
     */
    @CanIgnoreReturnValue
    public Builder putAll(Map<String, RangeTree> rangeMap) {
      rangeMap.forEach(this::put);
      return this;
    }

    /** Returns the new classifier. */
    public RangeClassifier build() {
      return new AutoValue_RangeClassifier(
          isSingleValued, isClassifierOnly, ImmutableMap.copyOf(map));
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns whether this classifier is single valued. Whether a classifier is single- or
   * multi-valued is a natural consequence of the semantics of the data it represents
   *
   * <p>A single valued classifier will classify number by testing ranges in turn until a match is
   * found, which permits an "early exit" from a classification operation. A multivalued classifier
   * returns all values which matched a number, which entails an exhaustive test of every range.
   */
  public abstract boolean isSingleValued();

  /**
   * Returns whether this classifier is intended for use with "partial matching", or only as a basic
   * "classifier" or complete phone numbers.
   *
   * <p>Partial matching enables you to ask questions about incomplete phone numbers, whereas basic
   * classification will treat incomplete and invalid numbers equally.
   */
  public abstract boolean isClassifierOnly();

  // Internal field (shouldn't be needed by outside this class).
  abstract ImmutableMap<String, RangeTree> map();

  /**
   * Returns the keys of this classifier in the order they should be tested for classification.
   *
   * <p>The order is important because it <em>is not</em> guaranteed that the ranges of a classifier
   * are disjoint, even for a "single valued" classifier.
   */
  public final ImmutableSet<String> orderedKeys() {
    return map().keySet();
  }

  /**
   * Returns the entries of this classifier in the order they should be tested for classification.
   *
   * <p>The order is important because it <em>is not</em> guaranteed that the ranges of a classifier
   * are disjoint, even for a "single valued" classifier.
   */
  public final ImmutableSet<Map.Entry<String, RangeTree>> orderedEntries() {
    return map().entrySet();
  }

  /**
   * Returns the ranges for a give key. If the key is not present in the classifier, the empty range
   * is returned.
   */
  public final RangeTree getRanges(String key) {
    return map().getOrDefault(key, RangeTree.empty());
  }

  /**
   * Mimics client side classification for a given digit sequence. This is useful when generating
   * test data.
   */
  public final ImmutableSet<String> classify(DigitSequence seq) {
    Stream<String> matches =
        orderedEntries().stream().filter(e -> e.getValue().contains(seq)).map(Map.Entry::getKey);
    if (isSingleValued()) {
      return matches.findFirst().map(ImmutableSet::of).orElse(ImmutableSet.of());
    } else {
      return matches.collect(toImmutableSet());
    }
  }

  /**
   * Intersects all ranges of this classifier with the given bound. The result is a classifier for
   * which no number outside the bound is present. Note that this <em>does not</em> imply that all
   * numbers inside the bound <em>are</em> assigned a value.
   */
  public final RangeClassifier intersect(RangeTree bound) {
    Builder trimmed =
        builder().setSingleValued(isSingleValued()).setClassifierOnly(isClassifierOnly());
    for (Map.Entry<String, RangeTree> e : orderedEntries()) {
      trimmed.put(e.getKey(), e.getValue().intersect(bound));
    }
    return trimmed.build();
  }
}
