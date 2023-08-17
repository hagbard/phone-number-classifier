package net.goui.phonenumber;

import static com.google.common.base.Preconditions.*;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static net.goui.phonenumber.LengthResult.POSSIBLE;
import static net.goui.phonenumber.LengthResult.TOO_LONG;
import static net.goui.phonenumber.MatchResult.INVALID;
import static net.goui.phonenumber.MatchResult.MATCHED;
import static net.goui.phonenumber.PhoneNumberResult.ParseFormat.INTERNATIONAL;
import static net.goui.phonenumber.PhoneNumberResult.ParseFormat.NATIONAL;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.goui.phonenumber.PhoneNumberResult.ParseFormat;
import net.goui.phonenumber.metadata.ParserData;
import net.goui.phonenumber.metadata.RawClassifier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * /** Phone number parsing API, available from subclasses of {@link AbstractPhoneNumberClassifier}
 * when parser metadata is available.
 *
 * <p><em>Important</em>: This parser is deliberately simpler than the corresponding parser in
 * Google's Libphonenumber library. It is designed to be a robust parser for cases where the input
 * is a formatted phone number, but it will not handle all the edge cases that Libphonenumber can
 * (e.g. parsing U.S. vanity numbers such as "1-800-BIG-DUCK").
 *
 * <p>However, when given normally formatted national/international phone number text, this parser
 * produces exactly the same, or better results than Libphonenumber for valid/supported ranges.
 *
 * <p>If a calling code/region is supported in the metadata for the parser, then numbers are
 * validated with/without national prefixes, and the best match is chosen. Examples of input which
 * can be parsed:
 *
 * <ul>
 *   <li>{@code "056/090 93 19"} (SK)
 *   <li>{@code "+687 71.49.28"} (NC)
 *   <li>{@code "(8108) 6309 390 906"} (RU/KZ)
 * </ul>
 *
 * <p>In the final example, note that the national is given without the (optional) national prefix,
 * which is also '8'. In this case <a href="https://issuetracker.google.com/issues/295677348">
 * Libphonenumber gets confused and mis-parses the number</a>, whereas this parser will correctly
 * handle it.
 *
 * <p>If a calling code/region is not supported in the metadata for the parser, then only
 * internationally formatted numbers (with a leading '+' and country calling code) can be parsed,
 * and no validation is performed (the match result is always `Invalid`).
 *
 * <p>As a special case, Argentinian national mobile numbers formatted with the mobile token '15'
 * after the area code (e.g. "0 11 15-3329-5195") will be transformed to use the international
 * mobile token prefix '9' (e.g. +54 9 11 3329-5195). This results in 11-digit mobile numbers in
 * Argentina, which is not strictly correct (the leading '9' is not part of the national number) but
 * it is the only reasonable way to preserve the distinction between mobile and fixed-line numbers
 * when the numbers are subsequently formatted again.
 */
public final class PhoneNumberParser<T> {
  private static final CharMatcher ASCII_DIGIT = CharMatcher.inRange('0', '9');
  private static final CharMatcher WIDE_DIGIT = CharMatcher.inRange('０', '９');
  private static final CharMatcher ANY_DIGIT = ASCII_DIGIT.or(WIDE_DIGIT);
  // This must include every character in any format specifier.
  private static final CharMatcher GROUPING_SEPARATORS =
      CharMatcher.anyOf(
          "-\uFF0D\u2010\u2011\u2012\u2013\u2014\u2015\u2212"
              + "/\uFF0F\u3000\u2060"
              + ".\uFF0E"
              + "(\uFF08\u2768"
              + ")\uFF09\u2769");
  private static final CharMatcher ALLOWED_CHARS =
      CharMatcher.whitespace().or(ANY_DIGIT).or(GROUPING_SEPARATORS).or(CharMatcher.is('+'));

  private static final DigitSequence CC_ARGENTINA = DigitSequence.parse("54");
  private static final Pattern ARGENTINA_MOBILE_PREFIX = Pattern.compile("0?(.{2,4})15(.{6,8})");

