/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.truth.Truth.assertThat;
import static net.goui.phonenumber.tools.ClassifierType.TYPE;
import static net.goui.phonenumber.tools.ClassifierType.VALIDITY;

import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.i18n.PhoneRegion;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.util.Arrays;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GenerateMetadataTest {
  // This is an important test because it shows that simplification cannot reintroduce ranges
  // which are excluded in `validation_ranges` expression. If simplification naively reset the
  // validation range before simplification, this test would fail.
  @Test
  public void testSimplificationCannotOverwriteRemovedValues() throws ParseException {
    // Carefully designed ranges so that once BAR is removed, FOO and BAZ would "fill the gap" if
    // simplification were applied naively. Each range has 2 parts:
    // 1: "Nx[0-4]xxx" - this will simplify to "Nxxxxx" and each value has a different prefix.
    // 2: "01[...]xxx" and "02[...]xxx" - these ranges are mixed between all values and require a
    //    prefix of length 3 to disambiguate properly.
    // If we just removed BAR before simplifying, the 2nd ranges of FOO and BAZ would simplify
    // such that they overlap where BAR's ranges were (which would be a problem).
    RangeTree originalFoo = r("1x[0-4]xxx", "01[0-2]xxx");
    RangeTree originalBar = r("2x[0-4]xxx", "01[7-9]xxx", "02[0-2]xxx");
    RangeTree originalBaz = r("3x[0-4]xxx", "02[7-9]xxx");
    RangeTree allRanges = originalFoo.union(originalBar).union(originalBaz);

    RangeClassifier classifier =
        RangeClassifier.builder().setSingleValued(true)
            .put("FOO", originalFoo)
            .put("BAR", originalBar)
            .put("BAZ", originalBaz)
            .build();
    RangeMap original = RangeMap.builder().put(TYPE, classifier).build(allRanges);

    assertThat(original.getTypes()).containsExactly(TYPE);
    assertThat(original.getClassifier(TYPE).getRanges("FOO")).isEqualTo(originalFoo);
    assertThat(original.getClassifier(TYPE).getRanges("BAR")).isEqualTo(originalBar);
    assertThat(original.getClassifier(TYPE).getRanges("BAZ")).isEqualTo(originalBaz);

    // ----------------------------------------
    // This transforms the original range map and adds a new (synthetic) validity classifier based
    // on the `validation_ranges` expression.
    MetadataConfigProto config =
        config(
            "validation_ranges: 'ALL:RANGES - TYPE:BAR'",
            "classifier {",
            "  type_name: 'TYPE'",
            "}");
    RangeMap transformed = RangeMapTransformer.from(config).apply(original);

    assertThat(transformed.getTypes()).containsExactly(TYPE, VALIDITY);
    assertThat(transformed.getClassifier(TYPE)).isEqualTo(original.getClassifier(TYPE));

    RangeClassifier validityClassifier = transformed.getClassifier(VALIDITY);
    assertThat(validityClassifier.orderedKeys()).containsExactly("VALID", "INVALID");
    assertThat(validityClassifier.getRanges("VALID")).isEqualTo(originalFoo.union(originalBaz));
    assertThat(validityClassifier.getRanges("INVALID")).isEqualTo(originalBar);

    // ----------------------------------------
    // This will simplify the first part of each range "Nx[0-4]xxx" --> "Nxxxxx" but cannot
    // simplify the 2nd part since the prefix for it cannot be shortened.
    RangeMap simplified = MetadataSimplifier.simplifyRangeMap(transformed, 100000, 2);

    // The simplified range map still contains BAZ, but with its simplified ranges.
    // It also contains a simplified version of the validity classifier.
    assertThat(simplified.getTypes()).containsExactly(TYPE, VALIDITY);
    assertThat(simplified.getClassifier(TYPE).orderedKeys()).containsExactly("FOO", "BAR", "BAZ");

    RangeTree simplifiedFoo = r("1xxxxx", "01[0-2]xxx");
    RangeTree simplifiedBar = r("2xxxxx", "01[7-9]xxx", "02[0-2]xxx");
    RangeTree simplifiedBaz = r("3xxxxx", "02[7-9]xxx");

    assertThat(simplified.getClassifier(TYPE).getRanges("FOO")).isEqualTo(simplifiedFoo);
    assertThat(simplified.getClassifier(TYPE).getRanges("BAR")).isEqualTo(simplifiedBar);
    assertThat(simplified.getClassifier(TYPE).getRanges("BAZ")).isEqualTo(simplifiedBaz);
    assertThat(simplified.getAllRanges())
        .isEqualTo(simplifiedFoo.union(simplifiedBar).union(simplifiedBaz));

    // Now (finally) we can trim the map to the ranges in the validity classifier. In this example
    // it is the same as removing the `simplifiedBar` ranges (NOT `originalBar`).
    RangeMap trimmed = simplified.trimValidRanges();

    // No more validity classifier.
    assertThat(trimmed.getTypes()).containsExactly(TYPE);
    // Reduced validation range.
    assertThat(trimmed.getAllRanges()).isEqualTo(simplifiedFoo.union(simplifiedBaz));
    // No more "BAR" values and no overlap with values that were removed.
    assertThat(trimmed.getClassifier(TYPE).orderedKeys()).containsExactly("FOO", "BAZ");
    assertThat(trimmed.getAllRanges().intersect(originalBar)).isEqualTo(RangeTree.empty());
  }

  private static MetadataConfigProto config(String... lines) throws ParseException {
    String config = String.join("\n", lines);
    return TextFormat.parse(config, MetadataConfigProto.class);
  }

  private static RangeTree r(String... specs) {
    return Arrays.stream(specs)
        .map(RangeSpecification::parse)
        .map(RangeTree::from)
        .reduce(RangeTree.empty(), RangeTree::union);
  }
}
