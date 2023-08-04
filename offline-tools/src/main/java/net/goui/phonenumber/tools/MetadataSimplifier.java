/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.function.Function.identity;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.LazyArg;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.PrefixTree;
import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.finitestatematcher.compiler.MatcherCompiler;
import com.google.i18n.phonenumbers.metadata.table.RangeKey;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import net.goui.phonenumber.tools.MetadataConfig.CallingCodeConfig;

/**
 * Simplifies phone number metadata to reduce metadata size at the expense of additional
 * false-positive number classification.
 */
final class MetadataSimplifier {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Comparator<Map.Entry<String, RangeTree>> DECREASING_BY_RANGE_SIZE =
      Comparator.<Map.Entry<String, RangeTree>, Long>comparing(e -> e.getValue().size())
          .reversed()
          .thenComparing(Map.Entry::getKey);

  private static final ImmutableMap<Integer, RangeTree> ANY_RANGES =
      IntStream.rangeClosed(1, DigitSequence.MAX_DIGITS)
          .boxed()
          .collect(toImmutableMap(identity(), n -> RangeTree.from(RangeSpecification.any(n))));

  @AutoValue
  abstract static class SimplifiedKey {
    static SimplifiedKey of(RangeKey original, RangeKey simplified, int index) {
      RangeTree ranges = simplified.asRangeTree();
      long cost = ranges.size() - original.asRangeTree().size();
      return new AutoValue_MetadataSimplifier_SimplifiedKey(ranges, cost, index);
    }

    abstract RangeTree ranges();

    abstract long cost();

    abstract int index();
  }

  /**
   * Simplifies metadata according to the given configuration.
   *
   * <p>Range information for each country calling code is simplified separately and different
   * limits can be applied on a per calling code basis.
   */
  public static Metadata simplify(Metadata metadata, MetadataConfig config) {
    Metadata.Builder simplifiedMetadata = Metadata.builder(metadata.root());

    for (DigitSequence cc : metadata.getAvailableCallingCodes()) {
      Optional<CallingCodeConfig> callingCodeConfig = config.getCallingCodeConfig(cc);
      if (callingCodeConfig.isEmpty()) {
        continue;
      }

      int maxFalsePositivePercent = callingCodeConfig.get().maxFalsePositivePercent();
      int minPrefixLength = callingCodeConfig.get().minPrefixLength();
      RangeMap rangeMap = metadata.getRangeMap(cc);

      // If the configuration specifies no additional false-positives, we just copy the range.
      if (maxFalsePositivePercent == 0) {
        simplifiedMetadata.put(cc, rangeMap);
        continue;
      }
      simplifiedMetadata.put(
          cc, simplifyRangeMap(rangeMap, maxFalsePositivePercent, minPrefixLength));
    }
    // Return the final metadata with simplified ranges for every calling code.
    return simplifiedMetadata.build();
  }

  @VisibleForTesting
  static RangeMap simplifyRangeMap(
      RangeMap rangeMap, int maxFalsePositivePercent, int minPrefixLength) {
    // Start by simplifying the validation ranges according to the configuration (but keep
    // the original range since we must avoid changing anything in that).
    RangeTree originalValidationRange = rangeMap.getAllRanges();
    RangeTree simplifiedValidationRange =
        simplifyRange(originalValidationRange, maxFalsePositivePercent, minPrefixLength);
    RangeExpander rangeExpander =
        new RangeExpander(originalValidationRange, simplifiedValidationRange);

    // Now simplify the ranges for the values of each classifier type in turn.
    RangeMap.Builder simplifiedRangeMap = rangeMap.toBuilder();
    RangeTree totalUnassigned = RangeTree.empty();
    for (ClassifierType type : rangeMap.getTypes()) {
      // Process keys from the largest to smallest (in terms of their associated ranges) since
      // this prioritises the ranges which will be tested first and means the statistically
      // most common ranges will have the most visible changes.
      RangeClassifier classifier = rangeMap.getClassifier(type);
      ImmutableSet<String> sortedKeys =
          classifier.orderedEntries().stream()
              .sorted(DECREASING_BY_RANGE_SIZE)
              .map(Map.Entry::getKey)
              .collect(toImmutableSet());

      // Stores the key-to-expanded-range mapping for this type.
      Map<String, RangeTree> expandedRangeMap = new LinkedHashMap<>();

      // Track remaining unassigned ranges for this type, starting with all assignable sequences.
      RangeTree unassignedRange = rangeExpander.getAssignableRange();
      for (String key : sortedKeys) {
        logger.atFinest().log("value: %s", key);
        RangeTree assignedRanges = rangeExpander.expandRange(rangeMap.getRanges(type, key));
        if (!assignedRanges.isEmpty()) {
          expandedRangeMap.put(key, assignedRanges);
          unassignedRange = unassignedRange.subtract(assignedRanges);
        }
      }

      // Having built the map of values-to-expanded-ranges, build a new classifier from it.
      simplifiedRangeMap.put(
          type,
          RangeClassifier.builder()
              .setSingleValued(classifier.isSingleValued())
              .setClassifierOnly(classifier.isClassifierOnly())
              .putAll(expandedRangeMap)
              .build());

      // The total unassigned range is the set of sequences for which one or more values were not
      // assigned. This ensures that (assuming we exclude this from our new range map) every
      // valid sequence will have an assignment in every number type.
      //
      // Note that this DOES NOT mean there cannot be more than one assignment in a given type.
      totalUnassigned = totalUnassigned.union(unassignedRange);
    }
    if (!totalUnassigned.isEmpty()) {
      logger.atFinest().log("unassigned: %s", totalUnassigned);
    }
    // Remove ranges for which one or more values were unassigned, and build our range map.
    return simplifiedRangeMap.build(simplifiedValidationRange.subtract(totalUnassigned));
  }

