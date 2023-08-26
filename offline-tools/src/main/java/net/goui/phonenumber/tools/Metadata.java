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
import static net.goui.phonenumber.tools.ClassifierType.VALIDITY;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.i18n.PhoneRegion;
import com.google.i18n.phonenumbers.metadata.model.ExamplesTableSchema;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec;
import com.google.i18n.phonenumbers.metadata.model.FormatsTableSchema;
import com.google.i18n.phonenumbers.metadata.model.MetadataTableSchema;
import com.google.i18n.phonenumbers.metadata.model.RangesTableSchema;
import com.google.i18n.phonenumbers.metadata.proto.Types.ValidNumberType;
import com.google.i18n.phonenumbers.metadata.table.Column;
import com.google.i18n.phonenumbers.metadata.table.ColumnGroup;
import com.google.i18n.phonenumbers.metadata.table.CsvTable;
import com.google.i18n.phonenumbers.metadata.table.RangeTable;
import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Encapsulation of phone number metadata for classifiers, either for underlying metadata or custom
 * metadata with simplified ranges or custom classifiers.
 */
@AutoValue
abstract class Metadata {
  // Argentina is ... special.
  private static final DigitSequence CC_ARGENTINA = DigitSequence.of("54");

  static final class Builder {
    private final CsvTable<DigitSequence> root;
    private final ImmutableMap.Builder<DigitSequence, RangeMap> callingCodeMap =
        ImmutableMap.builder();

    private Builder(CsvTable<DigitSequence> root) {
      this.root = root;
    }

    @CanIgnoreReturnValue
    public Builder put(DigitSequence cc, RangeMap rangeMap) {
      callingCodeMap.put(cc, rangeMap);
      return this;
    }

    public Metadata build() {
      return create(root, callingCodeMap.buildOrThrow());
    }
  }

  public static Builder builder(CsvTable<DigitSequence> root) {
    return new Builder(root);
  }

  private static Metadata create(
      CsvTable<DigitSequence> root, ImmutableMap<DigitSequence, RangeMap> callingCodeMap) {
    checkArgument(!callingCodeMap.isEmpty(), "must have at least one calling code: %s", root);
    RangeMap firstMap = callingCodeMap.values().asList().get(0);
    // All maps must share the same types in the same order.
    ImmutableList<ClassifierType> expectedTypesList = firstMap.getTypes().asList();
    for (RangeMap map : callingCodeMap.values()) {
      checkArgument(map.getTypes().asList().equals(expectedTypesList));
    }
    return new AutoValue_Metadata(root, expectedTypesList, callingCodeMap);
  }

  /**
   * Loads underlying data from the specified zip file, with optional overlay directory for local
   * data patching.
   */
  public static Metadata load(String zipFilePath, String overlayDirPath, String csvSeparator)
      throws IOException {

    checkArgument(
        !zipFilePath.isEmpty() || !overlayDirPath.isEmpty(),
        "Must specify a zip file path or an overlay directory (or both)");
    checkArgument(
        csvSeparator.length() == 1,
        "bad CSV separator for overlay files (must be single char): %s",
        csvSeparator);

    CsvTable<DigitSequence> root;
    ImmutableMap.Builder<DigitSequence, RangeMap> callingCodeMap = ImmutableMap.builder();
    try (TableLoader loader =
        new TableLoader(zipFilePath, overlayDirPath, csvSeparator.charAt(0))) {
      root = loader.load("metadata/metadata.csv", MetadataTableSchema.SCHEMA);
      for (DigitSequence cc : root.getKeys()) {
        RangeTable rangeTable =
            RangesTableSchema.toRangeTable(
                loader.load(csvFile(cc, "ranges"), RangesTableSchema.SCHEMA));
        ImmutableMap<String, FormatSpec> formatsTable =
            FormatsTableSchema.toFormatSpecs(
                loader.load(csvFile(cc, "formats"), FormatsTableSchema.SCHEMA));
        ImmutableTable<PhoneRegion, ValidNumberType, DigitSequence> exampleNumbers =
            ExamplesTableSchema.toExampleTable(
                loader.load(csvFile(cc, "examples"), ExamplesTableSchema.SCHEMA));

        if (cc.equals(CC_ARGENTINA)) {
          RangeTable rewrittenTable =
              SpecialCaseArgentinaRanges.addSyntheticMobileRanges(rangeTable, formatsTable);
          formatsTable =
              SpecialCaseArgentinaRanges.addSyntheticMobileFormats(rangeTable, formatsTable);
          rangeTable = rewrittenTable;
        }

        // Load the preferred national prefix (first element), used to build formatter data.
        ImmutableList<DigitSequence> nationalPrefixes =
            root.get(cc, MetadataTableSchema.NATIONAL_PREFIX)
                .map(s -> s.getValues().asList())
                .orElse(ImmutableList.of());

        callingCodeMap.put(
            cc, getRangeMapForTable(rangeTable, formatsTable, nationalPrefixes, exampleNumbers));
      }
    }
    return create(root, callingCodeMap.buildOrThrow());
  }

  private static String csvFile(DigitSequence cc, String baseName) {
    return String.format("metadata/%s/%s.csv", cc, baseName);
  }

