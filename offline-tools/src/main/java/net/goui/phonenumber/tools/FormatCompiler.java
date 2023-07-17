package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec.FormatTemplate;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.function.BiConsumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.goui.phonenumber.proto.Metadata;

public class FormatCompiler {
  // Byte:
  // Literal (ASCII):   [ 0 x x x x x x x ] >= 0x20  --> individual separators + parens.
  //
  // Basic Group (Separator + Length):  [ 0 0 0 S S L L L ]
  // Length: 000 -> 1, 111 -> 8 (value + 1)
  // Sep:  0 -> Omitted,  1 -> Space,  2 -> Hyphen,  3 -> RESERVED
  //
  // Special Group (Type + Length):     [ 1 0 x T T L L L ]
  // Length: 000 -> 1, 111 -> 8 (value + 1)
  // Type: 0 -> Optional, 1 -> Parens, 2 -> Ignored, 3 -> RESERVED
  //
  // Special Tokens [ 1 1 x x x x x x ]:
  // CarrierCode    = 0xFD
  // NationalPrefix = 0xFE
  // <RESERVED>     = 0xFF
  //
  // XXX-...     -> Group(3, HYPHEN)
  // (XXX)...    -> Special(3, PARENS)
  // XX***-...   -> [ Group(2, OMITTED), Special(3, OPTIONAL), '-' ]  (could do better)
  // {XX>abc}... -> [ Special(2, IGNORE), 'a', 'b', 'c' ]
  // #...        -> NationalPrefix()
  // (#XXX)...   -> [ '(', NationalPrefix(), Group(3, NONE), ')' ]    (maybe could do better)
  // (XX @)-...   -> [ '(', Group(2, SPACE), CarrierCode(), ')' ]

  private static class SpecMatcher {
    private final Pattern pattern;
    private final BiConsumer<MatchResult, ByteArrayOutputStream> handler;

    SpecMatcher(String regex, BiConsumer<MatchResult, ByteArrayOutputStream> handler) {
      this.pattern = Pattern.compile(regex);
      this.handler = handler;
    }

    String apply(String spec, ByteArrayOutputStream out) {
      Matcher matcher = pattern.matcher(spec);
      if (!matcher.lookingAt()) {
        return null;
      }
      MatchResult r = matcher.toMatchResult();
      handler.accept(r, out);
      return spec.substring(r.end());
    }
  }

  private enum Separator {
    OMITTED(0),
    SPACE(1),
    HYPHEN(2);

    static Separator from(String s) {
      switch (s) {
        case "":
          return OMITTED;
        case " ":
          return SPACE;
        case "-":
          return HYPHEN;
        default:
          throw new AssertionError("unknown separator: " + s);
      }
    }

    private final int id;

    Separator(int id) {
      this.id = id;
    }

    int mask() {
      return id << 3;
    }
  }

  private static int group(MatchResult r) {
    int len = r.group(1).length() - 1;
    return len + Separator.from(r.group(2)).mask();
  }

  private enum SpecialType {
    OPTIONAL(0),
    PARENTHESIZED(1),
    IGNORED(2);

    private final int id;

    SpecialType(int id) {
      this.id = id;
    }

    int mask() {
      return id << 3;
    }
  }

  private static int special(MatchResult r, SpecialType type) {
    int len = r.group(1).length() - 1;
    return len + type.mask();
  }

  private enum SpecialToken {
    CARRIER_CODE(0xFD),
    NATIONAL_PREFIX(0xFE);

    static SpecialToken from(String s) {
      switch (s) {
        case "#":
          return NATIONAL_PREFIX;
        case "@":
          return CARRIER_CODE;
        default:
          throw new AssertionError("unknown special token: " + s);
      }
    }

    private final int value;

    SpecialToken(int value) {
      this.value = value;
    }

    int value() {
      return value;
    }
  }

  private static final ImmutableList<SpecMatcher> SPEC_MATCHERS =
      ImmutableList.of(
          new SpecMatcher("(X{1,8})([- ]?)", (r, out) -> out.write(group(r))),
          new SpecMatcher("(\\*{1,8})", (r, out) -> out.write(special(r, SpecialType.OPTIONAL))),
          new SpecMatcher(
              "\\((X{1,8})\\)", (r, out) -> out.write(special(r, SpecialType.PARENTHESIZED))),
          new SpecMatcher(
              "\\{(X{1,8})>([^}]*)}",
              (r, out) -> {
                out.write(special(r, SpecialType.IGNORED));
                r.group(2).codePoints().forEach(c -> out.write(checkAsciiPrintable(c)));
              }),
          new SpecMatcher("[#@]", (r, out) -> out.write(SpecialToken.from(r.group()).value())),
          new SpecMatcher("\\\\(.)", (r, out) -> out.write(checkAsciiPrintable(r.group(1).codePointAt(0)))),
          new SpecMatcher("[^#@X*{>}\\\\]", (r, out) -> out.write(checkAsciiPrintable(r.group().codePointAt(0)))),
          new SpecMatcher(".", (r, out) -> {
            throw new IllegalArgumentException("invalid format spec: " + r);
          }));

  private static int checkAsciiPrintable(int c) {
    checkState(0x20 <= c && c < 0x80);
    return c;
  }

  // (#XXX) XX**-XXX
  // XXX {XX>56}-XXX
  //
  // Separators -,_,/,0
  // Parens - maybe just separators, but very common.
  // Basic group (never longer than 6 outside of last group)
  // Group + Opt (2 groups?)
  // Group replacement (never opt, Argentina only atm) - super special case.
  // National prefix or carrier code (maybe in parens): (#XX)-... XX (@)...
  // Carrier code
  //
  //
  //
  // Separators: '-', ' ', <OPT>, '/', 0
  //
  public static String compileSpec(FormatTemplate format) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    String formatSpec = format.getSpecifier();
    while (!formatSpec.isEmpty()) {
      for (SpecMatcher m : SPEC_MATCHERS) {
        String s = m.apply(formatSpec, out);
        if (s != null) {
          formatSpec = s;
          break;
        }
      }
    }
    return Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray());
  }
}
