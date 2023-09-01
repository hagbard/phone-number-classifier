/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.tools;

import static net.goui.phonenumbers.tools.ClassifierType.TYPE;

import com.google.common.truth.Truth;
import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RangeExpressionTest {
  private static final RangeClassifier TYPES =
      RangeClassifier.builder().setSingleValued(true)
          .put("FOO", r("[0145]xxxxx"))
          .put("BAR", r("[0246]xxxxx"))
          .put("BAZ", r("[0356]xxxxx"))
          .build();

  private static final RangeMap MAP = RangeMap.builder().put(TYPE, TYPES).build(r("[0-6]xxxxx"));

  @Test
  public void testLiteral() {
    assertExpression("TYPE:FOO", "[0145]xxxxx");
    assertExpression("TYPE:BAR", "[0246]xxxxx");
    assertExpression("ALL:RANGES", "[0-6]xxxxx");
    // Just ensure parentheses aren't an issue.
    assertExpression(" ( TYPE:FOO ) ", "[0145]xxxxx");
    assertExpression(" ( ( TYPE:FOO ) ) ", "[0145]xxxxx");
  }

  @Test
  public void testOperations() {
    assertExpression("TYPE:FOO + TYPE:BAR", "[0-24-6]xxxxx");
    assertExpression("TYPE:FOO ^ TYPE:BAR", "[04]xxxxx");
    assertExpression("TYPE:FOO - TYPE:BAR", "[15]xxxxx");
  }

  @Test
  public void testIsLeftAssociative() {
    assertExpression("TYPE:FOO - TYPE:BAR - TYPE:BAZ", "1xxxxx");
    assertExpression("TYPE:FOO - ( TYPE:BAR - TYPE:BAZ )", "[015]xxxxx");
  }

  @Test
  public void testErrors() {
    assertError("", "unexpected end of expression", "offset 0");
    assertError("FOO", "invalid range identifier", "offset 0");
    assertError("TYPE:FOO:BAR", "unexpected trailing characters", "offset 8");
    assertError("TYPE:FOO, TYPE:BAR", "unexpected trailing characters", "offset 8");
    assertError("TYPE:FOO +- TYPE:BAR", "invalid range identifier", "offset 10");
    assertError("(TYPE:FOO + TYPE:BAR", "expected closing ')'", "offset 20");
    assertError("TYPE:FOO + TYPE:BAR)", "unexpected trailing characters", "offset 19");
  }

  private static void assertExpression(String expression, String... specs) {
    Truth.assertThat(RangeExpression.parse(expression).apply(MAP)).isEqualTo(r(specs));
  }

  private static void assertError(String expression, String... messages) {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class, () -> RangeExpression.parse(expression));
    for (String m : messages) {
      Truth.assertThat(e).hasMessageThat().contains(m);
    }
  }

  private static RangeTree r(String... specs) {
    return Arrays.stream(specs)
        .map(RangeSpecification::parse)
        .map(RangeTree::from)
        .reduce(RangeTree.empty(), RangeTree::union);
  }
}
