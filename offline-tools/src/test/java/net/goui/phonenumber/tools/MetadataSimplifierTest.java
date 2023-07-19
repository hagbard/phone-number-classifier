/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.truth.Truth.assertThat;
import static net.goui.phonenumber.tools.ClassifierType.REGION;
import static net.goui.phonenumber.tools.ClassifierType.TYPE;
import static net.goui.phonenumber.tools.MetadataSimplifier.simplifyRange;

import com.google.i18n.phonenumbers.metadata.PrefixTree;
import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.Arrays;

import net.goui.phonenumber.tools.MetadataSimplifier.RangeExpander;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataSimplifierTest {
  @Test
  public void testSimplifyingTrivialRangeSpecification() {
    // "12xxxxx" is 2 x larger than the input range, so this is NOT simplified until
    // (maxFalsePositivePercent >= 100) AND (minPrefixLength <= 2).
    RangeTree testRanges = r("12[0-4]xxxx");
    assertUnsimplified(testRanges, 99, 2);
    assertUnsimplified(testRanges, 100, 3);
    assertSimplified(testRanges, 100, 2, "12xxxxx");
  }

  @Test
  public void testRangeSimplificationPrioritizesSmallChanges() {
    // Initial sequence count = 15000.
    // Additional counts (per part) after 2-digit simplification:
    //                       +8000 (54%), +2000 (14%), +5000 (33%)
    RangeTree testRanges = r("12[01]xxx", "34[0-7]xxx", "56[0-4]xxx");
    assertUnsimplified(testRanges, 10, 2);
    assertSimplified(testRanges, 20, 1, "12[01]xxx", "34xxxx", "56[0-4]xxx");
    // At 55% it *could* have simplified "12[01]xxx" --> "12xxxx" if it was just doing them in
    // order, but instead it simplified the two smaller ranges.
    assertSimplified(testRanges, 55, 1, "12[01]xxx", "34xxxx", "56xxxx");
    // At 100% it will simplify all 3 parts.
    assertSimplified(testRanges, 100, 1, "12xxxx", "34xxxx", "56xxxx");
    // At higher percentage thresholds it simplifies to a single digit prefix. These are done in
    // "range key" order (numerical by smallest sequence) since they all have the same "cost".
    assertSimplified(testRanges, 700, 1, "1xxxxx", "34xxxx", "56xxxx");
    assertSimplified(testRanges, 1300, 1, "[13]xxxxx", "56xxxx");
    assertSimplified(testRanges, 1900, 1, "[135]xxxxx");
  }

  @Test
  public void testRangeExpander() {
    RangeTree original = r("[1-3]4[7-9]xx", "[1-3][0-4]xxxx");
    RangeTree simplified = r("[1-3]xxxx", "[1-3]xxxxx");
    RangeExpander expander = new RangeExpander(original, simplified);

    // The range doesn't cover all "1[0-4]..." ranges, so cannot be expanded.
    assertThat(expander.expandRange(r("1[34]xxxx"))).isEqualTo(r("1[34]xxxx"));

    // This range does cover the prefix, so is expanded (but NOT into the 5-digit range).
    assertThat(expander.expandRange(r("1[0-4]xxxx"))).isEqualTo(r("1xxxxx"));

    // These input ranges account for everything starting with a '1', in the original range, so they
    // can be expanded to everything starting with a '1' in the simplified range.
    assertThat(expander.expandRange(r("14[7-9]xx", "1[0-4]xxxx"))).isEqualTo(r("1xxxx", "1xxxxx"));
  }

  @Test
  public void testRangeMapSimplification() {
    // Range designed to simplify to "[1-3]xxxxxx"
    RangeTree totalRanges = r("[1-3][0-2]xxxxx", "[1-3][3-7][5-9]xxxx", "[1-3][5-9][0-4]xxxx");

    // Foo is "everything in the total range that starts with '1'.
    RangeTree fooRanges = r("1[0-2]xxxxx", "1[3-7][5-9]xxxx", "1[5-9][0-4]xxxx");
    assertThat(fooRanges).isEqualTo(p("1").retainFrom(totalRanges));

    // Bar is "everything in the total range starting with 2 or 3, and a 2nd digit 6 or 7".
    RangeTree barRanges = r("[23][5-7]xxxxx");
    assertThat(barRanges).isEqualTo(p("[23][5-7]").retainFrom(totalRanges));

    // Baz is everything else.
    RangeTree bazRanges = totalRanges.subtract(fooRanges).subtract(barRanges);

    RangeTree xxRanges = p("[12]").retainFrom(totalRanges);
    RangeTree yyRanges = p("[23]").retainFrom(totalRanges);

    RangeMap.Builder rangeMap = RangeMap.builder();
    rangeMap.put(
        TYPE,
        RangeClassifier.builder().setSingleValued(true)
            .put("FOO", fooRanges)
            .put("BAR", barRanges)
            .put("BAZ", bazRanges)
            .build());
    rangeMap.put(
        ClassifierType.REGION,
        RangeClassifier.builder().setSingleValued(false)
            .put("XX", xxRanges)
            .put("YY", yyRanges)
            .build());

    // Use high degree of simplification on 'totalRanges' to make this as simple as possible.
    RangeMap simplified = MetadataSimplifier.simplifyRangeMap(rangeMap.build(totalRanges), 100000, 1);
    assertThat(simplified.getAllRanges()).isEqualTo(r("[1-3]xxxxxx"));

    // Simplified ranges essentially have the same prefix as before (up to the point they are
    // unambiguous) and fill the simplified total range.
    assertThat(simplified.getClassifier(TYPE).getRanges("FOO")).isEqualTo(r("1xxxxxx"));
    assertThat(simplified.getClassifier(TYPE).getRanges("BAR")).isEqualTo(r("[23][5-7]xxxxx"));
    assertThat(simplified.getClassifier(TYPE).getRanges("BAZ")).isEqualTo(r("[23][0-489]xxxxx"));

    assertThat(simplified.getClassifier(REGION).getRanges("XX")).isEqualTo(r("[12]xxxxxx"));
    assertThat(simplified.getClassifier(REGION).getRanges("YY")).isEqualTo(r("[23]xxxxxx"));
  }

  static void assertSimplified(
      RangeTree ranges, int maxFalsePositivePercent, int minPrefixLength, String... expected) {
    assertThat(simplifyRange(ranges, maxFalsePositivePercent, minPrefixLength))
        .isEqualTo(r(expected));
  }

  static void assertUnsimplified(RangeTree r, int maxFalsePositivePercent, int minPrefixLength) {
    assertThat(simplifyRange(r, maxFalsePositivePercent, minPrefixLength)).isEqualTo(r);
  }

  private static PrefixTree p(String... specs) {
    return PrefixTree.from(r(specs));
  }

  private static RangeTree r(String... specs) {
    return Arrays.stream(specs)
        .map(RangeSpecification::parse)
        .map(RangeTree::from)
        .reduce(RangeTree.empty(), RangeTree::union);
  }
}
