/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.i18n.phonenumbers.metadata.model.ExamplesTableSchema.NUMBER;
import static com.google.i18n.phonenumbers.metadata.model.FormatsTableSchema.INTERNATIONAL;
import static com.google.i18n.phonenumbers.metadata.model.FormatsTableSchema.NATIONAL;
import static com.google.i18n.phonenumbers.metadata.model.FormatsTableSchema.NATIONAL_PREFIX_OPTIONAL;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.AREA_CODE_LENGTH;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.CSV_REGIONS;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.FORMAT;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.NATIONAL_ONLY;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.TARIFF;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.TYPE;
import static com.google.i18n.phonenumbers.metadata.table.CsvTable.DiffMode.CHANGES;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.model.*;
import com.google.i18n.phonenumbers.metadata.model.CsvData.CsvDataProvider;
import com.google.i18n.phonenumbers.metadata.table.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiffManager {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final class Flags {
    @Parameter(names = "--zip", description = "Standard format zip file path")
    private String zipPath = "";

    @Parameter(names = "--log_level", description = "JDK log level name")
    private String logLevel = "WARNING";
  }

  private static void setLogging(String levelName) {
    Level level = Level.parse(levelName);
    Arrays.stream(Logger.getLogger("").getHandlers()).forEach(h -> h.setLevel(level));
    Logger.getLogger("net.goui.phonenumber").setLevel(level);
  }

  public static void main(String[] args) throws IOException {
    Flags flags = new Flags();
    JCommander.newBuilder().addObject(flags).build().parse(args);
    setLogging(flags.logLevel);

    try (TableLoader loader =
        new TableLoader(flags.zipPath, "", 'x')) {

      // Load the root metadata directly from the zip file.
      CsvTable<DigitSequence> root =
          loader.loadFromZipFile("metadata/metadata.csv", MetadataTableSchema.SCHEMA);
      CsvTable<DigitSequence> resourcesRoot =
          TableLoader.loadFromResource("metadata/metadata.csv", MetadataTableSchema.SCHEMA)
              .orElse(root);
      checkState(
          root.getKeys().containsAll(resourcesRoot.getKeys()),
          "Overlay metadata cannot contain additional calling codes: %s",
          Sets.difference(resourcesRoot.getKeys(), root.getKeys()));
      System.out.println(
          "Removed calling codes: " + Sets.difference(root.getKeys(), resourcesRoot.getKeys()));
      ImmutableSet<DigitSequence> modifiedCallingCodes =
          resourcesRoot.getKeys().stream()
              .filter(DiffManager::hasOverlayResource)
              .collect(toImmutableSet());

      CsvDataLoader zipLoader = new CsvDataLoader(loader, root);
      CsvDataLoader resourceLoader = zipLoader.resourceLoader(resourcesRoot);

      CsvTable<DiffKey<DigitSequence>> rootDiff = CsvTable.diff(root, resourcesRoot, CHANGES);
      if (!rootDiff.isEmpty()) {
        System.out.println("==== ROOT " + Strings.repeat("=", 120));
        System.out.println(rootDiff);
      }

      for (DigitSequence cc : modifiedCallingCodes) {
        System.out.println("==== " + cc + " " + Strings.repeat("=", 120));
        CsvData.Diff diff = CsvData.diff(zipLoader.loadData(cc), resourceLoader.loadData(cc));
        diff.rangesDiff().ifPresent(System.out::println);
        diff.formatsDiff().ifPresent(System.out::println);
        diff.examplesDiff().ifPresent(System.out::println);
      }
    }
  }

  static class CsvDataLoader implements CsvDataProvider {
    protected final TableLoader loader;
    private final CsvTable<DigitSequence> root;

    CsvDataLoader(TableLoader loader, CsvTable<DigitSequence> root) {
      this.loader = loader;
      this.root = root;
    }

    CsvDataLoader resourceLoader(CsvTable<DigitSequence> root) {
      return new CsvDataLoader(loader, root) {
        @Override
        protected <K> CsvTable<K> loadImpl(String rootRelativePath, CsvSchema<K> schema)
            throws IOException {
          Optional<CsvTable<K>> table = TableLoader.loadFromResource(rootRelativePath, schema);
          return table.isPresent() ? table.get() : super.loadImpl(rootRelativePath, schema);
        }
      };
    }

    <K> CsvTable<K> load(
        String rootRelativePath, CsvSchema<K> schema, Predicate<Column<?>> included)
        throws IOException {
      CsvTable<K> table = loadImpl(rootRelativePath, schema);
      return table.toBuilder().filterColumns(included).build();
    }

    protected <K> CsvTable<K> loadImpl(String rootRelativePath, CsvSchema<K> schema)
        throws IOException {
      return loader.loadFromZipFile(rootRelativePath, schema);
    }

    @Override
    public CsvTable<DigitSequence> loadMetadata() throws IOException {
      return root;
    }

    private static final ImmutableSet<Column<?>> RANGE_COLUMNS =
        ImmutableSet.of(TYPE, TARIFF, AREA_CODE_LENGTH, FORMAT, CSV_REGIONS, NATIONAL_ONLY);

    private static final ImmutableSet<Column<?>> EXAMPLE_COLUMNS = ImmutableSet.of(NUMBER);

    private static final ImmutableSet<Column<?>> FORMAT_COLUMNS =
        ImmutableSet.of(NATIONAL, INTERNATIONAL, NATIONAL_PREFIX_OPTIONAL);

    @Override
    public CsvData loadData(DigitSequence cc) throws IOException {
      return CsvData.create(
          cc,
          root,
          load(csv(cc, "ranges"), RangesTableSchema.SCHEMA, RANGE_COLUMNS::contains),
          CsvTable.builder(ShortcodesTableSchema.SCHEMA).build(),
          load(csv(cc, "examples"), ExamplesTableSchema.SCHEMA, EXAMPLE_COLUMNS::contains),
          load(csv(cc, "formats"), FormatsTableSchema.SCHEMA, FORMAT_COLUMNS::contains),
          ImmutableList.of(),
          CsvTable.builder(OperatorsTableSchema.SCHEMA).build(),
          ImmutableList.of());
    }

    private static String csv(DigitSequence cc, String name) {
      return String.format("metadata/%s/%s.csv", cc, name);
    }
  }

  private static final ImmutableSet<String> TABLE_NAMES =
      ImmutableSet.of("ranges", "examples", "formats");

  private static boolean hasOverlayResource(DigitSequence cc) {
    for (String name : TABLE_NAMES) {
      String resourcePath = String.format("/metadata/%s/%s.csv", cc, name);
      try (InputStream is = DiffManager.class.getResourceAsStream(resourcePath)) {
        if (is != null) {
          return true;
        }
      } catch (IOException e) {
        throw new AssertionError("Resource not closable: " + resourcePath);
      }
    }
    return false;
  }
}
