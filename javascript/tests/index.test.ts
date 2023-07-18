/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { PhoneNumber, AbstractPhoneNumberClassifier, Matcher, SingleValuedMatcher, DigitSequence, PhoneNumberFormatter, FormatType } from "../src/index";
import { RawClassifier } from "../src/internal";
import goldenDataJson from "./lpn_golden_data.json"
const fs = require("fs");

enum LpnType {
  PREMIUM_RATE,
  TOLL_FREE,
  SHARED_COST,
  FIXED_LINE,
  MOBILE,
  FIXED_LINE_OR_MOBILE,
  PAGER,
  PERSONAL_NUMBER,
  VOIP,
  UAN,
  VOICEMAIL,
}

class TestClassifier extends AbstractPhoneNumberClassifier {
  private readonly typeMatcher: SingleValuedMatcher<LpnType>;
  private readonly regionMatcher: Matcher<string>;
  private readonly nationalFormatter: PhoneNumberFormatter;
  private readonly internationalFormatter: PhoneNumberFormatter;

  constructor(path: string) {
    super(fs.readFileSync(path, { encoding: "utf8", flag: "r" }));
    this.typeMatcher = super.forNumericEnum("LPN:TYPE", LpnType).singleValuedMatcher();
    // We don't have a region code enum to hand, so use strings directly.
    this.regionMatcher = super.forStrings("REGION").matcher();
    this.nationalFormatter = super.getFormatter(FormatType.NATIONAL);
    this.internationalFormatter = super.getFormatter(FormatType.INTERNATIONAL);
  }

  rawClassifierForTests(): RawClassifier {
    return super.getRawClassifier();
  }

  formatForTests(cc: DigitSequence, nn: DigitSequence, type: string): string {
    let number: PhoneNumber = PhoneNumber.fromE164("+" + cc + nn);
    switch (type) {
      case "NATIONAL_FORMAT":
        return this.nationalFormatter.format(number);
      case "INTERNATIONAL_FORMAT":
        return this.internationalFormatter.format(number);
      default:
        throw new Error(`unknown format type: ${type}`);
    }
  }

  forType(): SingleValuedMatcher<LpnType> { return this.typeMatcher; }

  forRegion(): Matcher<string> { return this.regionMatcher; }

  formatNational(number: PhoneNumber): string {
    return this.nationalFormatter.format(number);
  }

  formatInternational(number: PhoneNumber): string {
    return this.internationalFormatter.format(number);
  }
}

function assertDigits(seq: DigitSequence, ...digits: number[]) {
  expect(seq.length()).toEqual(digits.length);
  for (var n = 0; n < seq.length(); n++) {
    expect(seq.getDigit(n)).toEqual(digits[n]);
  }
}

describe("PhoneNumber", () => {
  test("testParse", () => {
    assertDigits(DigitSequence.parse(""));
    assertDigits(DigitSequence.parse("0"), 0);
    assertDigits(DigitSequence.parse("0000"), 0, 0, 0, 0);
    assertDigits(DigitSequence.parse("1234"), 1, 2, 3, 4);
    assertDigits(DigitSequence.parse("123456789"), 1, 2, 3, 4, 5, 6, 7, 8, 9);
  });

  test("testParseErrors", () => {
    expect(() => DigitSequence.parse("x"))
        .toThrow(new Error("Invalid digit sequence: x"));
    expect(() => DigitSequence.parse("01234x567"))
        .toThrow(new Error("Invalid digit sequence: 01234x567"));
    // 20 digits (too long).
    expect(() => DigitSequence.parse("01234567890123456789"))
        .toThrow(new Error("Digit sequence too long: 01234567890123456789"));
  });

  test("testToString", () => {
    expect(DigitSequence.parse("").toString()).toEqual("");
    expect(DigitSequence.parse("012345").toString()).toEqual("012345");
  });
});

