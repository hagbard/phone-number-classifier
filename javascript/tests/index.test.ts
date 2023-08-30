/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import {
    SchemaVersion,
    PhoneNumber,
    AbstractPhoneNumberClassifier,
    Matcher,
    SingleValuedMatcher,
    DigitSequence,
    PhoneNumberFormatter,
    FormatType,
    PhoneNumberParser,
    MatchResult } from "../dist/index.js";
import { RawClassifier, Converter } from "../dist/internal.js";
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
  private readonly parser: PhoneNumberParser<string>;

  constructor(path: string) {
    super(
        fs.readFileSync(path, { encoding: "utf8", flag: "r" }),
        new SchemaVersion("goui.net/libphonenumber/dfa/compact", 1));
    this.typeMatcher =
        super.forValues("LPN:TYPE", super.ofNumericEnum(LpnType)).singleValuedMatcher();
    // We don't have a region code enum to hand, so use strings directly.
    this.regionMatcher = super.forStrings("REGION").matcher();
    this.nationalFormatter = super.createFormatter(FormatType.National);
    this.internationalFormatter = super.createFormatter(FormatType.International);
    this.parser = super.createParser(Converter.identity());
  }

  rawClassifierForTests(): RawClassifier {
    return super.getRawClassifier();
  }

  formatForTests(cc: DigitSequence, nn: DigitSequence, type: string): string {
    let number: PhoneNumber = e164("+" + cc + nn);
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

  getParser(): PhoneNumberParser<string> {
    return this.parser;
  }
}

function assertDigits(seq: DigitSequence, ...digits: number[]) {
  expect(seq.length()).toEqual(digits.length);
  for (var n = 0; n < seq.length(); n++) {
    expect(seq.getDigit(n)).toEqual(digits[n]);
  }
}

function seq(s: string): DigitSequence {
  return DigitSequence.parse(s);
}

function e164(s: string): PhoneNumber {
  return PhoneNumber.fromE164(s);
}

describe("PhoneNumber", () => {
  test("testParse", () => {
    assertDigits(seq(""));
    assertDigits(seq("0"), 0);
    assertDigits(seq("0000"), 0, 0, 0, 0);
    assertDigits(seq("1234"), 1, 2, 3, 4);
    assertDigits(seq("123456789"), 1, 2, 3, 4, 5, 6, 7, 8, 9);
  });

  test("testParseErrors", () => {
    expect(() => seq("x"))
        .toThrow(new Error("Invalid digit sequence: x"));
    expect(() => seq("01234x567"))
        .toThrow(new Error("Invalid digit sequence: 01234x567"));
    // 20 digits (too long).
    expect(() => seq("01234567890123456789"))
        .toThrow(new Error("Digit sequence too long: 01234567890123456789"));
  });

  test("testToString", () => {
    expect(seq("").toString()).toEqual("");
    expect(seq("012345").toString()).toEqual("012345");
  });
});

describe("PhoneNumber", () => {
  test("testFromE164", () => {
    let number: PhoneNumber = e164("+44123456789");
    expect(number.length()).toEqual(11);
    expect(number.toString()).toEqual("+44123456789");
  });

  test("testOf", () => {
    let cc = seq("44");
    let nn = seq("7471920002");
    let number: PhoneNumber = PhoneNumber.of(cc, nn);
    expect(number.getCallingCode()).toEqual(cc);
    expect(number.getNationalNumber()).toEqual(nn);
  });

  test("testCallingCodeAndNationalNumber", () => {
    let oneDigitCc: PhoneNumber = e164("+15515817989");
    expect(oneDigitCc.getCallingCode()).toEqual(seq("1"));
    expect(oneDigitCc.getNationalNumber()).toEqual(seq("5515817989"));

    let twoDigitCc: PhoneNumber = e164("+447471920002");
    expect(twoDigitCc.getCallingCode()).toEqual(seq("44"));
    expect(twoDigitCc.getNationalNumber()).toEqual(seq("7471920002"));

    let threeDigitCc: PhoneNumber = e164("+962707952933");
    expect(threeDigitCc.getCallingCode()).toEqual(seq("962"));
    expect(threeDigitCc.getNationalNumber()).toEqual(seq("707952933"));
  });

  test("testEquality", () => {
    let number: PhoneNumber = e164("+447471920002");
    // Parse from same input is equal.
    expect(number).toEqual(e164("+447471920002"));
    expect(number).not.toEqual(e164("+447471929999"));
  });
});

