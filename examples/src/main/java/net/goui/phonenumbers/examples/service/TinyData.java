/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.examples.service;

import com.google.auto.service.AutoService;
import net.goui.phonenumber.metadata.ClassifierService;
import net.goui.phonenumber.metadata.VersionInfo;
import net.goui.phonenumber.service.proto.AbstractResourceClassifierService;

@AutoService(ClassifierService.class)
public class TinyData extends AbstractResourceClassifierService {
  public TinyData() {
    super(VersionInfo.of("goui.net/libphonenumber/dfa/tiny", 1, 1, 0), "/lpn_dfa_tiny.pb");
  }
}
