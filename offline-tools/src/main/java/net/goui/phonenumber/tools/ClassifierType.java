/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.UnaryOperator.identity;

import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.model.RangesTableSchema;
import com.google.i18n.phonenumbers.metadata.table.Column;
import com.google.i18n.phonenumbers.metadata.table.ColumnGroup;
import com.google.i18n.phonenumbers.metadata.table.RangeTable;
import java.util.function.Function;
import java.util.stream.Stream;

@AutoValue
abstract class ClassifierType {
  // If anything is added here, also add it to Metadata#getRangeMapForTable().
  public static final ClassifierType TYPE = ClassifierType.create("TYPE");
  public static final ClassifierType TARIFF = ClassifierType.create("TARIFF");
  public static final ClassifierType AREA_CODE_LENGTH = ClassifierType.create("AREA_CODE_LENGTH");
  public static final ClassifierType FORMAT = ClassifierType.create("FORMAT");
  public static final ClassifierType REGION = ClassifierType.create("REGION");

  // Synthetic classifier used when validation ranges are transformed.
  static final ClassifierType VALIDITY = ClassifierType.create("VALIDITY");

  private static final ImmutableMap<
          ClassifierType, Function<RangeTable, ImmutableMap<String, RangeTree>>>
      EXTRACT_FN =
          ImmutableMap.of(
              TYPE, t -> extract(t, RangesTableSchema.TYPE),
              TARIFF, t -> extract(t, RangesTableSchema.TARIFF),
              AREA_CODE_LENGTH, t -> extract(t, RangesTableSchema.AREA_CODE_LENGTH),
              FORMAT, t -> extract(t, RangesTableSchema.FORMAT),
              REGION, t -> extract(t, RangesTableSchema.REGIONS));

  private static final ImmutableMap<String, ClassifierType> BASE_TYPES =
      Stream.concat(EXTRACT_FN.keySet().stream(), Stream.of(VALIDITY))
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
    return create(id);
  }

  private static ImmutableMap<String, RangeTree> extract(RangeTable table, Column<?> column) {
    ImmutableMap.Builder<String, RangeTree> map = ImmutableMap.builder();
    table.getAssignedValues(column).forEach(k -> map.put(k.toString(), table.getRanges(column, k)));
    return map.buildOrThrow();
  }

  private static ImmutableMap<String, RangeTree> extract(
      RangeTable table, ColumnGroup<?, Boolean> group) {
    ImmutableMap.Builder<String, RangeTree> map = ImmutableMap.builder();
    group
        .extractGroupColumns(table.getColumns())
        .forEach((k, c) -> map.put(k.toString(), table.getRanges(c, true)));
    return map.buildOrThrow();
  }

  private static ClassifierType create(String id) {
    return new AutoValue_ClassifierType(id);
  }

  public final ImmutableMap<String, RangeTree> extractRangesFrom(RangeTable table) {
    checkState(isBaseType(), "cannot extract custom ranges from a raw table: %s", this);
    return checkNotNull(EXTRACT_FN.get(this), "unsupported classifier type: %s", this).apply(table);
  }

  public abstract String id();

  public final boolean isBaseType() {
    return EXTRACT_FN.containsKey(this);
  }
}
