/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.metadata;

import com.google.auto.value.AutoValue;

/** Version information for phone number metadata. */
@AutoValue
public abstract class VersionInfo {
  /**
   * A URL/URI identifying the data schema. This defines the available data, such as the available
   * classifiers and their semantic meaning, as well as which representation(s) the matcher data
   * will be provided in.
   *
   * <p>Within a schema, all classifiers have a local namespace and keys such as "FIXED_LINE" can
   * mean semantically different things for different schemas.
   *
   * <p>This value may or may not reference a real web page, but if it does, that page should
   * explain the schema semantics.
   *
   * <p>Once published, a data schema should always retain the same semantics for existing
   * classifiers.
   *
   * <p>Code may accept any one of multiple schemas if their meanings are sufficiently similar, but
   * should not assume that the same classifier/key name(s) will imply the same semantics.
   */
  public abstract String getSchema();

  /**
   * The data schema version defining which classifiers are present. This should be increased when
   * new data is added to an existing schema. Code can rely on later versions of a schema being
   * backwards compatible with earlier code.
   */
  public abstract int getSchemaVersion();

  /**
   * Data structure major version, increased when incompatible changes are made to the protocol
   * buffer structure (e.g. removing fields). Code should test this version explicitly and reject
   * any unsupported/unknown major versions.
   *
   * <p>This relates only to the protocol buffer structure, and is unrelated to the semantic meaning
   * of the data held by this message.
   */
  public abstract int getMajorDataVersion();

  /**
   * Data structure minor version, increased when backwards compatible changes are made (e.g. adding
   * new fields which are optional for existing code). Code may rely on a larger minor version than
   * it was expecting, but not smaller.
   *
   * <p>This relates only to the protocol buffer structure, and is unrelated to the semantic meaning
   * of the data held by this message.
   */
  public abstract int getMinorDataVersion();

  /** Creates a schema version for the specified version information. */
  public static VersionInfo of(
      String schema, int schemaVersion, int majorDataVersion, int minorDataVersion) {
    return new AutoValue_VersionInfo(schema, schemaVersion, majorDataVersion, minorDataVersion);
  }

  /**
   * Returns whether this version would satisfy the given version (i.e. metadata with this version
   * is acceptable to someone who requires the given version). The rules for compatibility are:
   *
   * <ol>
   *   <li>Major data versions must be equal.
   *   <li>This minor data version must not be less than the given minor data version.
   *   <li>Schema URIs must be equal.
   *   <li>This schema version must not be less than the given schema version.
   * </ol>
   */
  public boolean satisfies(VersionInfo requestedVersion) {
    return getMajorDataVersion() == requestedVersion.getMajorDataVersion()
        && getMinorDataVersion() >= requestedVersion.getMinorDataVersion()
        && getSchema().equals(requestedVersion.getSchema())
        && getSchemaVersion() >= requestedVersion.getSchemaVersion();
  }
}