  @VisibleForTesting
  static class RangeExpander {
    private final RangeTree originalRange;
    private final RangeTree assignableRange;
    private final ImmutableMap<Integer, RangeTree> originalRangeDecomposedByLength;
    private final ImmutableMap<Integer, RangeTree> assignableRangeDecomposedByLength;

    @VisibleForTesting
    RangeExpander(RangeTree originalRange, RangeTree simplifiedRange) {
      checkArgument(
          simplifiedRange.containsAll(originalRange),
          "simplified range must contain all of original range:\noriginal: %s\nsimplified: %s",
          originalRange,
          simplifiedRange);
      logger.atFinest().log("original: %s", originalRange);
      logger.atFinest().log("simplified: %s", simplifiedRange);
      this.originalRange = originalRange;
      // Work out which ranges need to be assigned in order to provide values for the additional
      // ranges added during simplification (this could be empty but we don't care).
      this.assignableRange = simplifiedRange.subtract(originalRange);
      logger.atFinest().log("assignable: %s", assignableRange);
      this.originalRangeDecomposedByLength = decomposeByLength(originalRange);
      this.assignableRangeDecomposedByLength = decomposeByLength(assignableRange);
    }

    private RangeTree getAssignableRange() {
      return assignableRange;
    }

    @VisibleForTesting
    RangeTree expandRange(RangeTree range) {
      checkArgument(
          originalRange.containsAll(range),
          "range to be expanded must be a subset of the original range:\noriginal: %s\nrange: %s",
          originalRange,
          range);
      logger.atFinest().log("range: %s", range);
      if (range.isEmpty()) {
        // Never attempt to expand an empty range (it would just end up empty anyway).
        return range;
      }
      // Find the subset of the original range which shared the same lengths as the given range.
      RangeTree lengthRestrictedOriginalRange =
          recomposeFromLengthsOf(range, originalRangeDecomposedByLength);
      // Then find the minimal prefix which captures everything for the given range, but nothing
      // covered by other values with matching lengths (i.e. in the remaining original range).
      PrefixTree prefix =
          PrefixTree.minimal(range, lengthRestrictedOriginalRange.subtract(range), 0);

      // Now find which of the assignable ranges share the same length as this value's range,
      // and capture them with the prefix. These ranges cannot overlap the original range ranges
      // (since the assignable validation ranges do not overlap them).
      RangeTree lengthRestrictedAssignableRange =
          recomposeFromLengthsOf(range, assignableRangeDecomposedByLength);
      RangeTree capturedRange = prefix.retainFrom(lengthRestrictedAssignableRange);
      logger.atFinest().log("captured: %s", capturedRange);

      // Determine the expanded range. This range has not been explicitly simplified in the same
      // way as the original validation range was, but since it expands into that simplified range,
      // it should also tend to lose its "jagged edges", except of course near other ranges.
      RangeTree expandedRange = range.union(capturedRange);
      logger.atFinest().log("expanded: %s", expandedRange);
      return expandedRange;
    }

    /** Decompose a range into slices with equal length sequences. */
    private static ImmutableMap<Integer, RangeTree> decomposeByLength(RangeTree range) {
      return range.getLengths().stream()
          .collect(toImmutableMap(identity(), n -> range.intersect(ANY_RANGES.get(n))));
    }

    /**
     * Recompose a range from equal length slices based of the possible lengths of a given range.
     */
    private static RangeTree recomposeFromLengthsOf(
        RangeTree range, ImmutableMap<Integer, RangeTree> decomposed) {
      return range.getLengths().stream()
          .map(decomposed::get)
          .filter(Objects::nonNull)
          .reduce(RangeTree.empty(), RangeTree::union);
    }
  }

