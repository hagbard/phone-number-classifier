/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import { PhoneNumber, AbstractPhoneNumberClassifier, Matcher, SingleValuedMatcher, DigitSequence } from "../src/index";
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

  constructor(path: string) {
    super(fs.readFileSync(path, { encoding: "utf8", flag: "r" }));
    this.typeMatcher = super.forNumericEnum("LPN:TYPE", LpnType).singleValuedMatcher();
    // We don't have a region code enum to hand, so use strings directly.
    this.regionMatcher = super.forStrings("REGION").matcher();
  }

  rawClassifierForTests(): RawClassifier {
    return super.getRawClassifier();
  }

  forType(): SingleValuedMatcher<LpnType> { return this.typeMatcher; }

  forRegion(): Matcher<string> { return this.regionMatcher; }
}

const pnc = new TestClassifier("tests/lpn_dfa_compact.json");

describe("AbstractPhoneNumberClassifier", () => {

  test('getPossibleValues_uniqueValues', () => {
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700112345")))
      .toEqual(new Set(["GB"]));
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700312345")))
      .toEqual(new Set(["JE"]));
    });

  test('getPossibleValues_multipleValues', () => {
    expect(pnc.forRegion().getPossibleValues(PhoneNumber.fromE164("+447700")))
      .toEqual(new Set(["GB", "JE"]));
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
    });
  });
});
