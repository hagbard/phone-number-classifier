/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples.libphonenumber;

import static net.goui.phonenumber.PhoneNumberFormatter.FormatType.INTERNATIONAL;
import static net.goui.phonenumber.PhoneNumberFormatter.FormatType.NATIONAL;

import com.ibm.icu.util.Region;
import net.goui.phonenumber.AbstractPhoneNumberClassifier;
import net.goui.phonenumber.PhoneNumberFormatter;
import net.goui.phonenumber.metadata.RawClassifier;

public final class ValidationOnlyNumberClassifier extends AbstractPhoneNumberClassifier {

  public static ValidationOnlyNumberClassifier load(SchemaVersion version, SchemaVersion... rest) {
    return new ValidationOnlyNumberClassifier(
        AbstractPhoneNumberClassifier.loadRawClassifier(version, rest));
  }

  private final PhoneNumberFormatter nationalFormatter = getFormatter(NATIONAL);
  private final PhoneNumberFormatter internationalFormatter = getFormatter(INTERNATIONAL);

  private ValidationOnlyNumberClassifier(RawClassifier rawClassifier) {
    super(rawClassifier);
  }

  RawClassifier rawClassifierForTesting() {
    return rawClassifier();
  }

  public PhoneNumberFormatter national() {
    return nationalFormatter;
  }

  public PhoneNumberFormatter international() {
    return internationalFormatter;
  }
}
