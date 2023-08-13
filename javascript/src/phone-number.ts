/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { DigitSequence } from "./digit-sequence.js";

export class PhoneNumber {
  private static readonly ASCII_0: number = 48;

  // Obtained from running GenerateMetadata tool (encoded string is output to console).
  private static readonly CC_MASK: string =
      "\u0082\uc810\ufb97\uf7fb\u0007\ufc56\u0004\u0000\u0000\u0000\u0000\u0000\u0000\uf538"
      + "\uffff\uffff\u3ff7\u0000\u0e0c\u0000\u0000\uc000\u00ff\uf7fc\u002e\u0000\u00b0\u0000"
      + "\u0000\u0000\u0000\u3ff0\u0000\u0000\u0000\u0000\uc000\u00ff\u0000\u0000\u0000\u4000"
      + "\uefff\u001f\u0000\u0000\u0000\u0000\u0000\u0000\u0101\u0000\u0000\u01b4\u4040\u014f"
      + "\u0000\u0000\u0000\u0000\ufdff\u000b\u005f";

  static fromE164(e164: string): PhoneNumber {
    let seq = e164.startsWith("+") ? e164.substring(1) : e164;
    let cc = PhoneNumber.extractCallingCode(seq);
    if (!cc) {
      throw new Error(`Cannot extract calling code from E.164 number: ${e164}`);
    }
    return PhoneNumber.of(cc, DigitSequence.parse(seq.substring(cc.length())));
  }

  static of(callingCode: DigitSequence, nationalNumber: DigitSequence): PhoneNumber {
    return new PhoneNumber(callingCode, nationalNumber);
  }

  static extractCallingCode(seq: string): DigitSequence|null {
    let len = seq.length;
    if (len === 0) return null;
    let cc: number = PhoneNumber.digitOf(seq, 0);
    if (cc === 0) return null;
    if (!PhoneNumber.isCallingCode(cc)) {
      if (len === 1) return null;
      cc = (10 * cc) + PhoneNumber.digitOf(seq, 1);
      if (!PhoneNumber.isCallingCode(cc)) {
        if (len === 2) return null;
        cc = (10 * cc) + PhoneNumber.digitOf(seq, 2);
        if (!PhoneNumber.isCallingCode(cc)) return null;
      }
    }
    return DigitSequence.parse(cc.toString());
  }

  private static digitOf(s: string, n: number): number {
    let d = s.charCodeAt(n) - PhoneNumber.ASCII_0;
    if (d < 0 || d > 9) {
      throw new Error(`Invalid decimal digit in: ${s}`);
    }
    return d;
  }

  private static isCallingCode(cc: number): boolean {
    let bits: number = PhoneNumber.CC_MASK.charCodeAt(cc >>> 4);
    return (bits & (1 << (cc & 0xF))) != 0;
  }

  private constructor(private readonly cc: DigitSequence, private readonly nn: DigitSequence) {}

  length(): number {
    return this.cc.length() + this.nn.length();
  }

  getCallingCode(): DigitSequence {
    return this.cc;
  }

  getNationalNumber(): DigitSequence {
    return this.nn;
  }

  getDigits(): DigitSequence {
    return this.cc.append(this.nn);
  }

  toString(): string {
    return "+" + this.getCallingCode() + this.getNationalNumber();
  }
}