/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static net.goui.phonenumbers.FormatType.INTERNATIONAL;
import static net.goui.phonenumbers.FormatType.NATIONAL;

import com.google.common.base.Ascii;
import com.ibm.icu.util.Region;
import net.goui.phonenumbers.AbstractPhoneNumberClassifier;
import net.goui.phonenumbers.PhoneNumberFormatter;
import net.goui.phonenumbers.PhoneNumberParser;
import net.goui.phonenumbers.metadata.RawClassifier;

public final class LibPhoneNumberClassifier extends AbstractPhoneNumberClassifier {

  /** Duplicates of the number types defined in the configuration file. */
  public enum NumberType {
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
    VOICEMAIL
  }

  /** Specifier for the available metadata versions. */
  public enum MetadataVariant {
    PRECISE,
    COMPACT;
  }

  public static LibPhoneNumberClassifier load(MetadataVariant version) {
    SchemaVersion schemaVersion =
        SchemaVersion.of(
            "goui.net/libphonenumber/examples/lpn/dfa/" + Ascii.toLowerCase(version.name()), 1);
    return new LibPhoneNumberClassifier(
        AbstractPhoneNumberClassifier.loadRawClassifier(schemaVersion));
  }

  private final SingleValuedMatcher<NumberType> typeMatcher =
      forEnum("LPN:TYPE", NumberType.class).singleValuedMatcher();
  private final Matcher<Region> regionMatcher =
      forType("REGION", Region.class, Region::getInstance, Object::toString).matcher();
  private final PhoneNumberFormatter nationalFormatter = createFormatter(NATIONAL);
  private final PhoneNumberFormatter internationalFormatter = createFormatter(INTERNATIONAL);
  private final PhoneNumberParser<Region> regionInfo = createParser(Region::getInstance);

  private LibPhoneNumberClassifier(RawClassifier rawClassifier) {
    super(rawClassifier);
  }

  RawClassifier rawClassifierForTesting() {
    return rawClassifier();
  }

  public SingleValuedMatcher<NumberType> forType() {
    return typeMatcher;
  }

  public Matcher<Region> forRegion() {
    return regionMatcher;
  }

  public PhoneNumberFormatter national() {
    return nationalFormatter;
  }

  public PhoneNumberFormatter international() {
    return internationalFormatter;
  }

  public PhoneNumberParser<Region> getRegionInfo() {
    return regionInfo;
  }
}