  private static RangeMap getRangeMapForTable(
      RangeTable rangeTable,
      ImmutableMap<String, FormatSpec> formatsTable,
      ImmutableList<DigitSequence> nationalPrefixes,
      ImmutableTable<PhoneRegion, ValidNumberType, DigitSequence> exampleNumbers) {

    DigitSequence primaryNationalPrefix =
        Iterables.getFirst(nationalPrefixes, DigitSequence.empty());
    ImmutableList<BiConsumer<RangeTable, RangeMap.Builder>> extractFunctions =
        ImmutableList.of(
            extractColumn(RangesTableSchema.TYPE, ClassifierType.TYPE),
            extractColumn(RangesTableSchema.TARIFF, ClassifierType.TARIFF),
            extractColumn(RangesTableSchema.AREA_CODE_LENGTH, ClassifierType.AREA_CODE_LENGTH),
            extractGroup(RangesTableSchema.REGIONS, ClassifierType.REGION),
            extractFormat(formatsTable, primaryNationalPrefix));

    // In the format specifier, "national prefix optional" means:
    //   "If there is a national prefix, it's optional."
    // For parsing we want to know:
    //   "Is there any format in which the national prefix is not present?"
    // So we can't just look at nationalPrefixOptional(), as that is NOT set when there is no
    // national prefix at all. We have to also count any formats without a national format.
    boolean nationalPrefixOptionalForParsing =
        formatsTable.values().stream()
            .anyMatch(f -> !f.national().hasNationalPrefix() || f.nationalPrefixOptional());

    RangeMap.Builder builder =
        RangeMap.builder()
            .setExampleNumbers(exampleNumbers)
            .setNationalPrefixOptional(nationalPrefixOptionalForParsing);
    extractFunctions.forEach(fn -> fn.accept(rangeTable, builder));
    return builder.build(rangeTable.getAllRanges());
  }

  private static BiConsumer<RangeTable, RangeMap.Builder> extractColumn(
      Column<?> column, ClassifierType type) {
    return (table, out) -> {
      RangeClassifier.Builder classifier = RangeClassifier.builder().setSingleValued(true);
      table
          .getAssignedValues(column)
          .forEach(k -> classifier.put(k.toString(), table.getRanges(column, k)));
      out.put(type, classifier.build());
    };
  }

  private static BiConsumer<RangeTable, RangeMap.Builder> extractGroup(
      ColumnGroup<?, ?> group, ClassifierType type) {
    return (table, out) -> {
      RangeClassifier.Builder classifier = RangeClassifier.builder().setSingleValued(false);
      group
          .extractGroupColumns(table.getColumns())
          .forEach((k, c) -> classifier.put(k.toString(), table.getRanges(c, true)));
      out.put(type, classifier.build());
    };
  }

  private static BiConsumer<RangeTable, RangeMap.Builder> extractFormat(
      ImmutableMap<String, FormatSpec> formatsTable, DigitSequence nationalPrefix) {
    return (table, out) -> {
      RangeClassifier.Builder nationalFormat = RangeClassifier.builder().setSingleValued(true);
      RangeClassifier.Builder intlFormat = RangeClassifier.builder().setSingleValued(true);
      for (String formatId : table.getAssignedValues(RangesTableSchema.FORMAT)) {
        FormatSpec formatSpec =
            checkNotNull(
                formatsTable.get(formatId), "missing format specification for ID: %s", formatId);
        RangeTree formatRange = table.getRanges(RangesTableSchema.FORMAT, formatId);
        nationalFormat.put(
            FormatCompiler.compileSpec(formatSpec.national(), nationalPrefix), formatRange);
        // We MUST NOT skip adding ranges (even when no international format exists) because
        // otherwise
        // range simplification risks overwriting unassigned ranges. Since both columns have exactly
        // the same ranges, simplification will create the same end ranges, which will be shared.
        // Thus, the overhead for adding this "duplicated" data here is almost zero.
        intlFormat.put(
            formatSpec
                .international()
                .map(f -> FormatCompiler.compileSpec(f, nationalPrefix))
                .orElse(""),
            formatRange);
      }
      out.put(ClassifierType.NATIONAL_FORMAT, nationalFormat.build());
      out.put(ClassifierType.INTERNATIONAL_FORMAT, intlFormat.build());
    };
  }

  abstract CsvTable<DigitSequence> root();

  public abstract ImmutableList<ClassifierType> getTypes();

  abstract ImmutableMap<DigitSequence, RangeMap> rangeData();

  public final ImmutableSet<DigitSequence> getAvailableCallingCodes() {
    return rangeData().keySet();
  }

  /** Returns the range map for a calling code. */
  public final RangeMap getRangeMap(DigitSequence cc) {
    RangeMap rangeTable = rangeData().get(cc);
    checkArgument(rangeTable != null, "no data for calling code: %s", cc);
    return rangeTable;
  }

  /**
   * Returns a transformed metadata instance, usually with reduced or custom types. This operation
   * should occur before simplification to obtain the metadata of a user configuration.
   */
  public Metadata transform(RangeMapTransformer outputTransformer) {
    Metadata.Builder transformed = Metadata.builder(root());
    ImmutableSet<DigitSequence> callingCodes = getAvailableCallingCodes();
    for (DigitSequence cc : callingCodes) {
      transformed.put(cc, outputTransformer.apply(getRangeMap(cc)));
    }
    return transformed.build();
  }

  /**
   * Trims metadata according to an internally held (special) "validation" type. This should be
   * called after simplification to apply a range restriction to the simplified ranges. This avoids
   * either:
   *
   * <ul>
   *   <li>Removing ranges before simplification, and then overwriting them during simplification.
   *   <li>Restricting simplified metadata to some originally determined range, and leaving unwanted
   *       expanded range assignments in.
   * </ul>
   */
  public final Metadata trimValidRanges() {
    if (!getTypes().contains(VALIDITY)) {
      return this;
    }
    Metadata.Builder trimmedMetadata = Metadata.builder(root());
    for (DigitSequence cc : getAvailableCallingCodes()) {
      trimmedMetadata.put(cc, getRangeMap(cc).trimValidRanges());
    }
    return trimmedMetadata.build();
  }
}
