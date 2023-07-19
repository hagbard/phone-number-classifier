/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static net.goui.phonenumber.tools.ClassifierType.VALIDITY;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.LinkedHashMap;
import java.util.Map;

@AutoValue
abstract class RangeMap {

  static final class Builder {
    private final Map<ClassifierType, RangeClassifier> map = new LinkedHashMap<>();

    Builder() {}

    @CanIgnoreReturnValue
    public Builder put(ClassifierType type, RangeClassifier classifier) {
      map.put(type, classifier);
      return this;
    }

    public RangeMap build(RangeTree allRanges) {
      // Bound all classifiers by the outer range to ensure that classifiers don't classify
      // invalid sequences.
      ImmutableMap<ClassifierType, RangeClassifier> trimmedMap =
          map.entrySet().stream()
              .collect(toImmutableMap(Map.Entry::getKey, e -> e.getValue().intersect(allRanges)));
      return new AutoValue_RangeMap(allRanges, trimmedMap);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public abstract RangeTree getAllRanges();

  // Internal field (shouldn't be needed by outside this class).
  abstract ImmutableMap<ClassifierType, RangeClassifier> classifiers();

  public final ImmutableSet<ClassifierType> getTypes() {
    return classifiers().keySet();
  }

  public final RangeClassifier getClassifier(ClassifierType type) {
    return checkNotNull(classifiers().get(type), "no such type: %s", type.id());
  }

  public final RangeTree getRanges(ClassifierType type, String key) {
    RangeClassifier classifier = getClassifier(type);
    return classifier != null ? classifier.getRanges(key) : RangeTree.empty();
  }

  public final RangeMap trimValidRanges() {
    RangeClassifier validityClassifier = classifiers().get(VALIDITY);
    if (validityClassifier == null) {
      return this;
    }
    RangeTree validRanges = validityClassifier.getRanges("VALID");
    Builder trimmedMap = RangeMap.builder();
    for (Map.Entry<ClassifierType, RangeClassifier> e : classifiers().entrySet()) {
      if (e.getKey().equals(VALIDITY)) {
        // Don't add the validity classifier (there's no point).
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
