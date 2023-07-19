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

/**
 * Compiler to turn format specifiers into a compact sequence of ASCII bytes used in client code to
 * implement phone number formatting.
 */
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
  // Examples:
  // XXX-...     -> Group(3, HYPHEN)
  // (XXX)...    -> Special(3, PARENS)
  // XX***-...   -> [ Group(2, OMITTED), Special(3, OPTIONAL), '-' ]  (could do better)
  // {XX>abc}... -> [ Special(2, IGNORE), 'a', 'b', 'c' ]
  // #...        -> NationalPrefix()
  // (#XXX)...   -> [ '(', NationalPrefix(), Group(3, NONE), ')' ]    (maybe could do better)
  // (XX @)-...   -> [ '(', Group(2, SPACE), CarrierCode(), ')' ]

  // Bare tokens MUST be in the range [0x20, 0x3F]. Anything else gets encoded via translation
  // (e.g. '@' -> '>') or after RAW_ASCII_BYTE. Client code MUST NOT check the validity of
  // bare tokens (so that this range can be extended without a version change) but it's useful
  // to have a reserved range when creating the data.
  private static final String BARE_ASCII_TOKENS = "0123456789() -/.";
  // The next byte after this is a literal printable ASCII character (this is only used when a
  // separator or other format character is not in the BARE_ASCII_TOKENS range).
  private static final char RAW_ASCII_BYTE = 0x3F;

  // A special marker which is replaced after processing with the national prefix (this avoids
  // the client formatting code needing to know about national prefixes).
  private static final String NATIONAL_PREFIX_MARKER = "\u0000";
  // NOTE: Carrier codes are unused at the moment.
  private static final char CARRIER_CODE_MARKER = '>';

  static {
    checkState(
        BARE_ASCII_TOKENS.chars().allMatch(c -> 0x20 <= c && c <= 0x3F),
        "Invalid ASCII token(s): %s",
        BARE_ASCII_TOKENS);
  }

  /**
   * A function which attempts to match the next part of the format spec being processed and (if
   * successful) applies a transformation to it.
   */
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

  /**
   * An identifier applied to "plain" format groups to indicate the following separator character.
   * Use the {@link #group(MatchResult)} to make a separator group from a match result.
   */
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

  /**
   * Returns the byte representing a format group followed by the matched separator.
   *
   * <p>For example, the match result for {@code "XXXX-..."} will return the byte {@code
   * [00110011]}, where:
   *
   * <ol>
   *   <li>the top 3 bits indicate the group type ("001" is a separator group).
   *   <li>the next 2 bits indicate the separator ("10" == 2, is "hyphen").
   *   <li>the lowest 3 bits is the length - 1 ("011" == 3, so the length is 4).
   * </ol>
   */
  private static char group(MatchResult r) {
    int len = r.group(1).length() - 1;
    return (char) (len + Separator.from(r.group(2)).mask());
  }

  /**
   * An identifier applied to "special" format groups to indicate their behaviour. Use the {@link
   * #special(MatchResult, SpecialType)} to make a special group from a match result.
   */
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

  /**
   * Returns the byte representing a format group with special semantics.
   *
   * <p>For example, the match result for {@code "(XXXX)..."} will return the byte {@code
   * [01001011]}, where:
   *
   * <ol>
   *   <li>the top 3 bits indicate the group type ("010" is a special group).
   *   <li>the next 2 bits indicate the type ("01" == 1, is a parenthesized group).
   *   <li>the lowest 3 bits is the length - 1 ("011" == 3, so the length is 4).
   * </ol>
   */
  private static char special(MatchResult r, SpecialType type) {
    int len = r.group(1).length() - 1;
    return (char) (len + type.mask());
  }

  /**
   * Appends a given printable ASCII character, either as a "bare" token or prefixed with {@code
   * RAW_ASCII_BYTE}.
   */
  private static void appendAscii(int cp, StringBuilder out) {
    if (BARE_ASCII_TOKENS.indexOf(cp) != -1) {
      out.append((char) cp);
    } else {
      out.append(RAW_ASCII_BYTE).append(checkAsciiPrintable(cp));
    }
  }

  /**
   * Transformation functions applied in order to "compile" a format specifier string into a binary
   * format sequence for use by clients. Since every rule captures at least one character, applying
   * these functions repeatedly must terminate or fail.
   */
  private static final ImmutableList<SpecMatcher> SPEC_MATCHERS =
      ImmutableList.of(
          // Plain groups with an optional common separator.
          new SpecMatcher("(X{1,8})([- ]?)", (r, out) -> out.append(group(r))),
          // Optional groups.
          new SpecMatcher("(\\*{1,8})", (r, out) -> out.append(special(r, SpecialType.OPTIONAL))),
          // Parenthesised groups.
          new SpecMatcher(
              "\\((X{1,8})\\)", (r, out) -> out.append(special(r, SpecialType.PARENTHESIZED))),
          // Ignored groups (using syntax "{XX>abc}").
          new SpecMatcher(
              "\\{(X{1,8})>([^}]*)}",
              (r, out) -> {
                out.append(special(r, SpecialType.IGNORED));
                r.group(2).codePoints().forEach(c -> appendAscii(c, out));
              }),
          // National prefixes get replaced with their real values later.
          new SpecMatcher("[#]", (r, out) -> out.append(NATIONAL_PREFIX_MARKER)),
          // Carrier codes are not used by clients yet, but are compiled to a reserved byte.
          new SpecMatcher("[@]", (r, out) -> out.append(CARRIER_CODE_MARKER)),
          // Escaped text (via \x) is just emitted directly.
          new SpecMatcher("\\\\(.)", (r, out) -> appendAscii(r.group(1).codePointAt(0), out)),
          // Unescaped NON-special characters are just emitted directly.
          new SpecMatcher("[^#@X*{>}\\\\]", (r, out) -> appendAscii(r.group().codePointAt(0), out)),
          // Everything else is an error.
          new SpecMatcher(
              ".",
              (r, out) -> {
                throw new IllegalArgumentException("invalid format spec: " + r);
              }));

  private static char checkAsciiPrintable(int c) {
    checkState(0x20 <= c && c < 0x80);
    return (char) c;
  }

  /**
   * Compiles a format specifier string into a binary format sequence for use by clients.
   *
   * <p>We can trust the structure of the given {@link FormatTemplate} since that's carefully
   * checked, so we don't need to be as paranoid here.
   */
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

  /**
   * In client code, it's often not necessary to have a trailing format group (and omitting it saves
   * space).
   */
  private static void removeRedundantTrailingGroups(StringBuilder spec) {
    if (spec.chars().anyMatch(c -> c == RAW_ASCII_BYTE)) {
      // An early exit in crazy rare cases to avoid needing to code for "raw" ascii which just
      // happens to match a metadata character. Just ignore these cases as there are usually none.
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
      // Removes trailing XXX*** groups (even if they are made up from several bytes).
      do {
        spec.setLength(lastIdx);
        lastChar = spec.charAt(--lastIdx);
      } while (isOptionalGroup(lastChar));
      checkArgument(isPlainGroup(lastChar), "expected preceding plain group: %s", spec);
      removeTrailingPlainGroup(spec, lastIdx);
    } else if (isPlainGroup(lastChar) && spec.chars().noneMatch(FormatCompiler::isOptionalGroup)) {
      removeTrailingPlainGroup(spec, lastIdx);
    }
  }

  // Removes trailing plain XXXX group (even if it is made up from several bytes).
  private static void removeTrailingPlainGroup(StringBuilder spec, int lastIdx) {
    do {
      spec.setLength(lastIdx);
    } while (lastIdx > 0 && isPlainGroup(spec.charAt(--lastIdx)));
  }

  private static boolean isPlainGroup(int c) {
    return (c & 0x78) == 0x40;
  }

  private static boolean isOptionalGroup(int c) {
    return (c & 0x78) == 0x60;
  }
}
