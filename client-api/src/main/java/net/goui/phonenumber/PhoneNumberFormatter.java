/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-EPL Identifier-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import net.goui.phonenumber.DigitSequence.Digits;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.RawClassifier.ValueMatcher;

/**
 * Provides a format function for phone numbers based on a specific format type.
 *
 * <p>When a metadata schema supports formatter metadata, an instance of this class can be returned
 * to the user from a subclass of `AbstractPhoneNumberClassifier`. Instances of this class cannot be
 * created for classifiers which do not have the required metadata.
 *
 * <p>This class is deliberately lightweight and avoids holding pre-computed format data so that it
 * can be instantiated on demand (if needed). All formatting information is encoded into the format
 * specifier strings obtained from the raw classifier.
 */
public class PhoneNumberFormatter {
  public enum FormatType {
    NATIONAL("NATIONAL_FORMAT"),
    INTERNATIONAL("INTERNATIONAL_FORMAT");

    final String id;

    FormatType(String id) {
      this.id = id;
    }
  }

  private static final int CARRIER_CODE_BYTE = 0x3E;
  private static final int RAW_ASCII_BYTE = 0x3F;

  private static final int GROUP_TYPE_MASK = 0x7 << 3;
  private static final int PLAIN_GROUP = 0x0 << 3;
  private static final int GROUP_THEN_SPACE = 0x1 << 3;
  private static final int GROUP_THEN_HYPHEN = 0x2 << 3;
  private static final int OPTIONAL_GROUP = 0x4 << 3;
  private static final int PARENTHESIZED_GROUP = 0x5 << 3;
  private static final int IGNORED_GROUP = 0x6 << 3;

  /**
   * Returns whether a classifier has the required format metadata for a type.
   *
   * <p>In general this should not be needed, since most metadata schemas should define whether
   * specific format metadata is to be included. The associated `AbstractPhoneNumberClassifier`
   * subclass should know implicitly if a format type is supported, so no runtime check should be
   * needed.
   *
   * <p>However, it is foreseeable that some schemas could define formatting to be optional and fall
   * back to simple block formatting for missing data.
   */
  static boolean canFormat(RawClassifier classifier, FormatType type) {
    return classifier.getSupportedNumberTypes().contains(type.id);
  }

  private final RawClassifier rawClassifier;
  private final FormatType type;

  /**
   * Constructs a formatter using the metadata from the given classifier. Formatter instances should
   * only need to be constructed in a subclass of `AbstractPhoneNumberClassifier`, to expose the
   * functionality implied by the expected metadata schema.
   */
  PhoneNumberFormatter(RawClassifier rawClassifier, FormatType type) {
    checkArgument(
        canFormat(rawClassifier, type), "No format data available for %s formatting", type);
    this.rawClassifier = checkNotNull(rawClassifier);
    this.type = checkNotNull(type);
  }

  /** Formats a phone number according to the type of this formatter. */
  public String format(PhoneNumber phoneNumber) {
    String bestFormatSpec = "";
    MatchResult bestResult = MatchResult.INVALID;
    ValueMatcher matcher = rawClassifier.getValueMatcher(phoneNumber.getCallingCode(), type.id);
    for (String v : rawClassifier.getPossibleValues(type.id)) {
      MatchResult r = matcher.matchValue(phoneNumber.getNationalNumber(), v);
      if (r.compareTo(bestResult) < 0) {
        bestResult = r;
        bestFormatSpec = v;
        if (r == MatchResult.MATCHED) {
          break;
        }
      }
    }
    // NOTE: This accounts for classifiers which don't have every value assigned.
    //
    // It's possible that a partial match was made above (i.e. PartialMatch/ExcessDigits), but that
    // the number is valid but simply unassigned any value. So by making a final validity check we
    // can catch this and reset the default value (which for formatting is always the empty string).
    if (bestResult != MatchResult.MATCHED
        && bestFormatSpec.length() > 0
        && rawClassifier
                .match(phoneNumber.getCallingCode(), phoneNumber.getNationalNumber())
                .compareTo(bestResult)
            < 0) {
      bestFormatSpec = "";
    }
    // bestFormatSpec is non-null base64 encoded binary spec (possibly empty).
    // bestResult is corresponding match (can be too short, too long, or invalid).
    String formatted;
    if (bestFormatSpec.length() > 0) {
      formatted =
          PhoneNumberFormatter.formatNationalNumber(
              phoneNumber.getNationalNumber(), bestFormatSpec);
    } else {
      formatted = phoneNumber.getNationalNumber().toString();
    }
    if (type == FormatType.INTERNATIONAL) {
      formatted = "+" + phoneNumber.getCallingCode() + " " + formatted;
    }
    return formatted;
  }

