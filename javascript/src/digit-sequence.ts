/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

/** Iterator of decimal digits {0..9} in a digit sequence. */
export interface Digits {
  /** @returns true if there are more digits to iterate. */
  hasNext(): boolean;
  /** @returns the next decimal digit {0..9}. */
  next(): number;
}

/**
 * A sequence of decimal digits up to 19-digits in length.
 *
 * The limit of 19 digits exists to match other implementations which encode a digit sequence in a
 * 64-bit long value. However, since E.164 is officially limited to 15 digits, and libphonenumber
 * is limited to 17 digits, a limit of 19 should be sufficient for all expected use cases.
 */
export abstract class DigitSequence {
  private static readonly MAX_LENGTH: number = 19;

  /** @returns an iterator of the digits in this sequence. */
  abstract iterate(): Digits;

  /**
   * @param n the index of the digit to retrieve (in the range `0 <= n < length`).
   * @returns the N-th digit in the sequence (an `Error` is thrown if `n` is out of bounds).
   */
  abstract getDigit(n: number): number;

  /** @returns the length of the digit sequence. */
  abstract length(): number;

  /**
   * @param length the length of the returned sequence (in the range `0 <= length < length()`).
   * @returns a digit sequence of `length` first digits of this sequence.
   */
  abstract append(s: DigitSequence): DigitSequence;

  /**
   * @param length the length of the returned sequence (in the range `0 <= length < length()`).
   * @returns a digit sequence of `length` first digits of this sequence.
   */
  abstract getPrefix(length: number): DigitSequence;

  /**
   * @param length the length of the returned sequence (in the range `0 <= length < length()`).
   * @returns a digit sequence of `length` last digits of this sequence.
   */
  abstract getSuffix(length: number): DigitSequence;

  /**
   * @param a string containing only ASCII decimal digits up to 19-digits in length.
   * @returns a new `DigitSequence` representing the given digits.
   */
  public static parse(digits: string): DigitSequence {
    if (digits.length > DigitSequence.MAX_LENGTH) {
      throw new Error(`Digit sequence too long: ${digits}`);
    }
    if (!/^[0-9]*$/.test(digits)) {
      throw new Error(`Invalid digit sequence: ${digits}`);
    }
    return new StringBasedDigitSequence(digits);
  }
}

class StringBasedDigitSequence extends DigitSequence {
  private static readonly ZERO: number = '0'.charCodeAt(0);

  // We could also use # prefix (https://github.com/tc39/proposal-class-fields#private-fields)
  // but this adds bloat when compiled for older JavaScript versions and anyone accessing
  // the private field in older JS can suffer getting broken.

  /** @param digits A string of ASCII decimal digits up to 19 digits long. */
  constructor(private readonly digits: string) { super(); }

  iterate(): Digits {
    const digits = this.digits;
    return new class implements Digits {
      private i: number = 0;

      hasNext(): boolean {
        return this.i < digits.length;
      }

      next(): number {
        return digits.charCodeAt(this.i++) - StringBasedDigitSequence.ZERO;
      }
    }();
  }

  getDigit(n: number): number {
    return this.digits.charCodeAt(n) - StringBasedDigitSequence.ZERO;
  }

  length(): number {
    return this.digits.length;
  }

  append(s: DigitSequence): DigitSequence {
    return DigitSequence.parse(this.digits + s.toString());
  }

  getPrefix(length: number): DigitSequence {
    if (length <= this.length()) {
      return length < this.length() ? new StringBasedDigitSequence(this.digits.substring(0, length)) : this;
    }
    throw new Error(`length (${length}) must not exceed sequence length: ${this}`);
  }

  getSuffix(length: number): DigitSequence {
    let start = this.length() - length;
    if (start >= 0) {
      return start > 0 ? new StringBasedDigitSequence(this.digits.substring(start)) : this;
    }
    throw new Error(`length (${length}) must not exceed sequence length: ${this}`);
  }

  toString(): string {
    return this.digits;
  }
}