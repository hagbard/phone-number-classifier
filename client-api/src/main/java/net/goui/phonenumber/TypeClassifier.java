/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static net.goui.phonenumber.MatchResult.INVALID;
import static net.goui.phonenumber.MatchResult.PARTIAL_MATCH;

import com.google.common.collect.ImmutableSet;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.goui.phonenumber.AbstractPhoneNumberClassifier.SingleValuedMatcher;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.RawClassifier.ValueMatcher;

final class TypeClassifier<V> implements SingleValuedMatcher<V> {
  private final AbstractPhoneNumberClassifier phoneNumberClassifier;
  private final String typeName;
  private final Function<String, V> toValueFn;
  private final Function<? super V, String> toStringFn;
  private final Class<V> valueType;
  private final boolean isSingleValued;

  TypeClassifier(
      AbstractPhoneNumberClassifier phoneNumberClassifier,
      String typeName,
      Function<String, V> toValueFn,
      Function<? super V, String> toStringFn,
      Class<V> valueType) {
    this.phoneNumberClassifier = checkNotNull(phoneNumberClassifier);
    this.typeName = checkNotNull(typeName);
    this.toValueFn = checkNotNull(toValueFn);
    this.toStringFn = checkNotNull(toStringFn);
    this.valueType = checkNotNull(valueType);
    this.isSingleValued = rawClassifier().isSingleValued(typeName);
    checkArgument(
        rawClassifier().getSupportedNumberTypes().contains(typeName),
        "no such number type '%s'; possible types: %s",
        typeName,
        rawClassifier().getSupportedNumberTypes());
    ImmutableSet<String> possibleValues = rawClassifier().getPossibleValues(typeName);
    long uniqueValues = possibleValues.stream().map(toValueFn).distinct().count();
    checkArgument(
        possibleValues.size() == uniqueValues,
        "type conversion is not a bijection; all values must be uniquely mapped in: %s",
        typeName,
        possibleValues);
  }

  String typeName() {
    return typeName;
  }

  RawClassifier rawClassifier() {
    return phoneNumberClassifier.rawClassifier();
  }

  TypeClassifier<V> ensureMatcher() {
    checkState(
        rawClassifier().supportsValueMatcher(typeName),
        "underlying classifier does not support partial matching for: %s",
        typeName);
    return this;
  }

  TypeClassifier<V> ensureSingleValued() {
    checkState(
        rawClassifier().isSingleValued(typeName),
        "underlying classifier is not single valued for: %s",
        typeName);
    return this;
  }

  <R> R withDecomposed(PhoneNumber number, BiFunction<DigitSequence, DigitSequence, R> fn) {
    return phoneNumberClassifier.withDecomposed(number, fn);
  }

  @Nullable
  String toValueString(@Nullable Object value) {
    return valueType.isInstance(value) ? toStringFn.apply(valueType.cast(value)) : null;
  }

  private Optional<V> toOptionalValue(String rawValue) {
    return rawValue.isEmpty()
        ? Optional.empty()
        : Optional.of(checkNotNull(toValueFn.apply(rawValue)));
  }

  private Set<V> toValueSet(Set<String> rawValues) {
    return new AbstractSet<>() {
      @Override
      public boolean contains(Object value) {
        return rawValues.contains(toValueString(value));
      }

      @Override
      public Iterator<V> iterator() {
        final Iterator<String> it = rawValues.iterator();
        return new Iterator<>() {
          @Override
          public boolean hasNext() {
            return it.hasNext();
          }

          @Override
          public V next() {
            // These strings should always be valid (non-empty) value names.
            return toValueFn.apply(it.next());
          }
        };
      }

      @Override
      public int size() {
        return rawValues.size();
      }
    };
  }

  @Override
  public Set<V> classify(PhoneNumber phoneNumber) {
    return toValueSet(
        phoneNumberClassifier.withDecomposed(
            phoneNumber,
            (cc, nn) -> phoneNumberClassifier.rawClassifier().classify(cc, nn, typeName)));
  }

  @Override
  public Optional<V> identify(PhoneNumber phoneNumber) {
    checkState(
        isSingleValued,
        "cannot \"uniquely identify\" numbers for a multi-valued type: %s",
        typeName);
    return toOptionalValue(
        phoneNumberClassifier.withDecomposed(
            phoneNumber,
            (cc, nn) -> phoneNumberClassifier.rawClassifier().classifyUniquely(cc, nn, typeName)));
  }

  @Override
  public MatchResult match(PhoneNumber phoneNumber, V value) {
    return withDecomposed(
        phoneNumber,
        (cc, nn) ->
            rawClassifier().getValueMatcher(cc, typeName()).matchValue(nn, toValueString(value)));
  }

  @Override
  public Set<V> getPossibleValues(PhoneNumber phoneNumber) {
    ImmutableSet<String> possibleValues = rawClassifier().getPossibleValues(typeName);
    return withDecomposed(
        phoneNumber,
        (cc, nn) ->
            possibleValues.stream()
                .filter(
                    v -> rawClassifier().match(cc, nn, typeName, v).compareTo(PARTIAL_MATCH) <= 0)
                .map(toValueFn)
                .collect(toImmutableSet()));
  }

  @Override
  @SafeVarargs // Requires that the method be explicitly final.
  public final MatchResult match(PhoneNumber phoneNumber, V... values) {
    return withDecomposed(phoneNumber, (cc, nn) -> match(cc, nn, Arrays.stream(values)));
  }

  @Override
  public MatchResult match(PhoneNumber phoneNumber, Set<V> values) {
    return withDecomposed(phoneNumber, (cc, nn) -> match(cc, nn, values.stream()));
  }

  private MatchResult match(
      DigitSequence callingCode, DigitSequence nationalNumber, Stream<V> values) {
    // Get this first to catch bad types early.
    ValueMatcher valueMatcher = rawClassifier().getValueMatcher(callingCode, typeName());
    return values
        .map(v -> valueMatcher.matchValue(nationalNumber, toValueString(v)))
        .reduce(INVALID, MatchResult::combine);
  }
}
