/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

import {
    AbstractPhoneNumberClassifier,
    FormatType,
    PhoneNumber,
    PhoneNumberFormatter,
    PhoneNumberParser,
    SchemaVersion } from "phonenumbers_js";
import { Converter, MetadataJson } from "phonenumbers_js/dist/internal.js";
import metadataJson from "./simple_compact.json";

export class SimplePhoneNumberValidator extends AbstractPhoneNumberClassifier {
  private static readonly ExpectedSchema: SchemaVersion =
      new SchemaVersion("goui.net/phonenumbers/simple/validation_only", 1);

  private readonly nationalFormatter: PhoneNumberFormatter;
  private readonly internationalFormatter: PhoneNumberFormatter;
  private readonly parser: PhoneNumberParser<string>;

  constructor() {
    super(metadataJson as MetadataJson, SimplePhoneNumberValidator.ExpectedSchema);
    this.nationalFormatter = super.createFormatter(FormatType.National);
    this.internationalFormatter = super.createFormatter(FormatType.International);
    this.parser = super.createParser(Converter.identity());
  }

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
