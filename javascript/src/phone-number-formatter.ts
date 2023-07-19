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
 * A base class from which custom, type-safe classifiers can be derived.
 *
 * This class maps the structure of a known metadata schema to set of a type-safe accessor methods
 * from which type specific classifiers and matchers can be obtained. In typical usage, a subclass
 * of this class will exist for each uniquely structured metadata schema, and will only be extended
 * as that schema evolves.
 *
 * In the subclass, new type-safe fields should be created for each supported phone number attribute
 * (e.g. "TARIFF" or "TYPE") via the `protected` `forXxx()` methods. Currently there exists support
 * for mapping to/from numeric or string based enums or (as a last resort) using string identifiers
 * directly.
 */
export class PhoneNumberFormatter {
  constructor(private readonly rawClassifier: RawClassifier, private readonly type: FormatType) {
    if (!rawClassifier.getSupportedNumberTypes().has(type)) {
      throw new Error(`No format data available for ${type} formatting`);
    }
  }

  format(number: PhoneNumber): string {
    let bestFormatSpec: string = "";
    let bestResult: MatchResult = MatchResult.Invalid;
    let matcher: ValueMatcher =
        this.rawClassifier.getValueMatcher(number.getCallingCode(), this.type);
    for (var v of this.rawClassifier.getPossibleValues(this.type)) {
      let r = matcher.matchValues(number.getNationalNumber(), v);
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
        && this.rawClassifier.match(number.getCallingCode(), number.getNationalNumber()) < bestResult) {
      bestFormatSpec = "";
    }
    // bestFormatSpec is non-null base64 encoded binary spec (possibly empty).
    // bestResult is corresponding match (can be too short, too long, or invalid).
    let formatted: string;
    if (bestFormatSpec.length > 0) {
      formatted = PhoneNumberFormatter.formatNationalNumber(number.getNationalNumber(), bestFormatSpec);
    } else {
      formatted = number.getNationalNumber().toString();
    }
    if (this.type === FormatType.INTERNATIONAL) {
      formatted = "+" + number.getCallingCode() + " " + formatted;
    }
    return formatted;
  }

  private static readonly CARRIER_CODE_BYTE: number = 0x3E;
  private static readonly RAW_ASCII_BYTE: number = 0x3F;

  private static readonly GROUP_TYPE_MASK: number = 0x7 << 3;
  private static readonly PLAIN_GROUP: number = 0x0 << 3;
  private static readonly GROUP_THEN_SPACE: number = 0x1 << 3;
  private static readonly GROUP_THEN_HYPHEN: number = 0x2 << 3;
  private static readonly OPTIONAL_GROUP: number = 0x4 << 3;
  private static readonly PARENTHESIZED_GROUP: number = 0x5 << 3;
  private static readonly IGNORED_GROUP: number = 0x6 << 3;

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