  /**
   * Range simplification starts from the longest prefixes of a range and gradually "fills in the
   * gaps" in prefixes until enough new numbers have been added to satisfy the given criteria.
   *
   * <p>Gap filling is achieved by decomposing the range into its {@link RangeKey}s and simplifying
   * them by simplifying their prefix
   *
   * <p>This is a good approach because (unlike {@link RangeSpecification}, the prefix for a range
   * key appears only once in the range decomposition and numbers of different length which share a
   * common prefix are simplified together.
   *
   * <p>For example, the following range specifications:
   *
   * <pre>{@code
   * 1[2-4][7-9]xxx,
   * 1[2-4][7-9]xxxx,
   * 1[2-4][7-9]xxxxxx,
   * }</pre>
   *
   * <p>define a single {@code RangeKey}:
   *
   * <pre>{@code
   * prefix = "1[2-4][7-9]", lengths = {6,7,9}
   * }</pre>
   *
   * <p>This can then be simplified by "collapsing" the final term of the prefix:
   *
   * <pre>{@code
   * prefix = "1[2-4]", lengths = {6,7,9}
   * }</pre>
   *
   * <p>By doing this the count of numbers defined by the {@code RangeKey} increases, which for this
   * example goes from {@code 9099000} to {@code 30330000} (an increase of 33%).
   *
   * <p>These simplified keys can then be sorted by their effective "cost" (the number of new, false
   * positive, sequences they introduce) and applied until the desired threshold is met.
   *
   * <p>By applying the "smallest cost" simplifications first, we maximize the number of simplified
   * prefixes we can apply, which in turn has the biggest impact on the DFA size reduction.
   *
   * <p>As well as limit to how many new sequences can be added, there is also a limit to how short
   * a prefix can be made during simplification, and this can be used to ensure that the resulting
   * range has "N-digits of precision" regardless of how much else was simplified.
   *
   * @param range a single range to simplify.
   * @param maxFalsePositivePercent maximum allowed increase in the size (sequence count) of the
   *     simplified range compared to the original (e.g. a value of {@code 100} allows a range to
   *     double in size).
   * @param minPrefixLength minimum length of any simplified prefix.
   * @return a simplified range which is a superset of the original range, and larger by no more
   *     than {@code maxFalsePositivePercent}%.
   */
  @VisibleForTesting
  static RangeTree simplifyRange(
      RangeTree range, int maxFalsePositivePercent, int minPrefixLength) {
    // New maximum range size.
    long maxSize =
        BigInteger.valueOf(range.size())
            .multiply(BigInteger.valueOf(100 + maxFalsePositivePercent))
            .divide(BigInteger.valueOf(100))
            .min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValueExact();
    long lastSize;
    do {
      lastSize = range.size();
      ImmutableList<RangeKey> originalRangeKeys = RangeKey.decompose(range);

      // Simplify each RangeKey with a sufficiently long prefix.
      List<SimplifiedKey> simplifiedKeys = new ArrayList<>();
      for (int i = 0; i < originalRangeKeys.size(); i++) {
        RangeKey key = originalRangeKeys.get(i);
        RangeSpecification prefix = key.getPrefix();
        // Ignore prefixes at or shorter than the minimum.
        if (prefix.length() <= minPrefixLength) {
          continue;
        }
        int newLength = prefix.length() - 1;
        // Remove the "least significant" entry in the range specification and store it.
        RangeKey simplifiedKey =
            RangeKey.create(prefix.first(newLength).getPrefix(), key.getLengths());
        simplifiedKeys.add(SimplifiedKey.of(key, simplifiedKey, i));
      }
      // Sort and apply the keys by increasing cost to make a new range.
      simplifiedKeys.sort(
          Comparator.comparing(SimplifiedKey::cost).thenComparing(SimplifiedKey::index));
      for (SimplifiedKey simplifiedKey : simplifiedKeys) {
        RangeTree modifiedRanges = range.union(simplifiedKey.ranges());
        // If after applying a simplification the maximum size is exceeded, return the previous
        // range. Note that the amount by which the size of the range increases is often less
        // than the size of the simplified range (due to overlaps). So we cannot just increment
        // a local cost value and we must re-obtain it from the new range.
        if (modifiedRanges.size() > maxSize) {
          break;
        }
        range = modifiedRanges;
      }
      // If a loop does not result in a size change, we have reached a steady state. This is
      // actually not uncommon for some types of range data, since they are already close to
      // maximally simplified.
    } while (range.size() > lastSize && range.size() < maxSize);

    logger.atFine().log(
        "%s%%, %d, %d", maxFalsePositivePercent, minPrefixLength, lazyCompiledLength(range));
    if (logger.atFinest().isEnabled()) {
      range
          .asRangeSpecifications()
          .forEach(s -> logger.atFinest().log("%s, %s, %s", s, s.length(), s.getSequenceCount()));
    }
    return range;
  }

  static LazyArg<Integer> lazyCompiledLength(RangeTree r) {
    return () -> MatcherCompiler.compile(r).length;
  }

  private MetadataSimplifier() {}
}
