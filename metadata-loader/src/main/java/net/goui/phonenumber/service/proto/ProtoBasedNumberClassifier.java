/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.naturalOrder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableTable;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.LengthResult;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.VersionInfo;
import net.goui.phonenumber.proto.Metadata;
import net.goui.phonenumber.proto.Metadata.MetadataProto;
import net.goui.phonenumber.proto.Metadata.NationalNumberDataProto;

final class ProtoBasedNumberClassifier implements RawClassifier {
  private final VersionInfo version;
  // TODO: Create encapsulation of CallingCodeData and migrate everything.
  private final ImmutableMap<DigitSequence, MatcherFunction> validityMatchers;
  private final ImmutableTable<DigitSequence, String, NationalNumberClassifier> classifierTable;
  private final ImmutableMap<DigitSequence, DigitSequence> exampleNumberMap;
  private final ImmutableListMultimap<DigitSequence, DigitSequence> nationalPrefixMap;
  private final ImmutableSet<String> singleValuedTypes;
  private final ImmutableSet<String> classifierOnlyTypes;
  private final ImmutableSetMultimap<String, String> possibleValues;

  public ProtoBasedNumberClassifier(MetadataProto metadataProto) {
    this.version = versionOf(metadataProto);

    List<String> tokens = metadataProto.getTokenList();
    List<String> typeNames =
        metadataProto.getTypeList().stream().map(tokens::get).collect(toImmutableList());
    ImmutableMap.Builder<DigitSequence, MatcherFunction> validityMatchers = ImmutableMap.builder();
    ImmutableTable.Builder<DigitSequence, String, NationalNumberClassifier> classifiers =
        ImmutableTable.builder();
    classifiers.orderRowsBy(naturalOrder()).orderColumnsBy(naturalOrder());
    ImmutableMap.Builder<DigitSequence, DigitSequence> exampleNumberMap = ImmutableMap.builder();
    ImmutableListMultimap.Builder<DigitSequence, DigitSequence> nationalPrefixMap =
        ImmutableListMultimap.builder();
    for (Metadata.CallingCodeProto callingCodeProto : metadataProto.getCallingCodeDataList()) {
      DigitSequence cc = getCallingCode(callingCodeProto);

      ImmutableList<DigitSequence> nationalPrefixes =
          callingCodeProto.getNationalPrefixList().stream()
              .map(tokens::get)
              .map(DigitSequence::parse)
              .collect(toImmutableList());
      nationalPrefixMap.putAll(cc, nationalPrefixes);

      String example = callingCodeProto.getExampleNumber();
      if (!example.isEmpty()) {
        exampleNumberMap.put(cc, DigitSequence.parse(example));
      }

      ImmutableList<MatcherFunction> matchers =
          callingCodeProto.getMatcherDataList().stream()
              .map(MatcherFunction::fromProto)
              .collect(toImmutableList());
      // For now, assume that if there are no validity matcher indices, we just use 0.
      Function<List<Integer>, MatcherFunction> matcherFactory =
          indices -> indices.isEmpty() ? matchers.get(0) : combinedMatcherOf(matchers, indices);

      List<Integer> validityMatcherIndexList = callingCodeProto.getValidityMatcherIndexList();
      validityMatchers.put(cc, matcherFactory.apply(validityMatcherIndexList));
      List<NationalNumberDataProto> nnd = callingCodeProto.getNationalNumberDataList();
      checkState(
          nnd.size() == typeNames.size(),
          "invalid phone number metadata (unexpected national number data): %s",
          callingCodeProto);
      for (int i = 0; i < typeNames.size(); i++) {
        classifiers.put(
            cc,
            typeNames.get(i),
            NationalNumberClassifier.create(nnd.get(i), tokens::get, matcherFactory));
      }
    }
    this.validityMatchers = validityMatchers.build();
    this.classifierTable = classifiers.build();
    this.nationalPrefixMap = nationalPrefixMap.build();
    this.exampleNumberMap = exampleNumberMap.buildOrThrow();

    this.singleValuedTypes =
        metadataProto.getSingleValuedTypeList().stream()
            .map(typeNames::get)
            .collect(toImmutableSet());
    this.classifierOnlyTypes =
        metadataProto.getClassifierOnlyTypeList().stream()
            .map(typeNames::get)
            .collect(toImmutableSet());

    ImmutableSetMultimap.Builder<String, String> possibleValues = ImmutableSetMultimap.builder();
    possibleValues.orderKeysBy(naturalOrder()).orderValuesBy(naturalOrder());
    this.classifierTable
        .cellSet()
        .forEach(c -> possibleValues.putAll(c.getColumnKey(), c.getValue().getPossibleValues()));
    this.possibleValues = possibleValues.build();
  }

