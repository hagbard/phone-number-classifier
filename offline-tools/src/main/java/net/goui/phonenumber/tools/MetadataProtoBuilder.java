/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;
import static net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.MatcherType.DIGIT_SEQUENCE_MATCHER;
import static net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.MatcherType.REGULAR_EXPRESSION;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.LazyArg;
import com.google.common.primitives.Bytes;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.finitestatematcher.compiler.MatcherCompiler;
import com.google.i18n.phonenumbers.metadata.regex.RegexGenerator;
import com.google.protobuf.ByteString;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import net.goui.phonenumber.proto.Metadata.*;
import net.goui.phonenumber.tools.Metadata.RangeClassifier;
import net.goui.phonenumber.tools.Metadata.RangeMap;

final class MetadataProtoBuilder {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Comparator<Map.Entry<String, RangeTree>> BY_RANGE_SIZE =
      Comparator.<Map.Entry<String, RangeTree>, Long>comparing(e -> e.getValue().size())
          .reversed()
          .thenComparing(Map.Entry::getKey);

  private static final Comparator<MatcherFunctionProto> BY_BYTE_LENGTH =
      Comparator.comparing(MatcherFunctionProto::getSerializedSize)
          .thenComparing(MatcherFunctionProto::getValue);

  private static final RegexGenerator regexGenerator =
      RegexGenerator.basic()
          .withDfaFactorization()
          .withSubgroupOptimization()
          .withDotMatch()
          .withTailOptimization();

  public static MetadataProto toMetadataProto(Metadata metadata, MetadataConfig config) {
    return new MetadataProtoBuilder(config).buildMetadata(metadata);
  }

  private final MetadataConfig config;
  private final LinkedHashMap<String, Integer> tokens = new LinkedHashMap<>();

  private MetadataProtoBuilder(MetadataConfig config) {
    this.config = config;
    // This prevents unset token values being conflated with a real tokenized string.
    tokenize("");
  }

  private int tokenize(String string) {
    return tokens.computeIfAbsent(string, s -> tokens.size());
  }

  private MetadataProto buildMetadata(Metadata metadata) {
    MetadataProto.Builder outputProto = MetadataProto.newBuilder();
    outputProto.setVersion(config.getVersion());

    metadata.getTypes().stream()
        .map(ClassifierType::id)
        .map(this::tokenize)
        .forEach(outputProto::addType);

    Map<Integer, Boolean> singleValuedTypes = new HashMap<>();
    Map<Integer, Boolean> classifierOnlyTypes = new HashMap<>();
    for (DigitSequence cc : metadata.getAvailableCallingCodes()) {
      CallingCodeProto.Builder callingCodeData = outputProto.addCallingCodeDataBuilder();
      callingCodeData.setCallingCode(Integer.parseInt(cc.toString()));

      Map<RangeTree, Integer> rangesMap = new LinkedHashMap<>();

      // Adds shared matcher data, returning its index.
      Function<RangeTree, Integer> matcherDataCollector =
          ranges -> {
            int index = rangesMap.computeIfAbsent(ranges, r -> rangesMap.size());
            int matcherCount = callingCodeData.getMatcherDataCount();
            // Add new data if not previous in the list.
            if (index == matcherCount) {
              logger.atFine().log("ranges[%d]: %s", index, ranges);
              callingCodeData.addMatcherData(buildMatcherData(ranges));
            }
            return index;
          };

      // Currently validity data is always index 0, but in theory it could be split into
      // several matchers and have multiple indices.
      RangeMap rangeMap = metadata.getRangeMap(cc);
      // We assume that an empty validity matcher index list means "use index 0", so no need to
      // call addValidityMatcherIndex(0) with the result.
      checkState(
          matcherDataCollector.apply(rangeMap.getAllRanges()) == 0,
          "bad validity matcher index (should be zero): %s",
          callingCodeData);
      checkState(rangeMap.getTypes().asList().equals(metadata.getTypes()));

      for (int typeIndex = 0; typeIndex < metadata.getTypes().size(); typeIndex++) {
        ClassifierType type = metadata.getTypes().get(typeIndex);
        RangeClassifier classifier = rangeMap.getClassifier(type);

        callingCodeData.addNationalNumberData(
            buildNationalNumberData(classifier, matcherDataCollector));
        Boolean previous = singleValuedTypes.put(typeIndex, classifier.isSingleValued());
        checkState(
            previous == null || previous == classifier.isSingleValued(),
            "inconsistent classifier types (single- vs multi- valued): %s",
            rangeMap);
        // Track classifier only types and make sure they are all consistent.
        previous = classifierOnlyTypes.put(typeIndex, classifier.isClassifierOnly());
        checkState(
            previous == null || previous == classifier.isClassifierOnly(),
            "inconsistent classifier types (matcher vs classifier only): %s",
            rangeMap);
      }
    }
    singleValuedTypes.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .forEach(outputProto::addSingleValuedType);
    classifierOnlyTypes.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .forEach(outputProto::addClassifierOnlyType);
    outputProto.addAllToken(tokens.keySet());
    return outputProto.build();
  }

