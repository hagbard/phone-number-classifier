/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.ibm.icu.util.Region;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import net.goui.phonenumbers.PhoneNumber;
import net.goui.phonenumbers.PhoneNumberResult;
import net.goui.phonenumbers.examples.LibPhoneNumberClassifier.MetadataVariant;
import net.goui.phonenumbers.examples.LibPhoneNumberClassifier.NumberType;

public class ClassifierDemo {
  public static void main(String[] args) {
    Scanner in = new Scanner(System.in);

    MetadataVariant variant;
    System.out.println("Enter classifier variant 'PRECISE' or 'COMPACT' (default):");
    try {
      variant = MetadataVariant.valueOf(Ascii.toUpperCase(in.nextLine()));
    } catch (IllegalArgumentException invalid) {
      System.out.println("Did not recognize input (using COMPACT).");
      variant = MetadataVariant.COMPACT;
    }
    LibPhoneNumberClassifier classifier = LibPhoneNumberClassifier.load(variant);
    while (true) {
      System.out.println("----");
      String input;
      do {
        System.out.println(
            "Enter 'region-code: phone-number' to parse (e.g. 'US: (201) 555-0123'):");
        input = in.nextLine();
      } while (!input.isEmpty() && !input.matches("[A-Z]{2}:.*"));
      if (input.isEmpty()) {
        break;
      }
      List<String> parts = Splitter.on(':').trimResults().limit(2).splitToList(input);
      PhoneNumberResult<Region> result;
      try {
        Region region = Region.getInstance(parts.get(0));
        result = classifier.getParser().parseStrictly(parts.get(1), region);
      } catch (IllegalArgumentException invalid) {
        System.out.println("Could not parse input: " + input);
        continue;
      }
      PhoneNumber parsed = result.getPhoneNumber();
      System.out.println("Phone number is: " + parsed);
      switch (result.getMatchResult()) {
        case MATCHED:
          System.out.println("Phone number is matched and valid.");
          System.out.println("National format: " + classifier.national().format(parsed));
          System.out.println("International format: " + classifier.international().format(parsed));
          // If the number was valid match, the type must be valid.
          NumberType type = classifier.forType().identify(parsed).orElseThrow();
          Set<Region> regions = classifier.forRegion().getPossibleValues(parsed);
          System.out.println("Libphonenumber type: " + type);
          System.out.println("Possible region(s): " + regions);
          break;
        case PARTIAL_MATCH:
          System.out.println("Phone number is a partial match (add digits to make it valid).");
          break;
        case EXCESS_DIGITS:
          System.out.println("Phone number has excess digits (remove digits to make it valid).");
          break;
        case POSSIBLE_LENGTH:
          System.out.println(
              "Phone number is invalid, but its length is the same as a valid number.");
          break;
        case INVALID:
          System.out.println("Phone number is invalid.");
          break;
      }
    }
    System.out.println("---- EXITING ----");
  }
}