  private static boolean isGroup(int byt) {
    return (byt & 0x40) != 0;
  }

  private static boolean isOptionalGroup(int byt) {
    // Count the length bits of anything that's a group, but isn't an optional group.
    return PhoneNumberFormatter.isGroup(byt)
        && (byt & PhoneNumberFormatter.GROUP_TYPE_MASK) == PhoneNumberFormatter.OPTIONAL_GROUP;
  }

  private static int groupLength(int byt) {
    return PhoneNumberFormatter.isGroup(byt) ? (byt & 0x7) + 1 : 0;
  }

  private static StringBuilder appendNDigits(StringBuilder out, int n, Digits digits) {
    while (digits.hasNext() && --n >= 0) {
      out.append((char) ('0' + digits.next()));
    }
    return out;
  }

  private static String formatNationalNumber(DigitSequence nn, String encodedFormatSpec) {
    int maxDigitCount = encodedFormatSpec.chars().map(PhoneNumberFormatter::groupLength).sum();
    int optionalDigitCount =
        encodedFormatSpec
            .chars()
            .filter(PhoneNumberFormatter::isOptionalGroup)
            .map(PhoneNumberFormatter::groupLength)
            .sum();

    int minDigitCount = maxDigitCount - optionalDigitCount;
    int possibleOptionalDigits = Math.max(nn.length() - minDigitCount, 0);

    StringBuilder out = new StringBuilder();
    Digits digits = nn.iterate();
    for (var i = 0; i < encodedFormatSpec.length() && digits.hasNext(); i++) {
      int b = encodedFormatSpec.charAt(i);

      if (PhoneNumberFormatter.isGroup(b)) {
        int len = PhoneNumberFormatter.groupLength(b);
        int typ = b & PhoneNumberFormatter.GROUP_TYPE_MASK;
        switch (typ) {
          case PhoneNumberFormatter.PLAIN_GROUP:
            appendNDigits(out, len, digits);
            break;
          case PhoneNumberFormatter.GROUP_THEN_SPACE:
            appendNDigits(out, len, digits);
            if (digits.hasNext()) out.append(" ");
            break;
          case PhoneNumberFormatter.GROUP_THEN_HYPHEN:
            appendNDigits(out, len, digits);
            if (digits.hasNext()) out.append("-");
            break;

          case PhoneNumberFormatter.OPTIONAL_GROUP:
            int digitsToAdd = Math.min(len, possibleOptionalDigits);
            appendNDigits(out, digitsToAdd, digits);
            possibleOptionalDigits -= digitsToAdd;
            break;
          case PhoneNumberFormatter.PARENTHESIZED_GROUP:
            appendNDigits(out.append("("), len, digits).append(")");
            break;
          case PhoneNumberFormatter.IGNORED_GROUP:
            while (digits.hasNext() && --len >= 0) {
              digits.next();
            }
            break;

          default:
            throw new AssertionError("Unknown group type: " + b);
        }
      } else if (b == PhoneNumberFormatter.CARRIER_CODE_BYTE) {
        // TODO: REPLACE WITH CARRIER CODE WHEN SUPPORTED !!
        out.append('@');
      } else if (b == PhoneNumberFormatter.RAW_ASCII_BYTE) {
        out.append(encodedFormatSpec.charAt(++i));
      } else {
        // WARNING: This is too lenient and should reject unexpected chars.
        out.append((char) b);
      }
    }
    while (digits.hasNext()) {
      out.append((char) ('0' + digits.next()));
    }
    return out.toString();
  }
}