  private final RawClassifier rawClassifier;
  private final ImmutableListMultimap<DigitSequence, T> regionCodeMap;
  private final ImmutableMap<T, DigitSequence> callingCodeMap;
  private final ImmutableSetMultimap<DigitSequence, DigitSequence> nationalPrefixMap;
  private final ImmutableSet<DigitSequence> nationalPrefixOptional;

  PhoneNumberParser(RawClassifier rawClassifier, Function<String, T> converter) {
    this.rawClassifier = rawClassifier;
    T worldRegion = checkNotNull(converter.apply("001"));
    ImmutableListMultimap.Builder<DigitSequence, T> regionCodeMap = ImmutableListMultimap.builder();
    ImmutableMap.Builder<T, DigitSequence> callingCodeMap = ImmutableMap.builder();
    ImmutableSetMultimap.Builder<DigitSequence, DigitSequence> nationalPrefixMap =
        ImmutableSetMultimap.builder();
    ImmutableSet.Builder<DigitSequence> nationalPrefixOptional = ImmutableSet.builder();
    for (DigitSequence cc : rawClassifier.getSupportedCallingCodes()) {
      ParserData parserData = rawClassifier.getParserData(cc);
      checkState(parserData != null, "Parser data unavailable for: " + cc);
      ImmutableSet<T> regions =
          parserData.getRegions().stream().map(converter).collect(toImmutableSet());

      // Region 001 is treated specially since it's the only region with more than one calling code,
      // so it cannot be put into the calling code map. It cannot also appear with other regions.
      // region codes.
      if (!regions.contains(worldRegion)) {
        regions.forEach(r -> callingCodeMap.put(r, cc));
      } else if (regions.size() > 1) {
        throw new Error("Region 001 must never appear with other region codes: " + regions);
      }
      regionCodeMap.putAll(cc, regions);

      if (!parserData.getNationalPrefixes().isEmpty()) {
        nationalPrefixMap.putAll(cc, parserData.getNationalPrefixes());
        if (parserData.isNationalPrefixOptional()) {
          nationalPrefixOptional.add(cc);
        }
      }
    }
    this.regionCodeMap = regionCodeMap.build();
    this.callingCodeMap = callingCodeMap.buildOrThrow();
    this.nationalPrefixMap = nationalPrefixMap.build();
    this.nationalPrefixOptional = nationalPrefixOptional.build();
  }

  /**
   * Returns a sorted list of CLDR region codes for the given country calling code.
   *
   * <p>This list is sorted alphabetically, except the first element, which is always the "main
   * region" associated with the calling code (e.g. "US" for "1", "GB" for "44").
   *
   * <p>em>NOTE</em>: There are some calling codes not associated with any region code (e.g. {@code
   * 888} and {@code 979}). In the original Libphonenumber metadata these are associated with the
   * special "world" region {@code "001"}. This is the only case where a returned region code is not
   * one of the "normal" 2-letter codes.
   *
   * <p>If the given calling code is not supported, an empty list is returned.
   */
  public ImmutableList<T> getRegions(DigitSequence callingCode) {
    return regionCodeMap.get(callingCode);
  }

  /**
   * Returns the unique country calling code for the specified CLDR region code.
   *
   * <p><em>NOTE</em>: There are some calling codes not associated with any region code (e.g. {@code
   * 888} and {@code 979}). In the original Libphonenumber metadata these are associated with the
   * special "world" region {@code "001"}. Unfortunately this results in the "001" region not having
   * a unique country calling code. As such, this method does not accept the "001" region as input,
   * even though {@code getRegions(DigitSequence.parse("888"))} will return it.
   *
   * <p>If the given region code is not supported, {@link Optional#empty()} is returned.
   */
  public Optional<DigitSequence> getCallingCode(T region) {
    return Optional.ofNullable(callingCodeMap.get(region));
  }

  public Optional<PhoneNumber> parseLeniently(String text) {
    return parseLeniently(text, (DigitSequence) null);
  }

  public Optional<PhoneNumber> parseLeniently(String text, T region) {
    Optional<DigitSequence> callingCode = getCallingCode(region);
    checkArgument(callingCode.isPresent(), "Unknown region code: %s", region);
    return parseLeniently(text, callingCode.get());
  }

