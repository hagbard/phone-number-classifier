/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { PhoneNumber } from "./phone-number.js";
import { DigitSequence, Digits } from "./digit-sequence.js";
import { RawClassifier, ValueMatcher, ReturnType } from "./raw-classifier.js";
import { MatchResult, LengthResult } from "./match-results.js";

export enum FormatType {
  NATIONAL = "NATIONAL_FORMAT",
  INTERNATIONAL = "INTERNATIONAL_FORMAT",
}

/**
 * Provides a format function for phone numbers based on a specific format type.
 *
 * When a metadata schema supports formatter metadata, an instance of this class can be returned to
 * the user from a subclass of `AbstractPhoneNumberClassifier`. Instances of this class cannot be
 * created for classifiers which do not have the required metadata.
 *
 * This class is deliberately lightweight and avoids holding pre-computed format data so that it can
 * be instantiated on demand (if needed). All formatting information is encoded into the format
 * specifier strings obtained from the raw classifier.
 */
export class PhoneNumberFormatter {
  private static readonly CARRIER_CODE_BYTE: number = 0x3E;
  private static readonly RAW_ASCII_BYTE: number = 0x3F;

  private static readonly GROUP_TYPE_MASK: number = 0x7 << 3;
  private static readonly PLAIN_GROUP: number = 0x0 << 3;
  private static readonly GROUP_THEN_SPACE: number = 0x1 << 3;
  private static readonly GROUP_THEN_HYPHEN: number = 0x2 << 3;
  private static readonly OPTIONAL_GROUP: number = 0x4 << 3;
  private static readonly PARENTHESIZED_GROUP: number = 0x5 << 3;
  private static readonly IGNORED_GROUP: number = 0x6 << 3;

  /**
   * Returns whether a classifier has the required format metadata for a type.
   *
   * In general this should not be needed, since most metadata schemas should define whether
   * specific format metadata is to be included. The associated `AbstractPhoneNumberClassifier`
   * subclass should know implicitly if a format type is supported, so no runtime check should be
   * needed.
   *
   * However, it is foreseeable that some schemas could define formatting to be optional and fall
   * back to simple block formatting for missing data.
   */
  static canFormat(rawClassifier: RawClassifier, type: FormatType): boolean {
    return this.rawClassifier.getSupportedNumberTypes().has(type);
  }

  /**
   * Constructs a formatter using the metadata from the given classifier. Formatter instances should
   * only need to be constructed in a subclass of `AbstractPhoneNumberClassifier`, to expose the
   * functionality implied by the expected metadata schema.
   */
  constructor(private readonly rawClassifier: RawClassifier, private readonly type: FormatType) {
    if (!PhoneNumberFormatter.canFormat(rawClassifier, type)) {
      throw new Error(`No format data available for ${type} formatting`);
    }
  }

  /**
   * Formats a phone number according to the type of this formatter.
   */
  format(phoneNumber: PhoneNumber): string {
    let bestFormatSpec: string = "";
    let bestResult: MatchResult = MatchResult.Invalid;
    let matcher: ValueMatcher =
        this.rawClassifier.getValueMatcher(phoneNumber.getCallingCode(), this.type);
    for (var v of this.rawClassifier.getPossibleValues(this.type)) {
      let r = matcher.matchValues(phoneNumber.getNationalNumber(), v);
      if (r < bestResult) {
        bestResult = r;
        bestFormatSpec = v;
        if (r === MatchResult.Matched) {
          break;
        }
      }
    }
    // NOTE: This accounts for classifiers which don't have every value assigned.
    //
    // It's possible that a partial match was made above (i.e. PartialMatch/ExcessDigits), but that
    // the number is valid but simply unassigned any value. So by making a final validity check we
    // can catch this and reset the default value (which for formatting is always the empty string).
    if (bestResult !== MatchResult.Matched
        && bestFormatSpec.length > 0
        && this.rawClassifier.match(phoneNumber.getCallingCode(), phoneNumber.getNationalNumber()) < bestResult) {
      bestFormatSpec = "";
    }
    // bestFormatSpec is non-null base64 encoded binary spec (possibly empty).
    // bestResult is corresponding match (can be too short, too long, or invalid).
    let formatted: string;
    if (bestFormatSpec.length > 0) {
      formatted = PhoneNumberFormatter.formatNationalNumber(phoneNumber.getNationalNumber(), bestFormatSpec);
    } else {
      formatted = phoneNumber.getNationalNumber().toString();
    }
    if (this.type === FormatType.INTERNATIONAL) {
      formatted = "+" + phoneNumber.getCallingCode() + " " + formatted;
    }
    return formatted;
  }