  private NationalNumberDataProto buildNationalNumberData(
      RangeClassifier classifier, Function<RangeTree, Integer> dataCollector) {
    NationalNumberDataProto.Builder proto = NationalNumberDataProto.newBuilder();
    ImmutableList<MatcherFunctionProto> sortedMatchers =
        classifier.orderedEntries().stream()
            .sorted(BY_RANGE_SIZE)
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> buildMatcherFunction(e.getKey(), dataCollector.apply(e.getValue())))
            .collect(toImmutableList());

    Predicate<MatcherFunctionProto> includedMatchers = m -> true;
    if (classifier.isClassifierOnly() && classifier.isSingleValued()) {
      // Without partial matching enabled we can reduce the data size for single valued types by
      // omitting the matcher data for the default value (and we can choose to select the largest
      // compiled data for the default).
      //
      // The downside to this size saving is that now the default value is likely to be one of the
      // larger number ranges (and thus more common) but we only infer its value after testing all
      // the other matchers.
      int largestProtoValue = sortedMatchers.stream().max(BY_BYTE_LENGTH).orElseThrow().getValue();
      includedMatchers = m -> largestProtoValue != m.getValue();
      proto.setDefaultValue(largestProtoValue);
    }

    sortedMatchers.stream().filter(includedMatchers).forEach(proto::addMatcher);
    return proto.build();
  }

  private MatcherFunctionProto buildMatcherFunction(String value, int index) {
    MatcherFunctionProto.Builder function =
        MatcherFunctionProto.newBuilder().setValue(tokenize(value));
    // As an optimization, when the index is zero, we omit listing anything. This works because
    // matcher[0] is typically the validation matcher and this is shared when there's only one value
    // for a type. It saves a few hundred bytes in the final data.
    if (index != 0) {
      function.addMatcherIndex(index);
    }
    return function.build();
  }

  private MatcherDataProto buildMatcherData(RangeTree ranges) {
    MatcherDataProto.Builder proto = MatcherDataProto.newBuilder();
    int lengthMask = ranges.getLengths().stream().mapToInt(n -> 1 << n).reduce(0, (a, b) -> a | b);
    proto.setPossibleLengthsMask(lengthMask);
    if (config.matcherTypes().contains(DIGIT_SEQUENCE_MATCHER)) {
      byte[] bytes = MatcherCompiler.compile(ranges);
      proto.setMatcherData(ByteString.copyFrom(bytes));
      logger.atFine().log("matcher bytes: %s", toHexString(bytes));
    }
    if (config.matcherTypes().contains(REGULAR_EXPRESSION)) {
      String regex = regexGenerator.toRegex(ranges);
      proto.setRegexData(regex);
      logger.atFine().log("matcher regex: [%s]", regex);
    }
    return proto.build();
  }

  private static LazyArg<String> toHexString(byte[] bytes) {
    return () ->
        Bytes.asList(bytes).stream()
            .map(b -> String.format("%02X", b & 0xFF))
            .collect(joining(",", "[", "]"));
  }
}