describe("PhoneNumber", () => {
  test("testFromE164", () => {
    let number: PhoneNumber = PhoneNumber.fromE164("+44123456789");
    expect(number.length()).toEqual(11);
    expect(number.toString()).toEqual("+44123456789");
  });

  test("testCallingCodeAndNationalNumber", () => {
    let oneDigitCc: PhoneNumber = PhoneNumber.fromE164("+15515817989");
    expect(oneDigitCc.getCallingCode()).toEqual(DigitSequence.parse("1"));
    expect(oneDigitCc.getNationalNumber()).toEqual(DigitSequence.parse("5515817989"));

    let twoDigitCc: PhoneNumber = PhoneNumber.fromE164("+447471920002");
    expect(twoDigitCc.getCallingCode()).toEqual(DigitSequence.parse("44"));
    expect(twoDigitCc.getNationalNumber()).toEqual(DigitSequence.parse("7471920002"));

    let threeDigitCc: PhoneNumber = PhoneNumber.fromE164("+962707952933");
    expect(threeDigitCc.getCallingCode()).toEqual(DigitSequence.parse("962"));
    expect(threeDigitCc.getNationalNumber()).toEqual(DigitSequence.parse("707952933"));
  });

  test("testEquality", () => {
    let number: PhoneNumber = PhoneNumber.fromE164("+447471920002");
    // Parse from same input is equal.
    expect(number).toEqual(PhoneNumber.fromE164("+447471920002"));
    expect(number).not.toEqual(PhoneNumber.fromE164("+447471929999"));
  });
});

const pnc = new TestClassifier("tests/lpn_dfa_compact.json");

describe("AbstractPhoneNumberClassifier", () => {
  test('testGetPossibleValues', () => {
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700112345")))
      .toEqual(new Set(["GB"]));
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700312345")))
      .toEqual(new Set(["JE"]));
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700")))
      .toEqual(new Set(["GB", "JE"]));
  });
});

describe("PhoneNumberFormatter", () => {
  test('testCompleteNumberFormatting', () => {
    expect(pnc.formatNational(PhoneNumber.fromE164("+447700112345"))).toEqual("07700 112345");
    expect(pnc.formatInternational(PhoneNumber.fromE164("+447700112345"))).toEqual("+44 7700 112345");
  });
  test('testOptionalGroupFormatting', () => {
    // Use RU toll free numbers since they have an optional group in the middle.
    expect(pnc.formatNational(PhoneNumber.fromE164("+55800123456"))).toEqual("0800 12 3456");
    expect(pnc.formatNational(PhoneNumber.fromE164("+558001234567"))).toEqual("0800 123 4567");
  });
  test('testAsYouTypeFormatting', () => {
    // Use BR variable cost numbers since they have an optional group in the middle ("#XXX XX* XXXX").
    expect(pnc.formatNational(PhoneNumber.fromE164("+5580"))).toEqual("080");
    expect(pnc.formatNational(PhoneNumber.fromE164("+55800"))).toEqual("0800");
    expect(pnc.formatNational(PhoneNumber.fromE164("+558001"))).toEqual("0800 1");
    expect(pnc.formatNational(PhoneNumber.fromE164("+5580012"))).toEqual("0800 12");
    expect(pnc.formatNational(PhoneNumber.fromE164("+55800123"))).toEqual("0800 12 3");
    expect(pnc.formatNational(PhoneNumber.fromE164("+558001234"))).toEqual("0800 12 34");
    expect(pnc.formatNational(PhoneNumber.fromE164("+5580012345"))).toEqual("0800 12 345");
    expect(pnc.formatNational(PhoneNumber.fromE164("+55800123456"))).toEqual("0800 12 3456");
    expect(pnc.formatNational(PhoneNumber.fromE164("+558001234567"))).toEqual("0800 123 4567");
    expect(pnc.formatNational(PhoneNumber.fromE164("+5580012345678"))).toEqual("0800 123 45678");
    expect(pnc.formatNational(PhoneNumber.fromE164("+55800123456789"))).toEqual("0800 123 456789");
  });
});

describe("GoldenDataTest", () => {
  test('testGoldenData', () => {
    let raw: RawClassifier = pnc.rawClassifierForTests();
    goldenDataJson.testdata.forEach(gd => {
      let cc: DigitSequence = DigitSequence.parse(gd.cc);
      let nn: DigitSequence = DigitSequence.parse(gd.number);
      gd.result.forEach(r => {
        expect(raw.classify(cc, nn, r.type)).toEqual(new Set(r.values));
      });
      gd.format.forEach(r => {
        expect(pnc.formatForTests(cc, nn, r.type)).toEqual(r.value);
      })
    });
  });
});