  private static isGroup(byte: number): boolean {
    return (byte & 0x40) != 0;
  }

  private static isOptionalGroup(byte: number): boolean {
    // Count the length bits of anything that's a group, but isn't an optional group.
    return PhoneNumberFormatter.isGroup(byte)
        && (byte & PhoneNumberFormatter.GROUP_TYPE_MASK) == PhoneNumberFormatter.OPTIONAL_GROUP;
  }

  private static groupLength(byte: number): number {
    return PhoneNumberFormatter.isGroup(byte) ? (byte & 0x7) + 1 : 0;
  }

  private static appendNDigits(s: string, n: number, digits: Digits): string {
    while (digits.hasNext() && --n >= 0) {
      s += digits.next().toString();
    }
    return s;
  }

  private static formatNationalNumber(nn: DigitSequence, encodedFormatSpec: string): string {
    let buffer: Buffer = new Buffer(encodedFormatSpec, "ascii");
    let bytes: Uint8Array = new Uint8Array(
        buffer.buffer,
        buffer.byteOffset,
        buffer.length);

    let maxDigitCount: number = bytes
        .map(c => PhoneNumberFormatter.groupLength(c))
        .reduce((a, b) => a + b, 0);
    let optionalDigitCount: number = bytes
        .filter(c => PhoneNumberFormatter.isOptionalGroup(c))
        .map(c => PhoneNumberFormatter.groupLength(c))
        .reduce((a, b) => a + b, 0);
    let minDigitCount: number = maxDigitCount - optionalDigitCount;
    let possibleOptionalDigits: number = Math.max(nn.length() - minDigitCount, 0);

    let out: string = "";
    let digits: Digits = nn.iterate();
    for (var i = 0; i < bytes.length && digits.hasNext(); i++) {
      let b: number = bytes[i];

      if (PhoneNumberFormatter.isGroup(b)) {
        let len: number = PhoneNumberFormatter.groupLength(b);
        let typ: number = b & PhoneNumberFormatter.GROUP_TYPE_MASK;
        switch (typ) {
          case PhoneNumberFormatter.PLAIN_GROUP:
            out = PhoneNumberFormatter.appendNDigits(out, len, digits);
            break;
          case PhoneNumberFormatter.GROUP_THEN_SPACE:
            out = PhoneNumberFormatter.appendNDigits(out, len, digits);
            if (digits.hasNext()) out += " ";
            break;
          case PhoneNumberFormatter.GROUP_THEN_HYPHEN:
            out = PhoneNumberFormatter.appendNDigits(out, len, digits);
            if (digits.hasNext()) out += "-";
            break;

          case PhoneNumberFormatter.OPTIONAL_GROUP:
            let digitsToAdd: number = Math.min(len, possibleOptionalDigits);
            out = PhoneNumberFormatter.appendNDigits(out, digitsToAdd, digits);
            possibleOptionalDigits -= digitsToAdd;
            break;
          case PhoneNumberFormatter.PARENTHESIZED_GROUP:
            out = PhoneNumberFormatter.appendNDigits(out + "(", len, digits) + ")";
            break;
          case PhoneNumberFormatter.IGNORED_GROUP:
            while (digits.hasNext() && --len >= 0) {
              digits.next();
            }
            break;

          default:
            throw new Error(`Unknown group type: ${b}`)
        }
      } else if (b === PhoneNumberFormatter.CARRIER_CODE_BYTE) {
        // TODO: REPLACE WITH CARRIER CODE WHEN SUPPORTED !!
        out += '@';
      } else if (b === PhoneNumberFormatter.RAW_ASCII_BYTE) {
        out += String.fromCharCode(bytes[++i]);
      } else {
        // WARNING: This is too lenient and should reject unexpected chars.
        out += String.fromCharCode(b)
      }
    }
    while (digits.hasNext()) {
      out += digits.next().toString();
    }
    return out;
  }
}