  private MatcherFunction combinedMatcherOf(
      ImmutableList<MatcherFunction> matchers, List<Integer> indices) {
    return MatcherFunction.combine(indices.stream().map(matchers::get).collect(toImmutableList()));
  }

  private static VersionInfo versionOf(MetadataProto proto) {
    MetadataProto.VersionInfo v = proto.getVersion();
    return VersionInfo.of(
        v.getDataSchemaUri(), v.getDataSchemaVersion(), v.getMajorVersion(), v.getMinorVersion());
  }

  private static DigitSequence getCallingCode(Metadata.CallingCodeProto ccp) {
    return DigitSequence.parse(Integer.toString(ccp.getCallingCode()));
  }

  @Override
  public VersionInfo getVersion() {
    return version;
  }

  @Override
  public ImmutableSet<DigitSequence> getSupportedCallingCodes() {
    return classifierTable.rowKeySet();
  }

  @Override
  public ImmutableList<DigitSequence> getNationalPrefixes(DigitSequence callingCode) {
    return nationalPrefixMap.get(callingCode);
  }

  @Override
  public Optional<DigitSequence> getExampleNationalNumber(DigitSequence callingCode) {
    return Optional.ofNullable(exampleNumberMap.get(callingCode));
  }

  @Override
  public ImmutableSet<String> getSupportedNumberTypes() {
    return classifierTable.columnKeySet();
  }

  @Override
  public boolean isSingleValued(String numberType) {
    return singleValuedTypes.contains(numberType);
  }

  @Override
  public boolean supportsValueMatcher(String numberType) {
    return !classifierOnlyTypes.contains(numberType);
  }

  @Override
  public ImmutableSet<String> getPossibleValues(String numberType) {
    return possibleValues.get(numberType);
  }

  @Override
  public Set<String> classify(
      DigitSequence callingCode, DigitSequence nationalNumber, String numberType) {
    if (getValidityMatcher(callingCode).isMatch(nationalNumber)) {
      // Single valued and multivalued data is slightly different, and we cannot just call
      // classifyMultiValue() on single-valued data. Instead, we call classifyUniquely() and
      // put the result into a singleton set.
      NationalNumberClassifier classifier = getClassifier(callingCode, numberType);
      return isSingleValued(numberType)
          ? classifier.classifySingleValueAsSet(nationalNumber)
          : classifier.classifyMultiValue(nationalNumber);
    }
    return ImmutableSet.of();
  }

  @Override
  public String classifyUniquely(
      DigitSequence callingCode, DigitSequence nationalNumber, String numberType) {
    if (!getValidityMatcher(callingCode).isMatch(nationalNumber)) {
      return "";
    }
    if (!isSingleValued(numberType)) {
      throw new UnsupportedOperationException(
          "unique classification of a multi-valued type is not supported: " + numberType);
    }
    return getClassifier(callingCode, numberType).classifySingleValue(nationalNumber);
  }

  @Override
  public LengthResult testLength(DigitSequence callingCode, DigitSequence nationalNumber) {
    return getValidityMatcher(callingCode).testLength(nationalNumber);
  }

  @Override
  public MatchResult match(DigitSequence callingCode, DigitSequence nationalNumber) {
    MatcherFunction matcher = validityMatchers.get(callingCode);
    return matcher != null ? matcher.match(nationalNumber) : MatchResult.INVALID;
  }

  @Override
  public ValueMatcher getValueMatcher(DigitSequence callingCode, String numberType) {
    return getClassifier(callingCode, numberType);
  }

  private MatcherFunction getValidityMatcher(DigitSequence callingCode) {
    MatcherFunction matcher = validityMatchers.get(callingCode);
    if (matcher != null) {
      return matcher;
    }
    throw new IllegalArgumentException(String.format("unsupported calling code: %s", callingCode));
  }

  private NationalNumberClassifier getClassifier(DigitSequence callingCode, String numberType) {
    NationalNumberClassifier classifier = classifierTable.get(callingCode, numberType);
    if (classifier != null) {
      return classifier;
    }
    throw new IllegalArgumentException(
        !classifierTable.containsRow(callingCode)
            ? String.format("unsupported calling code: %s", callingCode)
            : String.format("unsupported number type: %s", numberType));
  }
}
