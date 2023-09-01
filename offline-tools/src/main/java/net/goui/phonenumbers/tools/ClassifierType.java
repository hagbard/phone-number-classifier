/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.UnaryOperator.identity;

import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.stream.Stream;

/**
 * Classifier types are a simplified representation of {@link
 * com.google.i18n.phonenumbers.metadata.table.RangeTable RangeTable} columns, used by the {@link
 * RangeMap RangeMap} class. They are less strongly types than {@code RangeTable} columns,
 * since they must be able to represent user created custom columns.
 */
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

  // Classifiers which can be specified in a 'classifier' message.
  static final ImmutableSet<ClassifierType> PUBLIC_CLASSIFIERS =
      ImmutableSet.of(TYPE, TARIFF, AREA_CODE_LENGTH, REGION);

  // Base types exist on the underlying metadata and do not need a prefixed name.
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

  /**
   * Returns whether a type is intended to classify a single value. "TARIFF" is an example of a
   * single valued type (i.e. a number only has one tariff), but "REGIONS" is multivalued (i.e. a
   * number can be assigned to several regions).
   *
   * <p>This distinction is useful since a single-valued type can have a different API, more suited
   * to its expected use.
   */
  public static boolean isSingleValued(ClassifierType type) {
    checkArgument(type.isBaseType(), "only applicable for base types: %s", type);
    return !type.id().equals(REGION.id());
  }

  /**
   * Create or return a classifier for the given identifier. If the given ID does not correspond to
   * a "base type", then it must take the form {@code "<namespace>:<id>"} where the namespace prefix
   * is used to avoid name clashes with base types. The namespace can be any short identifier and
   * would normally be shared by all custom types in a project.
   */
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

  /** Returns the ID string which uniquely identifies this type. */
  public abstract String id();

  /** Returns whether this type is a base type. */
  public abstract boolean isBaseType();
}