  public Optional<PhoneNumber> parseLeniently(String text, @Nullable DigitSequence callingCode) {
    return Optional.ofNullable(parseImpl(text, callingCode)).map(PhoneNumberResult::getPhoneNumber);
  }

  public PhoneNumberResult<T> parseStrictly(String text) {
    return parseStrictly(text, (DigitSequence) null);
  }

  public PhoneNumberResult<T> parseStrictly(String text, T region) {
    Optional<DigitSequence> callingCode = getCallingCode(region);
    checkArgument(callingCode.isPresent(), "Unknown region code: %s", region);
    return parseStrictly(text, callingCode.get());
  }

  public PhoneNumberResult<T> parseStrictly(String text, @Nullable DigitSequence callingCode) {
    PhoneNumberResult<T> result = parseImpl(text, callingCode);
    checkArgument(result != null, "Cannot parse phone number text '%s'", text);
    return result;
  }

  /*
   * The state table for how return values are calculated for parsing.
   *
   * The algorithm tries to parse the input assuming both "national" and "international"
   * formatting of the given text.
   *    * For national format, the given calling code is used ("NATIONAL").
   *    * For international format, the calling code is extracted from the number ("INTL").
   * 1. If neither result can be obtained, parsing fails
   * 2. If only one result can be obtained, it is returned
   * 3. If the national result is "better" than the international one, return the national result.
   * 4. In the remaining cases, return the international if either:
   *    * The extracted calling code was the same as the given calling code: ("41 xxxx xxxx", cc=41)
   *    * If the input text is internationally formatted: e.g. ("+41 xxxx xxxx", cc=34)
   * 5. Otherwise return the national format.
   *
   * Note: Step 4 is only reached if the international parse result is a better match than the
   * national one, and even then we might return the national result if we aren't sure the extracted
   * calling code looks trustworthy. It also only occurs if the extracted calling code is supported,
   * so we can classify the candidate numbers to show they are "better" than the national result..
   *
   * National   /-------------------------- International Result ---------------------------\
   *  Result  || MATCHED    | PARTIAL    | EXCESS     | LENGTH     | INVALID    |  N/A
   * =========||==============================================================================
   *  MATCHED || NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   |
   * ---------||-=--=--=--=-+------------+------------+------------+------------+------------+
   *  PARTIAL || CHECK-CC   | NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   |
   * ---------||------------+-=--=--=--=-+------------+------------+------------+------------+
   *  EXCESS  || CHECK-CC   | CHECK-CC   | NATIONAL   | NATIONAL   | NATIONAL   | NATIONAL   |
   * ---------||------------+------------+-=--=--=--=-+------------+------------+------------+
   *  LENGTH  || CHECK-CC   | CHECK-CC   | CHECK-CC   | NATIONAL   | NATIONAL   | NATIONAL   |
   * ---------||------------+------------+------------+-=--=--=--=-+------------+------------+
   *  INVALID || CHECK-CC   | CHECK-CC   | CHECK-CC   | CHECK-CC   | NATIONAL   | NATIONAL   |
   * ---------||------------+------------+------------+------------+-=--=--=--=-+------------+
   *   N/A    || INTL (2)   | INTL (2)   | INTL (2)   | INTL (2)   | INTL (2)   | ---(1)---  |
   * ---------||------------+------------+------------+------------+------------+------------+
   */
  @Nullable
  private PhoneNumberResult<T> parseImpl(String text, @Nullable DigitSequence callingCode) {
    if (!ALLOWED_CHARS.matchesAllOf(text)) {
      return null;
    }
    // Should always succeed even if result is empty.
    String digitText = removeNonDigitsAndNormalizeToAscii(text);
    if (digitText.isEmpty()) {
      return null;
    }
    DigitSequence digits = DigitSequence.parse(digitText);
    DigitSequence extractedCc = PhoneNumbers.extractCallingCode(digitText);
    PhoneNumberResult<T> nationalParseResult =
        callingCode != null ? getBestResult(callingCode, digits, NATIONAL) : null;
    if (extractedCc == null) {
      // This accounts for (1.A) no results possible and (2.B) only the national result.
      return nationalParseResult;
    }
    PhoneNumberResult<T> internationalParseResult =
        getBestResult(extractedCc, removePrefix(digits, extractedCc.length()), INTERNATIONAL);
    if (nationalParseResult == null) {
      // This accounts for (2.C) only the international result.
      return internationalParseResult;
    }
    if (nationalParseResult.isBetterThan(internationalParseResult)) {
      // This accounts for (3)
      return nationalParseResult;
    }
    return (callingCode.equals(extractedCc) || looksLikeInternationalFormat(text, extractedCc))
        ? internationalParseResult
        : nationalParseResult;
  }

