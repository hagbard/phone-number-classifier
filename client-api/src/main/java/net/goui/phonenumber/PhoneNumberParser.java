package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static net.goui.phonenumber.LengthResult.POSSIBLE;
import static net.goui.phonenumber.LengthResult.TOO_LONG;
import static net.goui.phonenumber.MatchResult.INVALID;
import static net.goui.phonenumber.MatchResult.MATCHED;

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
    return parseImpl(text, null).map(PhoneNumberResult::getPhoneNumber);
  }

  public Optional<PhoneNumber> parseLeniently(String text, T region) {
    return parseImpl(text, region).map(PhoneNumberResult::getPhoneNumber);
  }

  public PhoneNumberResult parseStrictly(String text) {
    return parseImpl(text, null)
        .orElseThrow(
            () -> new IllegalArgumentException("Cannot parse phone number text '" + text + "'"));
  }

  public PhoneNumberResult parseStrictly(String text, T region) {
    return parseImpl(text, region)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Cannot parse phone number text '" + text + "' in region '" + region + "'"));
  }

  private Optional<PhoneNumberResult> parseImpl(String text, @Nullable T region) {
    if (!ALLOWED_CHARS.matchesAllOf(text)) {
      return Optional.empty();
    }
    // Should always succeed even if result is empty.
    String digitText = removeNonDigitsAndNormalizeToAscii(text);
    if (digitText.isEmpty()) {
      return Optional.empty();
    }
    // Heuristic to look for things that are more likely to be attempts are writing an E.164 number.
    // This is true for things like "+1234", "(+12) 34" but NOT "+ 12 34", "++1234" or "+1234+"
    // If this is true, we try to extract a calling code from the number *before* using the given
    // region.
    int plusIndex = text.indexOf('+');
    boolean looksLikeE164 =
        plusIndex >= 0
            && ANY_DIGIT.indexIn(text) == plusIndex + 1
            && plusIndex == text.lastIndexOf('+');

    DigitSequence originalNumber = DigitSequence.parse(digitText);
    // Null if the region is not supported.
    DigitSequence providedCc = callingCodeMap.get(region);
    // We can extract all possible calling codes regardless of whether they are supported, but we
    // only want to attempt to classify supported calling codes.
    DigitSequence extractedCc = PhoneNumbers.extractCallingCode(digitText);
    boolean extractedCcIsSupported =
        extractedCc != null && rawClassifier.getSupportedCallingCodes().contains(extractedCc);
    PhoneNumberResult bestResult = null;
    if (providedCc != null && !looksLikeE164) {
      bestResult = getBestParseResult(providedCc, originalNumber);
      if (bestResult.getResult() != MATCHED && extractedCcIsSupported) {
        PhoneNumberResult result =
            getBestParseResult(extractedCc, removePrefix(originalNumber, extractedCc.length()));
        bestResult = bestOf(bestResult, result);
      }
    } else if (extractedCcIsSupported) {
      bestResult =
          getBestParseResult(extractedCc, removePrefix(originalNumber, extractedCc.length()));
      if (bestResult.getResult() != MATCHED && providedCc != null) {
        bestResult = bestOf(bestResult, getBestParseResult(providedCc, originalNumber));
      }
    }
    // Fallback for cases where the calling code isn't supported in the metadata, but we can
    // still make a best guess at an E164 number without any validation.
    if (bestResult == null && looksLikeE164 && extractedCc != null) {
      bestResult = PhoneNumberResult.of(PhoneNumbers.fromE164(digitText), INVALID);
    }
    return Optional.ofNullable(bestResult);
  }

  private PhoneNumberResult getBestParseResult(DigitSequence cc, DigitSequence nn) {
    if (cc.equals(CC_ARGENTINA)) {
      nn = maybeAdjustArgentineFixedLineNumber(cc, nn);
    }
    DigitSequence bestNumber = nn;
    MatchResult bestResult = rawClassifier.match(cc, nn);
    if (bestResult != MATCHED) {
      ImmutableSet<DigitSequence> nationalPrefixes = nationalPrefixMap.get(cc);
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
    return PhoneNumberResult.of(PhoneNumbers.create(cc, bestNumber), bestResult);
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

  private static PhoneNumberResult bestOf(PhoneNumberResult a, PhoneNumberResult b) {
    return a.getResult().isBetterThan(b.getResult()) ? a : b;
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
