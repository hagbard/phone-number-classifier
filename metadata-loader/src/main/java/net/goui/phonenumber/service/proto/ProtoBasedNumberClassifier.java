/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.LengthResult;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumber.metadata.ParserData;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.VersionInfo;
import net.goui.phonenumber.proto.Metadata;
import net.goui.phonenumber.proto.Metadata.MetadataProto;

final class ProtoBasedNumberClassifier implements RawClassifier {
  private final VersionInfo version;
  private final ImmutableMap<DigitSequence, CallingCodeClassifier> classifiers;
  private final ImmutableMap<String, TypeInfo> typeInfoMap;

  public ProtoBasedNumberClassifier(MetadataProto metadataProto) {
    this.version = versionOf(metadataProto);

    List<String> tokens = metadataProto.getTokenList();
    ImmutableList<String> typeNames =
        metadataProto.getTypeList().stream().map(tokens::get).collect(toImmutableList());
    this.classifiers = buildCallingCodeClassifiers(metadataProto, typeNames.size(), tokens::get);
    this.typeInfoMap =
        IntStream.range(0, typeNames.size())
            .boxed()
            .collect(toImmutableMap(typeNames::get, i -> getTypeInfo(metadataProto, i)));
  }

  private static VersionInfo versionOf(MetadataProto proto) {
    MetadataProto.VersionInfo v = proto.getVersion();
    return VersionInfo.of(
        v.getDataSchemaUri(), v.getDataSchemaVersion(), v.getMajorVersion(), v.getMinorVersion());
  }

  private static ImmutableSortedMap<DigitSequence, CallingCodeClassifier>
      buildCallingCodeClassifiers(
          MetadataProto metadataProto, int typeCount, IntFunction<String> tokenDecoder) {
    ImmutableSortedMap.Builder<DigitSequence, CallingCodeClassifier> classifiers =
        ImmutableSortedMap.naturalOrder();
    for (Metadata.CallingCodeProto callingCodeProto : metadataProto.getCallingCodeDataList()) {
      DigitSequence cc = DigitSequence.parse(Integer.toString(callingCodeProto.getCallingCode()));
      CallingCodeClassifier classifier =
          CallingCodeClassifier.from(callingCodeProto, typeCount, tokenDecoder);
      classifiers.put(cc, classifier);
    }
    return classifiers.buildOrThrow();
  }

  private TypeInfo getTypeInfo(MetadataProto metadataProto, int i) {
    boolean isSingleValued = metadataProto.getSingleValuedTypeList().contains(i);
    boolean supportsPartialMatcher = !metadataProto.getClassifierOnlyTypeList().contains(i);
    ImmutableSet<String> possibleValues =
        classifiers.values().stream()
            .map(c -> c.getTypeClassifier(i))
            .flatMap(c -> c.getPossibleValues().stream())
            .collect(toImmutableSet());
    return new TypeInfo(i, possibleValues, isSingleValued, supportsPartialMatcher);
  }

  @Override
  public VersionInfo getVersion() {
    return version;
  }

  @Override
  public ImmutableSet<DigitSequence> getSupportedCallingCodes() {
    return classifiers.keySet();
  }

  @Override
  public ImmutableSet<String> getSupportedNumberTypes() {
    return typeInfoMap.keySet();
  }

  @Override
  public ParserData getParserData(DigitSequence callingCode) {
    return getClassifier(callingCode).getParserData();
  }

  @Override
  public boolean isSingleValued(String numberType) {
    return getTypeInfo(numberType).isSingleValued;
  }

  @Override
  public boolean supportsValueMatcher(String numberType) {
    return getTypeInfo(numberType).supportsValueMatcher;
  }

  @Override
  public ImmutableSet<String> getPossibleValues(String numberType) {
    return getTypeInfo(numberType).possibleValues;
  }

  @Override
  public Set<String> classify(
      DigitSequence callingCode, DigitSequence nationalNumber, String numberType) {
    CallingCodeClassifier ccClassifier = getClassifier(callingCode);
    int typeIndex = getTypeIndex(numberType);
    if (ccClassifier.getValidityMatcher().isMatch(nationalNumber)) {
      // Single valued and multivalued data is slightly different, and we cannot just call
      // classifyMultiValue() on single-valued data. Instead, we call classifyUniquely() and
      // put the result into a singleton set.
      TypeClassifier classifier = ccClassifier.getTypeClassifier(typeIndex);
      return isSingleValued(numberType)
          ? classifier.classifySingleValueAsSet(nationalNumber)
          : classifier.classifyMultiValue(nationalNumber);
    }
    return ImmutableSet.of();
  }

  @Override
  public String classifyUniquely(
      DigitSequence callingCode, DigitSequence nationalNumber, String numberType) {
    if (!isSingleValued(numberType)) {
      throw new UnsupportedOperationException(
          "unique classification of a multi-valued type is not supported: " + numberType);
    }
    CallingCodeClassifier ccClassifier = getClassifier(callingCode);
    int typeIndex = getTypeIndex(numberType);
    if (!ccClassifier.getValidityMatcher().isMatch(nationalNumber)) {
      return "";
    }
    return ccClassifier.getTypeClassifier(typeIndex).classifySingleValue(nationalNumber);
  }

  @Override
  public LengthResult testLength(DigitSequence callingCode, DigitSequence nationalNumber) {
    return getClassifier(callingCode).getValidityMatcher().testLength(nationalNumber);
  }

  @Override
  public MatchResult match(DigitSequence callingCode, DigitSequence nationalNumber) {
    return getClassifier(callingCode).getValidityMatcher().match(nationalNumber);
  }

  @Override
  public ValueMatcher getValueMatcher(DigitSequence callingCode, String numberType) {
    int typeIndex = getTypeIndex(numberType);
    return getClassifier(callingCode).getTypeClassifier(typeIndex);
  }

  private CallingCodeClassifier getClassifier(DigitSequence callingCode) {
    CallingCodeClassifier classifier = classifiers.get(callingCode);
    checkArgument(classifier != null, "unsupported calling code: %s", callingCode);
    return classifier;
  }

  private int getTypeIndex(String typeName) {
    return getTypeInfo(typeName).index;
  }

  private TypeInfo getTypeInfo(String typeName) {
    TypeInfo typeInfo = typeInfoMap.get(typeName);
    checkArgument(typeInfo != null, "unsupported type: %s", typeName);
    return typeInfo;
  }

  private static class TypeInfo {
    final int index;
    final boolean isSingleValued;
    final boolean supportsValueMatcher;
    final ImmutableSet<String> possibleValues;

    TypeInfo(
        int index,
        ImmutableSet<String> possibleValues,
        boolean isSingleValued,
        boolean supportsValueMatcher) {
      this.index = index;
      this.possibleValues = possibleValues;
      this.isSingleValued = isSingleValued;
      this.supportsValueMatcher = supportsValueMatcher;
    }
  }
}
