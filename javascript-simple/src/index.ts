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

/**
 * A simple phone number validator which illustrates the basic capabilities of the PhoneNumbers
 * library. This class illustrates the basic use of the core PhoneNumbers classes, and shows how
 * they can be used to create customized phone number APIs.
 *
 * While it is possible to use this class "as is" in your application, it strongly recommended that
 * you consider making your own version of it so you can tune the API and metadata to your needs.
 *
 * It is also worth noting that there is no promise that the metadata used in this example code
 * will be updated regularly, so using this directly in an application risks eventual metadata
 * accuracy issues.
 *
 * It is straightforward to define your own subclass of AbstractPhoneNumberClassifier, with the
 * features you need, and build you own metadata to support it. You have complete control over
 * the degree of accuracy you want in the metadata and which classifiers you present to your users
 * (including writing your own custom classifiers). Once you have defined your own metadata config,
 * it is trivial to keep your metadata as up-to-date as you need.
 */
export class SimplePhoneNumberValidator extends AbstractPhoneNumberClassifier {
  /**
   * A SchemaVersion defines which features are expected to be supported in the provided metadata.
   *
   * The following core features are supported by all metadata:
   * 1. Determining the supported calling codes (via isSupportedCallingCode()).
   * 2. Basic phone number validation (via testLength() and match()).
   *
   * This metadata schema supports the following optional features:
   * 1. National and international formatting of phone numbers (see below).
   * 2. Phone number parsing (see below).
   * 3. Example numbers (via getExampleNumber()).
   *
   * Note that this schema does not support any classification operations (e.g. determining the
   * type, tariff or region of phone numbers) as these are considered "advanced" operations and
   * their existence will increase the metadata size significantly.
   */
  private static readonly ExpectedSchema: SchemaVersion =
      new SchemaVersion("goui.net/phonenumbers/simple/validation_only", 1);

  /** A formatting API which formats numbers in "national" format. */
  private readonly nationalFormatter: PhoneNumberFormatter;
  /** A formatting API which formats numbers in "international" format. */
  private readonly internationalFormatter: PhoneNumberFormatter;
  /** A parsing API which can parse national and internationally formatted numbers. */
  private readonly parser: PhoneNumberParser<string>;

  /**
   * Creates in instance of SimplePhoneNumberValidator using the built in metadata.
   *
   * The job of this subclass constructor is:
   * 1: Initialize the parent class (must be first).
   * 2: Use the parent class's helper methods to initialize the supported APIs.
   *
   * Note that It is not best practice to build metadata into client code, but this is just a
   * simple example of the PhoneNumbers library. In the general case, it is expected that
   * up-tp-date metadata will be passed to the PhoneNumbers library at run time (either
   * downloaded on demand, or as part of an application which is served to users with static
   * metadata).
   */
  constructor() {
    super(metadataJson as MetadataJson, SimplePhoneNumberValidator.ExpectedSchema);
    this.nationalFormatter = super.createFormatter(FormatType.National);
    this.internationalFormatter = super.createFormatter(FormatType.International);
    this.parser = super.createParser(Converter.identity());
  }

  /**
   * Formats a phone number in international format (e.g. "+44 20 8743 8000") for dialling from
   * outside a country.
   *
   * This method will correctly handle partially entered (incomplete) phone numbers and will select
   * the most likely format. For partial numbers, this library often out-performs Libphonenumber.
   */
  formatNational(number: PhoneNumber): string {
    return this.nationalFormatter.format(number);
  }

  /**
   * Formats a phone number in national format (e.g. "020 8743 8000") for dialling from within a
   * country.
   *
   * This method will correctly handle partially entered (incomplete) phone numbers and will select
   * the most likely format. For partial numbers, this library often out-performs Libphonenumber.
   */
  formatInternational(number: PhoneNumber): string {
    return this.internationalFormatter.format(number);
  }

  /**
   * Returns a sorted list of the CLDR region codes (e.g. "US", GB", "CH" or "001") for the given
   * country calling code.
   *
   * This list is sorted alphabetically, except the first element, which is always the "main region"
   * associated with the calling code (e.g. "US" for "1", "GB" for "44").
   *
   * If the given calling code is not supported by the underlying metadata, the empty array is
   * returned.
   */
  getRegions(callingCode: DigitSequence): ReadonlyArray<string> {
    return this.parser.getRegions(callingCode);
  }

  /**
   * Returns the unique country calling code for a specified CLDR region code (e.g. "1" for "CA").
   *
   * If the given region code is not supported by the underlying metadata, null is returned (and
   * since the world region "001" is associated with more than one calling code, that will also
   * return null).
   */
  getCallingCode(regionCode: string): DigitSequence|null {
    return this.parser.getCallingCode(regionCode);
  }

  /**
   * Parses complete or partial phone number text with an optional country calling code.
   */
  parseLeniently(text: string, callingCode?: DigitSequence): PhoneNumber|null {
    return this.parser.parseLeniently(text, callingCode);
  }

  /**
   * Parses complete or partial phone number text for a specified region.
   */
  parseLenientlyForRegion(text: string, region: string): PhoneNumber|null {
    return this.parser.parseLenientlyForRegion(text, region);
  }

  /**
   * Parses complete or partial phone number text with an optional country calling code.
   */
  parseStrictly(text: string, callingCode?: DigitSequence): PhoneNumberResult {
    return this.parser.parseStrictly(text, callingCode);
  }

  /**
   * Parses complete or partial phone number text for a specified region.
   */
  parseStrictlyForRegion(text: string, region: T): PhoneNumberResult {
    return this.parser.parseStrictlyForRegion(text, region);
  }
}
