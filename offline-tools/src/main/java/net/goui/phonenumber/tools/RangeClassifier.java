/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

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

@AutoValue
abstract class RangeClassifier {
  static final class Builder {
    private final Map<String, RangeTree> map = new LinkedHashMap<>();
    private boolean isSingleValued = false;
    private boolean isClassifierOnly = false;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder setSingleValued(boolean isSingleValued) {
      this.isSingleValued = isSingleValued;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setClassifierOnly(boolean isClassifierOnly) {
      this.isClassifierOnly = isClassifierOnly;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder put(String key, RangeTree ranges) {
      if (!ranges.isEmpty()) {
        map.merge(key, ranges, RangeTree::union);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public Builder putAll(Map<String, RangeTree> rangeMap) {
      rangeMap.forEach(this::put);
      return this;
    }

    public RangeClassifier build() {
      return new AutoValue_RangeClassifier(
          isSingleValued, isClassifierOnly, ImmutableMap.copyOf(map));
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public abstract boolean isSingleValued();

  public abstract boolean isClassifierOnly();

  // Internal field (shouldn't be needed by outside this class).
  abstract ImmutableMap<String, RangeTree> map();

  public final ImmutableSet<String> orderedKeys() {
    return map().keySet();
  }

  public final ImmutableSet<Map.Entry<String, RangeTree>> orderedEntries() {
    return map().entrySet();
  }

  public final RangeTree getRanges(String key) {
    return map().getOrDefault(key, RangeTree.empty());
  }

  public final ImmutableSet<String> classify(DigitSequence seq) {
    Stream<String> matches =
        orderedEntries().stream().filter(e -> e.getValue().contains(seq)).map(Map.Entry::getKey);
    if (isSingleValued()) {
      return matches.findFirst().map(ImmutableSet::of).orElse(ImmutableSet.of());
    } else {
      return matches.collect(toImmutableSet());
    }
  }

  public final RangeClassifier intersect(RangeTree bound) {
    Builder trimmed =
        builder().setSingleValued(isSingleValued()).setClassifierOnly(isClassifierOnly());
    for (Map.Entry<String, RangeTree> e : orderedEntries()) {
      trimmed.put(e.getKey(), e.getValue().intersect(bound));
    }
    return trimmed.build();
  }
}
