/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.UnaryOperator.identity;

import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Stream;

@AutoValue
abstract class ClassifierType {
  // If anything is added here, also add it to Metadata#getRangeMapForTable().
  public static final ClassifierType TYPE = ClassifierType.baseType("TYPE");
  public static final ClassifierType TARIFF = ClassifierType.baseType("TARIFF");
  public static final ClassifierType AREA_CODE_LENGTH = ClassifierType.baseType("AREA_CODE_LENGTH");
  public static final ClassifierType REGION = ClassifierType.baseType("REGION");
  public static final ClassifierType NATIONAL_FORMAT = ClassifierType.baseType("NATIONAL_FORMAT");
  public static final ClassifierType INTERNATIONAL_FORMAT =
      ClassifierType.baseType("INTERNATIONAL_FORMAT");

  // Synthetic classifier used when validation ranges are transformed.
  static final ClassifierType VALIDITY = ClassifierType.baseType("VALIDITY");

  private static final ImmutableMap<String, ClassifierType> BASE_TYPES =
      Stream.of(
              VALIDITY, // Added after RangeMap is loaded if configuration restricts validation.
              TYPE,
              TARIFF,
              AREA_CODE_LENGTH,
              REGION,
              NATIONAL_FORMAT, // Specific formats are de-normalized from the format ID.
              INTERNATIONAL_FORMAT)
          .collect(toImmutableMap(ClassifierType::id, identity()));

  public static boolean isSingleValued(ClassifierType type) {
    checkArgument(type.isBaseType(), "only applicable for base types: %s", type);
    return !type.id().equals(REGION.id());
  }

  public static ClassifierType of(String id) {
    id = Ascii.toUpperCase(id);
    ClassifierType type = BASE_TYPES.get(id);
    if (type != null) {
      return type;
    }
    checkArgument(
        id.matches(".+:[A-Z_]+"), "invalid custom type name (expected 'foo:BAR'): %s", id);
    return new AutoValue_ClassifierType(id, false);
  }

  private static ClassifierType baseType(String id) {
    return new AutoValue_ClassifierType(id, true);
  }

  public abstract String id();

  public abstract boolean isBaseType();
}
