/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import static java.util.Arrays.asList;

import net.goui.phonenumbers.PhoneNumber;
import net.goui.phonenumbers.PhoneNumbers;
import net.goui.phonenumbers.examples.LibPhoneNumberClassifier.MetadataVariant;

public class LibPhoneNumberTool {

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: {COMPACT|PRECISE} {E.164 NUMBER}...");
      System.exit(1);
    }
    MetadataVariant variant = getMetadataVariant(args[0]);
    LibPhoneNumberClassifier classifier = LibPhoneNumberClassifier.load(variant);

    for (String s : asList(args).subList(1, args.length)) {
      System.out.println("---------------------------------------------");
      System.out.format("Input                 : %s\n", s);
      PhoneNumber number;
      try {
        number = PhoneNumbers.fromE164(s);
      } catch (IllegalArgumentException e) {
        System.out.format("Error [%s]          : %s\n", s, e.getMessage());
        continue;
      }
      System.out.format("Match Result          : %s\n", classifier.match(number));
      System.out.format(
          "Possible Type(s)      : %s\n", classifier.forType().getPossibleValues(number));
      System.out.format(
          "Possible Regions(s)   : %s\n", classifier.forRegion().getPossibleValues(number));
      System.out.format(
          "Calling Code Region(s): %s\n",
          classifier.getRegionInfo().getRegions(number.getCallingCode()));
      System.out.format("E.164 Format          : %s\n", number);
      System.out.format("National Format       : %s\n", classifier.national().format(number));
      System.out.format("International Format  : %s\n", classifier.international().format(number));
    }
    System.out.println("---------------------------------------------");
  }

  private static MetadataVariant getMetadataVariant(String s) {
    try {
      return MetadataVariant.valueOf(s);
    } catch (IllegalArgumentException e) {
      System.err.format("First argument must be one of: %s\n", asList(MetadataVariant.values()));
      System.exit(2);
      return null;
    }
  }
}
