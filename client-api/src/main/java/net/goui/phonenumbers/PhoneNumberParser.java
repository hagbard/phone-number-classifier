/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-EPL Identifier-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static net.goui.phonenumbers.FormatType.INTERNATIONAL;
import static net.goui.phonenumbers.FormatType.NATIONAL;
import static net.goui.phonenumbers.LengthResult.POSSIBLE;
import static net.goui.phonenumbers.LengthResult.TOO_LONG;
import static net.goui.phonenumbers.MatchResult.INVALID;
import static net.goui.phonenumbers.MatchResult.MATCHED;

import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.goui.phonenumbers.metadata.ParserData;
import net.goui.phonenumbers.metadata.RawClassifier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Phone number parsing API, available from subclasses of {@link AbstractPhoneNumberClassifier} when
 * parser metadata is available.
 *
 * <p><em>Important</em>: This parser is deliberately simpler than the corresponding parser in
 * Google's Libphonenumber library. It is designed to be a robust parser for cases where the input
 * is a formatted phone number, but it will not handle all the edge cases that Libphonenumber can
 * (e.g. parsing U.S. vanity numbers such as "1-800-NOT-REAL").
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
 * <p>If a calling code/region is not supported in the metadata for the parser, then no validation
 * is performed and the match result is always {@link MatchResult#INVALID}.
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

  private static final Comparator<PhoneNumberResult<?>> COMPARE_RESULTS =
      Comparator.comparing(PhoneNumberResult::getMatchResult);

  private final RawClassifier rawClassifier;
  private final ImmutableListMultimap<DigitSequence, T> regionCodeMap;
  private final ImmutableMap<T, DigitSequence> callingCodeMap;
  private final ImmutableMap<String, PhoneNumber> exampleNumberMap;
  private final ImmutableSetMultimap<DigitSequence, DigitSequence> nationalPrefixMap;
  private final ImmutableSet<DigitSequence> nationalPrefixOptional;

  // Called from AbstractPhoneNumberClassifier.
  PhoneNumberParser(RawClassifier rawClassifier, Function<String, T> converter) {
    this.rawClassifier = rawClassifier;
    T worldRegion = checkNotNull(converter.apply("001"));
    ImmutableListMultimap.Builder<DigitSequence, T> regionCodeMap = ImmutableListMultimap.builder();
    ImmutableMap.Builder<T, DigitSequence> callingCodeMap = ImmutableMap.builder();
    // Contains keys for both regions ("US", "GB" and "001") as well as calling codes ("1", "44",
    // "883").
    // There is no possibility of clashing between these and it saves having 2 maps.
    ImmutableMap.Builder<String, PhoneNumber> exampleNumberMap = ImmutableMap.builder();
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

      ImmutableList<DigitSequence> exampleNationalNumbers = parserData.getExampleNationalNumbers();
      if (!exampleNationalNumbers.isEmpty()) {
        checkArgument(
            exampleNationalNumbers.size() == regions.size(),
            "Invalid example numbers (should match available regions): %s",
            exampleNationalNumbers);
        // This gets a bit complicated due to the existence of the "world" region 001, for which
        // multiple calling codes and multiple regions exist. To address this we store example
        // numbers keyed by both calling code and region code (in string form). Luckily it's
        // impossible for keys to overlap, so we can share the same map.
        ImmutableList<T> regionsList = regions.asList();
        for (int i = 0; i < regionsList.size(); i++) {
          DigitSequence nationalNumber = exampleNationalNumbers.get(i);
          // Empty example numbers are possible and must just be ignored.
          if (nationalNumber.isEmpty()) continue;
          PhoneNumber example = PhoneNumbers.of(cc, nationalNumber);
          if (i == 0) {
            // The "main" region is also keyed by its calling code (this is how "world" region
            // examples can be returned to the user).
            exampleNumberMap.put(cc.toString(), example);
          }
          T region = regionsList.get(i);
          if (!region.equals(worldRegion)) {
            // Non-world regions are keyed here.
            exampleNumberMap.put(region.toString(), example);
          }
        }
      }

      if (!parserData.getNationalPrefixes().isEmpty()) {
        nationalPrefixMap.putAll(cc, parserData.getNationalPrefixes());
        if (parserData.isNationalPrefixOptional()) {
          nationalPrefixOptional.add(cc);
        }
      }
    }
    this.regionCodeMap = regionCodeMap.build();
    this.callingCodeMap = callingCodeMap.buildOrThrow();
    this.exampleNumberMap = exampleNumberMap.buildOrThrow();
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

  /**
   * Returns an example phone number for the given CLDR region code (if available).
   *
   * <p>It is not always possible to guarantee example numbers will exist for every metadata
   * configuration, and it is unsafe to invent example numbers at random (since they might be
   * accidentally callable, which can cause problems).
   *
   * <p>Note: The special "world" region "001" is associated with more than one example number, so
   * it cannot be resolved by this method. Use {@link #getExampleNumber(DigitSequence)} instead.
   */
  public Optional<PhoneNumber> getExampleNumber(T region) {
    return callingCodeMap.containsKey(region)
        ? Optional.ofNullable(exampleNumberMap.get(region.toString()))
        : Optional.empty();
  }

  /**
   * Returns an example phone number for the given calling code (if available).
   *
   * <p>It is not always possible to guarantee example numbers will exist for every metadata
   * configuration, and it is unsafe to invent example numbers at random (since they might be
   * accidentally callable, which can cause problems).
   *
   * <p>Note: This method will return the example number of the main region of the calling code.
   */
  public Optional<PhoneNumber> getExampleNumber(DigitSequence callingCode) {
    return regionCodeMap.containsKey(callingCode)
        ? Optional.ofNullable(exampleNumberMap.get(callingCode.toString()))
        : Optional.empty();
  }

  public Optional<PhoneNumber> parseLeniently(String text) {
    return parseLeniently(text, (DigitSequence) null);
  }

  public Optional<PhoneNumber> parseLeniently(String text, T region) {
    return parseLeniently(text, toCallingCode(region));
  }

  public Optional<PhoneNumber> parseLeniently(String text, @Nullable DigitSequence callingCode) {
    return Optional.ofNullable(parseImpl(text, callingCode)).map(PhoneNumberResult::getPhoneNumber);
  }

  public PhoneNumberResult<T> parseStrictly(String text) {
    return parseStrictly(text, (DigitSequence) null);
  }

  public PhoneNumberResult<T> parseStrictly(String text, T region) {
    return parseStrictly(text, toCallingCode(region));
  }

  public PhoneNumberResult<T> parseStrictly(String text, @Nullable DigitSequence callingCode) {
    PhoneNumberResult<T> result = parseImpl(text, callingCode);
    checkArgument(result != null, "Cannot parse phone number text '%s'", text);
    return result;
  }

  private DigitSequence toCallingCode(T region) {
    Optional<DigitSequence> callingCode = getCallingCode(region);
    checkArgument(callingCode.isPresent(), "Unknown region code: %s", region);
    return callingCode.get();
  }

  /*
   * The algorithm tries to parse the input assuming both "national" and "international"
   * formatting of the given text.
   *    * For national format, the given calling code is used ("NAT").
   *    * For international format, the calling code is extracted from the number ("INT").
   * 1. If neither result can be obtained, parsing fails
   * 2. If only one result can be obtained, it is returned
   * 3. If the national result match is strictly better than the international one, return the
   *    national result.
   * 4. In the remaining cases we check the input ("CHK"), and return the international result if
   *    either:
   *    * The extracted calling code was the same as the given calling code: ("41 xxxx xxxx", cc=41)
   *    * If the input text is internationally formatted: e.g. ("+41 xxxx xxxx", cc=34)
   * Otherwise return the national format.
   *
   * Note: Step 4 is only reached if the international parse result is a better match than the
   * national one, and even then we might return the national result if we aren't sure the extracted
   * calling code looks trustworthy.
   *
   * National   /----------------- International Result ------------------\
   *  Result  || MATCHED | PARTIAL | EXCESS  | LENGTH  | INVALID |  N/A    |
   * =========||============================================================
   *  MATCHED || CHK [4] | NAT [3] | NAT [3] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||-=--=--=-+---------+---------+---------+---------+---------+
   *  PARTIAL || CHK [4] | CHK [4] | NAT [3] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||---------+-=--=--=-+---------+---------+---------+---------+
   *  EXCESS  || CHK [4] | CHK [4] | CHK [4] | NAT [3] | NAT [3] | NAT [2] |
   * ---------||---------+---------+-=--=--=-+---------+---------+---------+
   *  LENGTH  || CHK [4] | CHK [4] | CHK [4] | CHK [4] | NAT [3] | NAT [2] |
   * ---------||---------+---------+---------+-=--=--=-+---------+---------+
   *  INVALID || CHK [4] | CHK [4] | CHK [4] | CHK [4] | CHK [4] | NAT [2] |
   * ---------||---------+---------+---------+---------+-=--=--=-+-=--=--=-+
   *   N/A    || INT [2] | INT [2] | INT [2] | INT [2] | INT [2] | --- [1] |
   * ---------||---------+---------+---------+---------+---------+---------+
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
      // This accounts for step [1] (no results) and step [2] with only the national result.
      return nationalParseResult;
    }
    PhoneNumberResult<T> internationalParseResult =
        getBestResult(extractedCc, removePrefix(digits, extractedCc.length()), INTERNATIONAL);
    if (nationalParseResult == null) {
      // This accounts for step [2] with only the international result.
      return internationalParseResult;
    }
    if (COMPARE_RESULTS.compare(nationalParseResult, internationalParseResult) < 0) {
      // This accounts for step [3].
      return nationalParseResult;
    }
    if (callingCode.equals(extractedCc) || looksLikeInternationalFormat(text, extractedCc)) {
      // This accounts for step [4] when the input strongly suggest international format.
      return internationalParseResult;
    }
    return nationalParseResult;
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
      DigitSequence cc, DigitSequence nn, FormatType formatType) {
    if (cc.equals(CC_ARGENTINA)) {
      nn = maybeAdjustArgentineFixedLineNumber(cc, nn);
    }
    if (!rawClassifier.getSupportedCallingCodes().contains(cc)) {
      return PhoneNumberResult.of(PhoneNumbers.of(cc, nn), INVALID, formatType);
    }
    ImmutableSet<DigitSequence> nationalPrefixes = nationalPrefixMap.get(cc);
    MatchResult bestResult = INVALID;
    // We can test the given number (without attempting to remove a national prefix) under some
    // conditions, but avoid doing so when a national prefix is required for national dialling.
    if (formatType == INTERNATIONAL
        || nationalPrefixes.isEmpty()
        || nationalPrefixOptional.contains(cc)) {
      bestResult = rawClassifier.match(cc, nn);
    }
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
    return PhoneNumberResult.of(PhoneNumbers.of(cc, bestNumber), bestResult, formatType);
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
