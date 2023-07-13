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

  private static readonly CC_MASK: string =
      "\u0082\uc810\ufb97\uf7fb\u0007\ufc56\u0004\u0000\u0000\u0000\u0000\u0000\u0000\uf538"
      + "\uffff\uffff\u3ff7\u0000\u0e0c\u0000\u0000\uc000\u00ff\uf7fc\u002e\u0000\u00b0\u0000"
      + "\u0000\u0000\u0000\u3ff0\u0000\u0000\u0000\u0000\uc000\u00ff\u0000\u0000\u0000\u4000"
      + "\uefff\u001f\u0000\u0000\u0000\u0000\u0000\u0000\u0101\u0000\u0000\u01b4\u4040\u014f"
      + "\u0000\u0000\u0000\u0000\ufdff\u000b\u005f";

  static parseE164(e164: string): PhoneNumber {
    let seq = e164.startsWith("+") ? e164.substring(1) : e164;
    if (seq.length <= 3) {
      throw new Error("E.164 numbers must be at least 3 digits: `${e164}`");
    }
    let cc: number = PhoneNumber.digitOf(seq, 0);
    if (!PhoneNumber.isCallingCode(cc)) {
      cc = (10 * cc) + PhoneNumber.digitOf(seq, 1);
      if (!PhoneNumber.isCallingCode(cc)) {
        cc = (10 * cc) + PhoneNumber.digitOf(seq, 2);
        if (!PhoneNumber.isCallingCode(cc)) {
          throw new Error("Unknown calling code `${cc}` in E.164 number: `${e164}`");
        }
      }
    }
    let callingCode: DigitSequence = DigitSequence.parse(cc.toString());
    return new PhoneNumber(callingCode, DigitSequence.parse(seq.substring(callingCode.length())));
  }

  private static digitOf(s: string, n: number): number {
    var d = s.charCodeAt(n) - PhoneNumber.ASCII_0;
    if (d < 0 || d > 9) {
      throw new Error("Invalid decimal digit in: `${s}`");
    }
    return d;
  }

  private static isCallingCode(cc: number): boolean {
    let bits: number = PhoneNumber.CC_MASK.charCodeAt(cc >>> 4);
    return (bits & (1 << (cc & 0xF))) != 0;
  }

  private constructor(private readonly cc: DigitSequence, private readonly nn: DigitSequence) {}

  getCallingCode(): DigitSequence {
    return this.cc;
  }

  getNationalNumber(): DigitSequence {
    return this.nn;
  }

  getDigitSequence(): DigitSequence {
    return this.cc.append(this.nn);
  }

  toString(): string {
    return "+" + this.getDigitSequence();
  }
}