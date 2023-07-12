/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples.libphonenumber;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.CharStreams;
import com.google.common.truth.Expect;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import net.goui.phonenumber.AbstractPhoneNumberClassifier;
import net.goui.phonenumber.testing.RegressionTester;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SimpleTest {
  @Rule public final Expect expect = Expect.create();

  @Test
  public void testGoldenData() throws IOException {
    LibPhoneNumberClassifier compact =
        LibPhoneNumberClassifier.load(
            AbstractPhoneNumberClassifier.SchemaVersion.of(
                "goui.net/libphonenumber/dfa/compact", 1));
    RegressionTester regressionTester =
        RegressionTester.forClassifier(compact.rawClassifierForTesting(), expect);
    try (Reader goldenData =
        new InputStreamReader(
            checkNotNull(SimpleTest.class.getResourceAsStream("/lpn_golden_data.json")), UTF_8)) {
      regressionTester.assertGoldenData(CharStreams.toString(goldenData));
    }
  }
}
