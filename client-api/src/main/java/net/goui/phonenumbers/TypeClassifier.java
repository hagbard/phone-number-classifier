/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static net.goui.phonenumbers.MatchResult.INVALID;
import static net.goui.phonenumbers.MatchResult.PARTIAL_MATCH;

import com.google.common.collect.ImmutableSet;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.goui.phonenumbers.AbstractPhoneNumberClassifier.SingleValuedMatcher;
import net.goui.phonenumbers.metadata.RawClassifier;
import net.goui.phonenumbers.metadata.RawClassifier.ValueMatcher;

/** Type safe classifier for phone numbers which implements all matcher/classifier APIs. */
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
  public Set<V> classify(PhoneNumber number) {
    return toValueSet(
        phoneNumberClassifier
            .rawClassifier()
            .classify(number.getCallingCode(), number.getNationalNumber(), typeName));
  }

  @Override
  public Optional<V> identify(PhoneNumber number) {
    checkState(
        isSingleValued,
        "cannot \"uniquely identify\" numbers for a multi-valued type: %s",
        typeName);
    return toOptionalValue(
        phoneNumberClassifier
            .rawClassifier()
            .classifyUniquely(number.getCallingCode(), number.getNationalNumber(), typeName));
  }

  @Override
  public MatchResult match(PhoneNumber number, V value) {
    return rawClassifier()
        .getValueMatcher(number.getCallingCode(), typeName())
        .matchValue(number.getNationalNumber(), toValueString(value));
  }

  @Override
  public Set<V> getPossibleValues(PhoneNumber number) {
    ValueMatcher matcher = rawClassifier().getValueMatcher(number.getCallingCode(), typeName);
    DigitSequence nationalNumber = number.getNationalNumber();
    return rawClassifier().getPossibleValues(typeName).stream()
        .filter(v -> matcher.matchValue(nationalNumber, v).compareTo(PARTIAL_MATCH) <= 0)
        .map(toValueFn)
        .collect(toImmutableSet());
  }

  @Override
  @SafeVarargs // Requires that the method be explicitly final.
  public final MatchResult match(PhoneNumber number, V... values) {
    return match(number, Arrays.stream(values));
  }

  @Override
  public MatchResult match(PhoneNumber number, Set<V> values) {
    return match(number, values.stream());
  }

  private MatchResult match(PhoneNumber number, Stream<V> values) {
    // Get this first to catch bad types early.
    ValueMatcher matcher = rawClassifier().getValueMatcher(number.getCallingCode(), typeName());
    DigitSequence nationalNumber = number.getNationalNumber();
    return values
        .map(v -> matcher.matchValue(nationalNumber, toValueString(v)))
        .reduce(INVALID, MatchResult::combine);
  }
}
