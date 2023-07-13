/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static net.goui.phonenumber.PhoneNumbers.fromE164;

import net.goui.phonenumber.AbstractPhoneNumberClassifier.SchemaVersion;
import net.goui.phonenumber.PhoneNumber;
import net.goui.phonenumbers.examples.libphonenumber.LibPhoneNumberClassifier;

public class Simple {
  public static void main(String[] args) {
    LibPhoneNumberClassifier compact = LibPhoneNumberClassifier.load(SchemaVersion.of("goui.net/libphonenumber/dfa/compact", 1));
    LibPhoneNumberClassifier precise = LibPhoneNumberClassifier.load(SchemaVersion.of("goui.net/libphonenumber/dfa/precise", 1));

    PhoneNumber number = fromE164("+16502123456");
    System.out.format("%s: %s\n", number, precise.forType().identify(number));
    System.out.format("%s: %s\n", number, compact.forType().identify(number));
    System.out.format("%s: %s\n", number, precise.testLength(number));
    System.out.format("%s: %s\n", number, compact.testLength(number));

    PhoneNumber prefix = fromE164("+917678");
    System.out.format("%s: %s\n", prefix, compact.forType().getPossibleValues(prefix));
    prefix = fromE164("+9176781");
    System.out.format("%s: %s\n", prefix, compact.forType().getPossibleValues(prefix));

    prefix = fromE164("+9176782");
    System.out.format("%s: %s\n", prefix, compact.forType().getPossibleValues(prefix));

    prefix = fromE164("+917678");
    System.out.format("%s: %s\n", prefix, precise.forType().getPossibleValues(prefix));
    prefix = fromE164("+9176781");
    System.out.format("%s: %s\n", prefix, precise.forType().getPossibleValues(prefix));
    prefix = fromE164("+9176782");
    System.out.format("%s: %s\n", prefix, precise.forType().getPossibleValues(prefix));

    prefix = fromE164("+15661");
    System.out.format("%s: %s\n", prefix, precise.forType().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, compact.forType().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, precise.forRegion().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, compact.forRegion().getPossibleValues(prefix));

    prefix = fromE164("+15671");
    System.out.format("%s: %s\n", prefix, precise.match(prefix));
    System.out.format("%s: %s\n", prefix, compact.match(prefix));

    System.out.format("%s: %s\n", prefix, precise.forRegion().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, compact.forRegion().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, precise.testLength(prefix));
    System.out.format("%s: %s\n", prefix, compact.testLength(prefix));

    prefix = fromE164("+447700312345");
    System.out.format("%s: %s\n", prefix, compact.forRegion().getPossibleValues(prefix));
    System.out.format("%s: %s\n", prefix, precise.forRegion().getPossibleValues(prefix));
  }
}
