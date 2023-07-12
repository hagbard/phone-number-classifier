/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import com.google.common.base.CharMatcher;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.goui.phonenumber.tools.Metadata.RangeMap;

abstract class RangeExpression implements Function<RangeMap, RangeTree> {
  // Label for special expression literal which takes the value of all valid ranges in the range map.
  private static final String ALL_RANGES_LABEL = "ALL:RANGES";

  public static RangeExpression parse(String expression) {
    return new Parser(expression).parse();
  }

  private static class Parser {
    private static final Pattern TOKEN = Pattern.compile("([A-Z_]+):([A-Z_]+)");
    private static final CharMatcher NOT_WS = CharMatcher.whitespace().negate();
    private static final RangeExpression ALL_RANGES = new AllRanges();

    private final String expression;
    private int pos = 0;

    private Parser(String expression) {
      this.expression = expression;
    }

    RangeExpression parse() {
      RangeExpression e = nextExpression();
      syntaxCheck(pos == -1, "unexpected trailing characters");
      return e;
    }

    private void syntaxCheck(boolean condition, String message) {
      if (!condition) {
        int offset = pos >= 0 ? pos : expression.length();
        throw new IllegalArgumentException(
            String.format(
                "Syntax error: '%s' at offset %d of expression: %s", message, offset, expression));
      }
    }

    private int peekNextChar() {
      if (pos >= 0) {
        pos = NOT_WS.indexIn(expression, pos);
        if (pos >= 0) {
          return expression.charAt(pos);
        }
      }
      return -1;
    }

    private RangeExpression currentExpression() {
      int c = peekNextChar();
      syntaxCheck(c >= 0, "unexpected end of expression");
      return (c == '(') ? recurse() : nextLiteral();
    }

    private RangeExpression nextExpression() {
      RangeExpression cur = currentExpression();
      while (true) {
        int c = peekNextChar();
        Optional<BinaryOp> op = BinaryOp.lookup(c);
        if (op.isEmpty()) {
          // End of string or closing parenthesis.
          return cur;
        }
        pos += 1;
        cur = op.get().toExpression(cur, currentExpression());
      }
    }

    private RangeExpression recurse() {
      pos += 1;
      RangeExpression e = nextExpression();
      syntaxCheck(peekNextChar() == ')', "expected closing ')'");
      pos += 1;
      return e;
    }

    private RangeExpression nextLiteral() {
      Matcher m = TOKEN.matcher(expression.subSequence(pos, expression.length()));
      syntaxCheck(m.lookingAt(), "invalid range identifier");
      pos += m.end();
      if (m.group().equals(ALL_RANGES_LABEL)) {
        return ALL_RANGES;
      } else {
        return new Literal(ClassifierType.of(m.group(1)), m.group(2));
      }
    }
  }

  private enum BinaryOp {
    UNION(RangeTree::union, "+"),
    INTERSECT(RangeTree::intersect, "^"),
    SUBTRACT(RangeTree::subtract, "-");

    static Optional<BinaryOp> lookup(int symbol) {
      switch (symbol) {
        case '+':
          return Optional.of(UNION);
        case '^':
          return Optional.of(INTERSECT);
        case '-':
          return Optional.of(SUBTRACT);
        default:
          return Optional.empty();
      }
    }

    private final BinaryOperator<RangeTree> operator;
    private final String symbol;

    BinaryOp(BinaryOperator<RangeTree> operator, String symbol) {
      this.operator = operator;
      this.symbol = symbol;
    }

    BinaryExpression toExpression(RangeExpression lhs, RangeExpression rhs) {
      return new BinaryExpression(lhs, rhs, operator, symbol);
    }
  }

  private static final class AllRanges extends RangeExpression {
    @Override
    public RangeTree apply(RangeMap rangeMap) {
      return rangeMap.getAllRanges();
    }

    @Override
    public String toString() {
      return ALL_RANGES_LABEL;
    }
  }

  private static final class Literal extends RangeExpression {
    private final ClassifierType type;
    private final String key;

    private Literal(ClassifierType type, String key) {
      this.type = type;
      this.key = key;
    }

    @Override
    public RangeTree apply(RangeMap rangeMap) {
      return rangeMap.getRanges(type, key);
    }

    @Override
    public String toString() {
      return String.format("%s:%s", type.id(), key);
    }
  }

  private static final class BinaryExpression extends RangeExpression {
    private final RangeExpression lhs;
    private final RangeExpression rhs;
    private final BinaryOperator<RangeTree> operator;
    private final String symbol;

    private BinaryExpression(
        RangeExpression lhs,
        RangeExpression rhs,
        BinaryOperator<RangeTree> operator,
        String symbol) {
      this.lhs = lhs;
      this.rhs = rhs;
      this.operator = operator;
      this.symbol = symbol;
    }

    @Override
    public RangeTree apply(RangeMap rangeMap) {
      return operator.apply(lhs.apply(rangeMap), rhs.apply(rangeMap));
    }

    @Override
    public String toString() {
      return String.format("(%s %s %s)", lhs, symbol, rhs);
    }
  }
}
