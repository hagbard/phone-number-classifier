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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.ClassifierConfigProto;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.CustomClassifierProto;

final class RangeMapTransformer implements Function<RangeMap, RangeMap> {
  public static RangeMapTransformer from(MetadataConfigProto proto) {
    Stream<RangeTransformer> configuredClassifiers =
        proto.getClassifierList().stream().map(RangeTransformer::from);

    String validationRangeExpression = proto.getValidationRanges();
    if (!validationRangeExpression.isEmpty()) {
      configuredClassifiers =
          Stream.concat(
              configuredClassifiers,
              Stream.of(RangeTransformer.validity(validationRangeExpression)));
    }
    ImmutableMap<ClassifierType, RangeTransformer> transformers =
        configuredClassifiers.collect(toImmutableMap(t -> t.type, Function.identity()));
    return new RangeMapTransformer(transformers);
  }

  public static RangeMapTransformer identity(Set<ClassifierType> classifierTypes) {
    ImmutableMap<ClassifierType, RangeTransformer> transformers =
        classifierTypes.stream()
            .map(ClassifierType::id)
            .map(RangeTransformer.Copying::new)
            .collect(toImmutableMap(t -> t.type, Function.identity()));
    return new RangeMapTransformer(transformers);
  }

  private final ImmutableMap<ClassifierType, RangeTransformer> transformers;

  RangeMapTransformer(ImmutableMap<ClassifierType, RangeTransformer> transformers) {
    this.transformers = checkNotNull(transformers);
  }

  public ImmutableSet<ClassifierType> getOutputTypes() {
    return transformers.keySet();
  }

  @Override
  public RangeMap apply(RangeMap input) {
    RangeMap.Builder output = RangeMap.builder();
    for (Map.Entry<ClassifierType, RangeTransformer> e : transformers.entrySet()) {
      output.put(e.getKey(), e.getValue().transform(input));
    }
    return output.build(input.getAllRanges());
  }

  abstract static class RangeTransformer {
    static RangeTransformer from(ClassifierConfigProto config) {
      return config.hasCustom()
          ? new RangeTransformer.Custom(config.getTypeName(), config.getCustom())
          : new RangeTransformer.Copying(config.getTypeName());
    }

    static RangeTransformer validity(String expression) {
      return new Validity(expression);
    }

    protected final ClassifierType type;

    private RangeTransformer(String typeName) {
      this.type = ClassifierType.of(typeName);
    }

    public abstract RangeClassifier transform(RangeMap rangeMap);

    static final class Custom extends RangeTransformer {
      private final String defaultValue;
      private final boolean isSingleValued;
      private final boolean isClassifierOnly;
      private final ImmutableMap<String, Function<RangeMap, RangeTree>> valueFunctions;

      private Custom(String typeName, CustomClassifierProto config) {
        super(typeName);
        checkArgument(
            !type.isBaseType(),
            "must use a custom type (e.g. 'TYPE:MOBILE' for custom classifiers (type=%s)",
            type);
        this.defaultValue = config.getDefaultValue();
        this.isSingleValued = config.getIsSingleValued();
        this.isClassifierOnly = config.getDisablePartialMatching();
        this.valueFunctions =
            config.getValueList().stream()
                .collect(
                    toImmutableMap(
                        MetadataConfigProto.CustomValueProto::getName,
                        v -> RangeExpression.parse(v.getRanges())));
      }

      @Override
      public RangeClassifier transform(RangeMap inputMap) {
        Map<String, RangeTree> ranges = new LinkedHashMap<>();
        valueFunctions.forEach(
            (k, f) -> {
              RangeTree r = f.apply(inputMap);
              if (!r.isEmpty()) ranges.put(k, r);
            });

        RangeTree allRanges = inputMap.getAllRanges();
        RangeTree unionOfRanges =
            ranges.values().stream().reduce(RangeTree.empty(), RangeTree::union);
        RangeTree unassignedRanges = allRanges.subtract(unionOfRanges);

        // If disjoint in the original range map, we can select our own default value (even if one
        // were specified explicitly, since that maps to no values).
        boolean isDisjoint =
            ranges.values().stream().mapToLong(RangeTree::size).sum() == allRanges.size();

        if (!isDisjoint && isSingleValued) {
          RangeTree unassigned = unionOfRanges;
          for (String key : ImmutableSet.copyOf(ranges.keySet())) {
            RangeTree rangeTree = ranges.get(key);
            RangeTree trimmedRanges = rangeTree.intersect(unassigned);
            if (trimmedRanges.isEmpty()) {
              ranges.remove(key);
            } else {
              ranges.put(key, trimmedRanges);
            }
            unassigned = unassigned.subtract(rangeTree);
          }
          isDisjoint = true;
        }

        if (isDisjoint && !unassignedRanges.isEmpty() && !defaultValue.isEmpty()) {
          ranges.put(defaultValue, unassignedRanges);
        }
        return RangeClassifier.builder()
            .putAll(ranges)
            .setSingleValued(isSingleValued)
            .setClassifierOnly(isClassifierOnly)
            .build();
      }
    }

    static final class Copying extends RangeTransformer {
      private Copying(String typeName) {
        super(typeName);
        checkArgument(
            type.isBaseType(),
            "must use a base type (e.g. 'TARIFF' for non-custom classifiers (type=%s)",
            type);
      }

      @Override
      public RangeClassifier transform(RangeMap rangeMap) {
        return rangeMap.getClassifier(type);
      }
    }

    static final class Validity extends RangeTransformer {
      private final RangeExpression validityFn;

      private Validity(String expression) {
        super("VALIDITY");
        this.validityFn = RangeExpression.parse(expression);
      }

      @Override
      public RangeClassifier transform(RangeMap rangeMap) {
        RangeClassifier.Builder classifier = RangeClassifier.builder().setSingleValued(true);
        RangeTree validRanges = validityFn.apply(rangeMap);
        classifier.put("VALID", validRanges);
        classifier.put("INVALID", rangeMap.getAllRanges().subtract(validRanges));
        return classifier.build();
      }
    }
  }
}
