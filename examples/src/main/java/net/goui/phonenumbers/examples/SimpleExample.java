/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples;

import net.goui.phonenumber.PhoneNumber;
import net.goui.phonenumber.PhoneNumbers;

/**
 * Simple command line tool which can be given one or more E.164 numbers as arguments to be
 * classified.
 */
public final class SimpleExample {
  private static final SimpleClassifier CLASSIFIER = SimpleClassifier.getInstance();

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: {E.164 NUMBER}...");
      System.exit(1);
    }
    for (String s : args) {
      System.out.println("---------------------------------------------");
      System.out.format("Input                 : %s\n", s);
      PhoneNumber number;
      try {
        number = PhoneNumbers.fromE164(s);
      } catch (IllegalArgumentException e) {
        System.out.format("Error [%s]          : %s\n", s, e.getMessage());
        continue;
      }
      System.out.format("Match Result          : %s\n", CLASSIFIER.match(number));
      System.out.format(
          "Regions               : %s\n", CLASSIFIER.forRegion().getPossibleValues(number));
      System.out.format(
          "Calling Code Region(s): %s\n",
          CLASSIFIER.getRegionInfo().getRegions(number.getCallingCode()));
      System.out.format("E.164 Format          : %s\n", number);
      System.out.format("National Format       : %s\n", CLASSIFIER.national().format(number));
      System.out.format("International Format  : %s\n", CLASSIFIER.international().format(number));
    }
    System.out.println("---------------------------------------------");
  }

  private SimpleExample() {}
}