  // This is true for things like "+1234", "(+12) 34" but NOT "+ 12 34", "++1234" or "+1234+".
  private static boolean looksLikeInternationalFormat(String text, DigitSequence cc) {
    int firstDigit = ANY_DIGIT.indexIn(text);
    return firstDigit > 0
        && text.charAt(firstDigit - 1) == '+'
        && text.indexOf('+', firstDigit) == -1
        && text.regionMatches(firstDigit, cc.toString(), 0, cc.length());
  }

  private PhoneNumberResult<T> getBestResult(
      DigitSequence cc, DigitSequence nn, ParseFormat formatType) {
    if (cc.equals(CC_ARGENTINA)) {
      nn = maybeAdjustArgentineFixedLineNumber(cc, nn);
    }
    if (!rawClassifier.getSupportedCallingCodes().contains(cc)) {
      return PhoneNumberResult.of(PhoneNumbers.create(cc, nn), INVALID, formatType);
    }
    ImmutableSet<DigitSequence> nationalPrefixes = nationalPrefixMap.get(cc);
    boolean requiresNationalPrefix =
        formatType == NATIONAL
            && !nationalPrefixes.isEmpty()
            && !nationalPrefixOptional.contains(cc);
    MatchResult bestResult = requiresNationalPrefix ? INVALID : rawClassifier.match(cc, nn);
    DigitSequence bestNumber = nn;
    if (bestResult != MATCHED) {
      for (DigitSequence np : nationalPrefixes) {
        if (startsWith(np, nn)) {
          DigitSequence candidateNumber = removePrefix(nn, np.length());
          MatchResult candidateResult = rawClassifier.match(cc, candidateNumber);
          if (candidateResult.isBetterThan(bestResult)) {
            bestNumber = candidateNumber;
            bestResult = candidateResult;
            if (bestResult == MATCHED) {
              break;
            }
          }
        }
      }
    }
    return PhoneNumberResult.of(PhoneNumbers.create(cc, bestNumber), bestResult, formatType);
  }

  private DigitSequence maybeAdjustArgentineFixedLineNumber(DigitSequence cc, DigitSequence nn) {
    if (rawClassifier.testLength(cc, nn) == TOO_LONG) {
      Matcher m = ARGENTINA_MOBILE_PREFIX.matcher(nn.toString());
      if (m.matches()) {
        DigitSequence candidate = DigitSequence.parse("9" + m.group(1) + m.group(2));
        if (rawClassifier.testLength(cc, candidate) == POSSIBLE) {
          return candidate;
        }
      }
    }
    return nn;
  }

  private static boolean startsWith(DigitSequence prefix, DigitSequence seq) {
    return prefix.length() <= seq.length() && seq.getPrefix(prefix.length()).equals(prefix);
  }

  private static DigitSequence removePrefix(DigitSequence seq, int length) {
    return seq.getSuffix(seq.length() - length);
  }

  private static String removeNonDigitsAndNormalizeToAscii(String s) {
    if (ASCII_DIGIT.matchesAllOf(s)) {
      return s;
    }
    StringBuilder b = new StringBuilder();
    ANY_DIGIT
        .retainFrom(s)
        .codePoints()
        .map(PhoneNumberParser::normalizeToAscii)
        .forEach(b::appendCodePoint);
    return b.toString();
  }

  private static int normalizeToAscii(int cp) {
    // Assume cp already matches ANY_DIGIT.
    return WIDE_DIGIT.matches((char) cp) ? ('0' + (cp - '０')) : cp;
  }
}