const pnc = new TestClassifier("tests/lpn_dfa_compact.json");

describe("AbstractPhoneNumberClassifier", () => {
  test('testGetPossibleValues', () => {
    expect(pnc.forRegion().getPossibleValues(e164("+447700112345")))
      .toEqual(new Set(["GB"]));
    expect(pnc.forRegion().getPossibleValues(e164("+447700312345")))
      .toEqual(new Set(["JE"]));
    expect(pnc.forRegion().getPossibleValues(e164("+447700")))
      .toEqual(new Set(["GB", "JE"]));
  });
});

describe("PhoneNumberFormatter", () => {
  test('testCompleteNumberFormatting', () => {
    expect(pnc.formatNational(e164("+447700112345"))).toEqual("07700 112345");
    expect(pnc.formatInternational(e164("+447700112345"))).toEqual("+44 7700 112345");
  });

  test('testOptionalGroupFormatting', () => {
    // Use RU toll free numbers since they have an optional group in the middle.
    expect(pnc.formatNational(e164("+55800123456"))).toEqual("0800 12 3456");
    expect(pnc.formatNational(e164("+558001234567"))).toEqual("0800 123 4567");
  });

  test('testAsYouTypeFormatting', () => {
    // Use BR variable cost numbers since they have an optional group in the middle ("#XXX XX* XXXX").
    expect(pnc.formatNational(e164("+5580"))).toEqual("080");
    expect(pnc.formatNational(e164("+55800"))).toEqual("0800");
    expect(pnc.formatNational(e164("+558001"))).toEqual("0800 1");
    expect(pnc.formatNational(e164("+5580012"))).toEqual("0800 12");
    expect(pnc.formatNational(e164("+55800123"))).toEqual("0800 12 3");
    expect(pnc.formatNational(e164("+558001234"))).toEqual("0800 12 34");
    expect(pnc.formatNational(e164("+5580012345"))).toEqual("0800 12 345");
    expect(pnc.formatNational(e164("+55800123456"))).toEqual("0800 12 3456");
    expect(pnc.formatNational(e164("+558001234567"))).toEqual("0800 123 4567");
    expect(pnc.formatNational(e164("+5580012345678"))).toEqual("0800 123 45678");
    expect(pnc.formatNational(e164("+55800123456789"))).toEqual("0800 123 456789");
  });
});

