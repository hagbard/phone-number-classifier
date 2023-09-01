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
    PhoneNumberParser } from "../dist/index.js";
import { RawClassifier, Converter } from "../dist/internal.js";
const fs = require("fs");

export enum LpnType {
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

export class ExampleClassifier extends AbstractPhoneNumberClassifier {
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
