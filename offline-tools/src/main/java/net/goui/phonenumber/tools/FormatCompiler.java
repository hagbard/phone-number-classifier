package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec.FormatTemplate;
import java.util.function.BiConsumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FormatCompiler {
  // Bytes are all printable 7-bit ASCII (consumed directly in code, no Base64 etc.):
  //
  // Special Tokens [ 0 1 x x x x x ]:
  // '0' ... '9'              = 0x30 - 0x39
  // '(', ')', ' ', '-', '/'  = 0x28, 0x29, 0x20, 0x2D, 0x2F
  // CarrierCode              = 0x3E ('>')
  // Utf-8 Glyph Follows      = 0x3F ('?')
  //
  // Basic Group (Separator + Length):  [ 1 0 S S L L L ]
  // Length: 000 -> 1, 111 -> 8 (value + 1)
  // Sep:  0 -> Omitted,  1 -> Space,  2 -> Hyphen,  3 -> RESERVED
  //
  // Special Group (Type + Length):     [ 1 1 T T L L L ]
  // Length: 000 -> 1, 111 -> 8 (value + 1)
  // Type: 0 -> Optional, 1 -> Parens, 2 -> Ignored, 3 -> RESERVED
  //
  // XXX-...     -> Group(3, HYPHEN)
  // (XXX)...    -> Special(3, PARENS)
  // XX***-...   -> [ Group(2, OMITTED), Special(3, OPTIONAL), '-' ]  (could do better)
  // {XX>abc}... -> [ Special(2, IGNORE), 'a', 'b', 'c' ]
  // #...        -> NationalPrefix()
  // (#XXX)...   -> [ '(', NationalPrefix(), Group(3, NONE), ')' ]    (maybe could do better)
  // (XX @)-...   -> [ '(', Group(2, SPACE), CarrierCode(), ')' ]

  // Bare tokens MUST be in the range [0x20, 0x3F]. Anything else gets encoded via translation
  // (e.g. '@' -> '>') or after RAW_ASCII_BYTE.
  private static final String BARE_ASCII_TOKENS = "0123456789() -/.";
  private static final String NATIONAL_PREFIX_MARKER = "\u0000";
  private static final char CARRIER_CODE_MARKER = '>';
  private static final char RAW_ASCII_BYTE = 0x3F;

  private static class SpecMatcher {
    private final Pattern pattern;
    private final BiConsumer<MatchResult, StringBuilder> handler;

    SpecMatcher(String regex, BiConsumer<MatchResult, StringBuilder> handler) {
      this.pattern = Pattern.compile(regex);
      this.handler = handler;
    }

    String apply(String spec, StringBuilder out) {
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
      return 0x40 + (id << 3);
    }
  }

  private static char group(MatchResult r) {
    int len = r.group(1).length() - 1;
    return (char) (len + Separator.from(r.group(2)).mask());
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
      return 0x60 + (id << 3);
    }
  }

  private static char special(MatchResult r, SpecialType type) {
    int len = r.group(1).length() - 1;
    return (char) (len + type.mask());
  }

  private static void appendCodePoint(int cp, StringBuilder out) {
    if (BARE_ASCII_TOKENS.indexOf(cp) != -1) {
      out.append((char) cp);
    } else {
      out.append(RAW_ASCII_BYTE).append(checkAsciiPrintable(cp));
    }
  }

  private static final ImmutableList<SpecMatcher> SPEC_MATCHERS =
      ImmutableList.of(
          new SpecMatcher("(X{1,8})([- ]?)", (r, out) -> out.append(group(r))),
          new SpecMatcher("(\\*{1,8})", (r, out) -> out.append(special(r, SpecialType.OPTIONAL))),
          new SpecMatcher(
              "\\((X{1,8})\\)", (r, out) -> out.append(special(r, SpecialType.PARENTHESIZED))),
          new SpecMatcher(
              "\\{(X{1,8})>([^}]*)}",
              (r, out) -> {
                out.append(special(r, SpecialType.IGNORED));
                r.group(2).codePoints().forEach(c -> appendCodePoint(c, out));
              }),
          new SpecMatcher("[#]", (r, out) -> out.append(NATIONAL_PREFIX_MARKER)),
          new SpecMatcher("[@]", (r, out) -> out.append(CARRIER_CODE_MARKER)),
          new SpecMatcher("\\\\(.)", (r, out) -> appendCodePoint(r.group(1).codePointAt(0), out)),
          new SpecMatcher(
              "[^#@X*{>}\\\\]", (r, out) -> appendCodePoint(r.group().codePointAt(0), out)),
          new SpecMatcher(
              ".",
              (r, out) -> {
                throw new IllegalArgumentException("invalid format spec: " + r);
              }));

  private static char checkAsciiPrintable(int c) {
    checkState(0x20 <= c && c < 0x80);
    return (char) c;
  }

  public static String compileSpec(FormatTemplate format, DigitSequence nationalPrefix) {
    StringBuilder out = new StringBuilder();

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
    removeRedundantTrailingGroups(out);
    int np = out.indexOf(NATIONAL_PREFIX_MARKER);
    if (np >= 0) {
      checkArgument(!nationalPrefix.isEmpty(), "Expected national prefix for format: %s", format);
      out.replace(np, np + 1, nationalPrefix.toString());
    }
    return out.toString();
  }

  private static void removeRedundantTrailingGroups(StringBuilder spec) {
    if (spec.chars().anyMatch(c -> c == RAW_ASCII_BYTE)) {
      // An early exit in crazy rare cases to avoid needing to code for "raw" ascii which just
      // happens to match a metadata character. Just ignore these cases as there are so few.
      return;
    }
    int lastIdx = spec.length() - 1;
    // Exit if the spec was empty (since there's nothing to remove).
    if (lastIdx == -1) {
      return;
    }
    int lastChar = spec.charAt(lastIdx);
    // We want to find formats which end with either "XXX" or "XXX***" (which is most of them)
    // 0x78 == [0 1 1 1 1 0 0 0] masks out the group related bits.
    // 0x40 == [_ 1 0 0 0 _ _ _] indicates a "plain" group with no separator.
    // 0x60 == [_ 1 1 0 0 _ _ _] indicates an optional group.
    // Note however that you can get more than one optional group in a row.
    if (isOptionalGroup(lastChar)) {
      do {
        spec.setLength(lastIdx);
        lastChar = spec.charAt(--lastIdx);
      } while (isOptionalGroup(lastChar));
      checkArgument(isPlainGroup(lastChar), "expected preceding plain group: %s", spec);
      spec.setLength(lastIdx);
    } else if (isPlainGroup(lastChar) && spec.chars().noneMatch(FormatCompiler::isOptionalGroup)) {
      spec.setLength(lastIdx);
    }
  }

  private static boolean isPlainGroup(int c) {
    return (c & 0x78) == 0x40;
  }

  private static boolean isOptionalGroup(int c) {
    return (c & 0x78) == 0x60;
  }
}