describe("PhoneNumberParser", () => {
  test('testRegions', () => {
    expect(pnc.getParser().getRegions(seq("1"))).toEqual([
        "US", "AG", "AI", "AS", "BB", "BM", "BS", "CA", "DM", "DO", "GD", "GU",
        "JM", "KN", "KY", "LC", "MP", "MS", "PR", "SX", "TC", "TT", "VC", "VG", "VI"]);
    expect(pnc.getParser().getRegions(seq("44"))).toEqual(["GB", "GG", "IM", "JE"]);
  });

  test('testCallingCode', () => {
    expect(pnc.getParser().getCallingCode("US")).toEqual(seq("1"));
    expect(pnc.getParser().getCallingCode("CA")).toEqual(seq("1"));
    expect(pnc.getParser().getCallingCode("GB")).toEqual(seq("44"));
    expect(pnc.getParser().getCallingCode("JE")).toEqual(seq("44"));
  });

  test('testExampleNumbers', () => {
    let exampleNumber = pnc.getParser().getExampleNumberForRegion("GB")!;
    expect(exampleNumber).toEqual(e164("+447400123456"));
    expect(pnc.getParser().getExampleNumber(seq("44"))).toEqual(exampleNumber);
    // Jersey (also +44) example number is not equal.
    expect(pnc.getParser().getExampleNumberForRegion("JE")).not.toEqual(exampleNumber);
    // Check the example number is considered valid.
    expect(pnc.match(exampleNumber)).toEqual(MatchResult.Matched);

    // Even when regions are strings, we can't accidentally ask for the example of a calling code.
    expect(pnc.getParser().getExampleNumber(seq("888"))).not.toEqual(null);
    expect(pnc.getParser().getExampleNumberForRegion("888")).toEqual(null);
  });

  // Example of a case where Libphonenumber fails to parse properly.
  // https://libphonenumber.appspot.com/phonenumberparser?number=%288108%29+6309+390+906&country=RU
  test('testParseBetterThanLibphonenumber', () => {
    // The example number is a 14-digit toll free number (from 7/ranges.csv):
    //   8108  ; 14  ; FIXED_LINE  ; TOLL_FREE
    // The optional national prefix is also '8', but isn't included for this input.
    // Libphonenumber gets brutally confused and returns a result of +86 309390906.
    let result = pnc.getParser().parseStrictlyForRegion("(8108) 6309 390 906", "RU");
    expect(result.getPhoneNumber().getCallingCode()).toEqual(seq("7"));
    expect(result.getPhoneNumber().getNationalNumber()).toEqual(seq("81086309390906"));
    expect(result.getMatchResult()).toEqual(MatchResult.Matched);
  });

  test('testParseUnsupportedNumber', () => {
    // All calling codes starting with 9 should be unsupported in the metadata for this test.
    expect(pnc.isSupportedCallingCode(seq("90"))).toEqual(false);

    let result = pnc.getParser().parseStrictly("+90 800 471 709298");
    expect(result.getPhoneNumber().getCallingCode()).toEqual(seq("90"));
    expect(result.getPhoneNumber().getNationalNumber()).toEqual(seq("800471709298"));
    expect(result.getMatchResult()).toEqual(MatchResult.Invalid);

    // For unsupported calling codes, national number parsing may give bogus results.
    let bogus = pnc.getParser().parseStrictly("0800 471 709298", seq("90"));
    expect(bogus.getPhoneNumber().getCallingCode()).toEqual(seq("90"));
    expect(bogus.getPhoneNumber().getNationalNumber()).toEqual(seq("0800471709298"));
    expect(bogus.getMatchResult()).toEqual(MatchResult.Invalid);
  });
});

describe("GoldenDataTest", () => {
  test('testGoldenData', () => {
    let raw: RawClassifier = pnc.rawClassifierForTests();
    goldenDataJson.testdata.forEach(gd => {
      let cc: DigitSequence = seq(gd.cc);
      let nn: DigitSequence = seq(gd.number);
      if (raw.isSupportedCallingCode(cc)) {
        gd.result.forEach(r => {
          expect(raw.classify(cc, nn, r.type)).toEqual(new Set(r.values));
        });
        // If a valid number is supported in the metadata it is parsed successfully from any format.
        gd.format.forEach(r => {
          expect(pnc.formatForTests(cc, nn, r.type)).toEqual(r.value);
          let res = pnc.getParser().parseStrictly(r.value, cc);
          expect(res.getPhoneNumber()).toEqual(PhoneNumber.of(cc, nn));
          expect(res.getMatchResult()).toEqual(MatchResult.Matched);
        });
      } else {
        // If a valid number is unsupported in the metadata it can be parsed from international
        // format, but cannot be classified (it's always considers "invalid").
        gd.format.filter(r => r.type === "INTERNATIONAL_FORMAT").forEach(r => {
          let res = pnc.getParser().parseStrictly(r.value, cc);
          expect(res.getPhoneNumber()).toEqual(PhoneNumber.of(cc, nn));
          expect(res.getMatchResult()).toEqual(MatchResult.Invalid);
        });
      }
    });
  });
});
