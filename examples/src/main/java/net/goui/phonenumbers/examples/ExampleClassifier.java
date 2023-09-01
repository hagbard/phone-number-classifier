/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static java.util.function.Function.identity;
import static net.goui.phonenumbers.FormatType.INTERNATIONAL;
import static net.goui.phonenumbers.FormatType.NATIONAL;

import com.google.common.annotations.VisibleForTesting;
import net.goui.phonenumbers.AbstractPhoneNumberClassifier;
import net.goui.phonenumbers.PhoneNumberFormatter;
import net.goui.phonenumbers.PhoneNumberParser;
import net.goui.phonenumbers.metadata.RawClassifier;

public final class ExampleClassifier extends AbstractPhoneNumberClassifier {
  private static final ExampleClassifier INSTANCE =
      new ExampleClassifier(
          AbstractPhoneNumberClassifier.loadRawClassifier(
              SchemaVersion.of("goui.net/phonenumbers/examples/simple/dfa/minimal", 1)));

  public static ExampleClassifier getInstance() {
    return INSTANCE;
  }

  private final PhoneNumberFormatter nationalFormatter = createFormatter(NATIONAL);
  private final PhoneNumberFormatter internationalFormatter = createFormatter(INTERNATIONAL);
  private final PhoneNumberParser<String> parser = createParser(identity());

  private ExampleClassifier(RawClassifier rawClassifier) {
    super(rawClassifier);
  }

  @VisibleForTesting
  RawClassifier rawClassifierForTesting() {
    return rawClassifier();
  }

  public PhoneNumberFormatter national() {
    return nationalFormatter;
  }

  public PhoneNumberFormatter international() {
    return internationalFormatter;
  }

  public PhoneNumberParser<String> getParser() {
    return parser;
  }
}
