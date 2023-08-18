/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-EPL Identifier-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.goui.phonenumber.FormatType.INTERNATIONAL;
import static net.goui.phonenumber.MatchResult.INVALID;

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

  private static final int CARRIER_CODE_BYTE = 0x3E;
  private static final int RAW_ASCII_BYTE = 0x3F;

  private static final int GROUP_TYPE_MASK = 0x7 << 3;
  private static final int PLAIN_GROUP = 0x0 << 3;
  private static final int GROUP_THEN_SPACE = 0x1 << 3;
  private static final int GROUP_THEN_HYPHEN = 0x2 << 3;
  private static final int OPTIONAL_GROUP = 0x4 << 3;
  private static final int PARENTHESIZED_GROUP = 0x5 << 3;
  private static final int IGNORED_GROUP = 0x6 << 3;

  private final RawClassifier rawClassifier;
  private final FormatType type;

  /**
   * Constructs a formatter using the metadata from the given classifier. Formatter instances should
   * only need to be constructed in a subclass of `AbstractPhoneNumberClassifier`, to expose the
   * functionality implied by the expected metadata schema.
   *
   * @throws IllegalStateException if the format type is not present in the underlying metadata.
   */
  PhoneNumberFormatter(RawClassifier rawClassifier, FormatType type) {
    this.rawClassifier = checkNotNull(rawClassifier);
    this.type = checkNotNull(type);
  }

  /** Formats a phone number according to the type of this formatter. */
  public String format(PhoneNumber phoneNumber) {
    ValueMatcher matcher = rawClassifier.getValueMatcher(phoneNumber.getCallingCode(), type.id);

    // Fall back to INTERNATIONAL formatting if there are no format specifiers for the given type.
    FormatType bestFormatType = this.type;
    if (matcher.getPossibleValues().isEmpty() && bestFormatType != INTERNATIONAL) {
      bestFormatType = INTERNATIONAL;
      matcher = rawClassifier.getValueMatcher(phoneNumber.getCallingCode(), bestFormatType.id);
    }

    // Attempt to find the best format specifier by testing all values in order. A matched number
    // can have only one value, but a partial number may match several format specifiers. This
    // loop picks to specifier with the best match (favouring a first match).
    String bestFormatSpec = "";
    MatchResult bestResult = INVALID;
    for (String spec : rawClassifier.getPossibleValues(bestFormatType.id)) {
      MatchResult result = matcher.matchValue(phoneNumber.getNationalNumber(), spec);
      if (result.compareTo(bestResult) < 0) {
        bestResult = result;
        bestFormatSpec = spec;
        if (result == MatchResult.MATCHED) {
          break;
        }
      }
    }

    // It's possible that a partial match was made above (i.e. PartialMatch/ExcessDigits), but that
    // the number is valid but simply has no format spec assigned. So by making a final validity
    // check we can catch this and reset the default specifier.
    if (bestResult != MatchResult.MATCHED
        && !bestFormatSpec.isEmpty()
        && rawClassifier
            .match(phoneNumber.getCallingCode(), phoneNumber.getNationalNumber())
            .isBetterThan(bestResult)) {
      bestFormatSpec = "";
    }

    // bestFormatSpec is non-null base64 encoded binary spec (possibly empty).
    // bestResult is corresponding match (can be too short, too long, or invalid).
    String formatted;
    if (!bestFormatSpec.isEmpty()) {
      formatted =
          PhoneNumberFormatter.formatNationalNumber(
              phoneNumber.getNationalNumber(), bestFormatSpec);
    } else {
      formatted = phoneNumber.getNationalNumber().toString();
    }
    if (bestFormatType == INTERNATIONAL) {
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
