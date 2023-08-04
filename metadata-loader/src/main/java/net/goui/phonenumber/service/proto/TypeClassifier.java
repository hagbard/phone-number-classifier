/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;
import static net.goui.phonenumber.MatchResult.INVALID;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.MatchResult;
import net.goui.phonenumber.metadata.RawClassifier.ValueMatcher;
import net.goui.phonenumber.proto.Metadata.MatcherFunctionProto;
import net.goui.phonenumber.proto.Metadata.NationalNumberDataProto;

final class TypeClassifier implements ValueMatcher {
  public static TypeClassifier create(
      NationalNumberDataProto proto,
      IntFunction<String> tokenDecoder,
      Function<List<Integer>, MatcherFunction> matcherFactory) {
    return new TypeClassifier(proto, tokenDecoder, matcherFactory);
  }

  private final MatcherFunction[] matchers;
  private final ImmutableMap<String, Integer> indexLookup;

  private TypeClassifier(
      NationalNumberDataProto proto,
      IntFunction<String> tokenDecoder,
      Function<List<Integer>, MatcherFunction> matcherFactory) {
    ImmutableMap.Builder<String, Integer> indexLookup = ImmutableMap.builder();
    int matcherCount = proto.getMatcherCount();
    this.matchers = new MatcherFunction[matcherCount];
    for (int i = 0; i < matcherCount; i++) {
      MatcherFunctionProto f = proto.getMatcher(i);
      matchers[i] = matcherFactory.apply(f.getMatcherIndexList());
      indexLookup.put(tokenDecoder.apply(f.getValue()), i);
    }
    // Empty string if field was unset.
    String defaultValue = tokenDecoder.apply(proto.getDefaultValue());
    if (!defaultValue.isEmpty()) {
      indexLookup.put(defaultValue, matcherCount);
    }
    this.indexLookup = indexLookup.build();
  }

  private String getValue(int index) {
    return indexLookup.keySet().asList().get(index);
  }

  @Override
  public MatchResult matchValue(DigitSequence nationalNumber, String value) {
    // If these do not match, this classifier has a default value, which means it cannot be used
    // for "matching" operations, only classification.
    checkState(
        matchers.length == indexLookup.size(), "match operations not supported by this classifier");
    Integer index = indexLookup.get(value);
    return index != null ? matchers[index].match(nationalNumber) : INVALID;
  }

  @Override
  public ImmutableSet<String> getPossibleValues() {
    return indexLookup.keySet();
  }

  public String classifySingleValue(DigitSequence nationalNumber) {
    int index = indexOfFirstMatch(nationalNumber);
    return index >= 0 ? getValue(index) : "";
  }

  public Set<String> classifySingleValueAsSet(DigitSequence nationalNumber) {
    int index = indexOfFirstMatch(nationalNumber);
    return new IndexedValueSet(index >= 0 ? 1 << index : 0);
  }

  private int indexOfFirstMatch(DigitSequence nationalNumber) {
    int index = 0;
    for (MatcherFunction p : matchers) {
      if (p.isMatch(nationalNumber)) {
        break;
      }
      // The final time round the loop this becomes the index associated with the default value.
      index++;
    }
    // If we don't have a default value then it's possible to fail to match and the index will
    // go out-of-bounds. Return -1 in that case for callers to deal with.
    return index < indexLookup.size() ? index : -1;
  }

  public Set<String> classifyMultiValue(DigitSequence nationalNumber) {
    int mask = 0;
    int bit = 1;
    for (MatcherFunction p : matchers) {
      if (p.isMatch(nationalNumber)) {
        mask = mask | bit;
      }
      bit <<= 1;
    }
    return new IndexedValueSet(mask);
  }

  public final class IndexedValueSet extends AbstractSet<String> {
    private final int mask;

    IndexedValueSet(int mask) {
      checkState(
          indexLookup.size() <= 32,
          "cannot create IntMaskSet if more than 32 values (was %s)",
          indexLookup.size());
      this.mask = mask;
    }

    @Override
    public int size() {
      return Integer.bitCount(mask);
    }

    @Override
    public boolean contains(Object value) {
      @SuppressWarnings("SuspiciousMethodCalls") // It's fine, non-strings are never present.
      Integer index = indexLookup.get(value);
      return index != null && ((1 << index) & mask) != 0;
    }

    @Override
    public Iterator<String> iterator() {
      return new Iterator<>() {
        private int bit = Integer.numberOfTrailingZeros(mask);

        @Override
        public boolean hasNext() {
          return bit != 32;
        }

        @Override
        public String next() {
          // Bit is only set by calling numberOfTrailingZeros(), so 32 means "no more bits".
          if (bit == 32) {
            throw new NoSuchElementException();
          }
          String value = getValue(bit);
          // Mask out all bits up to (and including) the current bit to find the next bit up.
          // (0 -> ...111110, 1 -> ...111100, 2 -> ...111000 ... 30 -> 1000..., 31 -> 0)
          bit = Integer.numberOfTrailingZeros(mask & -(1 << (bit + 1)));
          return value;
        }
      };
    }
  }
}
