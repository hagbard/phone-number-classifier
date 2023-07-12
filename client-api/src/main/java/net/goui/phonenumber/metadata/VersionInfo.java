/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.metadata;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class VersionInfo {
  public abstract String getSchema();

  public abstract int getSchemaVersion();

  public abstract int getMajorDataVersion();

  public abstract int getMinorDataVersion();

  public static VersionInfo of(
      String schema, int schemaVersion, int majorDataVersion, int minorDataVersion) {
    return new AutoValue_VersionInfo(schema, schemaVersion, majorDataVersion, minorDataVersion);
  }

  public boolean satisfies(VersionInfo requestedVersion) {
    return getMajorDataVersion() == requestedVersion.getMajorDataVersion()
        && getMinorDataVersion() >= requestedVersion.getMinorDataVersion()
        && getSchema().equals(requestedVersion.getSchema())
        && getSchemaVersion() >= requestedVersion.getSchemaVersion();
  }
}
