package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.FileVisitOption.FOLLOW_LINKS;

import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.table.CsvParser;
import com.google.i18n.phonenumbers.metadata.table.CsvSchema;
import com.google.i18n.phonenumbers.metadata.table.CsvTable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.checkerframework.checker.nullness.qual.Nullable;

class TableLoader implements AutoCloseable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final Pattern IS_OVERLAP_PATH =
      Pattern.compile("(metadata|[0-9]{1,3}/[^/]+)\\.csv");

  @Nullable private final ZipFile zip;
  private final ImmutableMap<String, Path> overlayMap;
  private final CsvParser csvParser;

  TableLoader(String zipPath, String overlayDirPath, char overlaySeparator) throws IOException {
    this.zip = !zipPath.isEmpty() ? new ZipFile(zipPath) : null;
    this.overlayMap = readOverlayFiles(overlayDirPath);
    this.csvParser = CsvParser.withSeparator(overlaySeparator).trimWhitespace();
  }

  <T> CsvTable<T> load(String rootRelativePath, CsvSchema<T> schema) throws IOException {
    Path overlayPath = overlayMap.get(rootRelativePath);
    if (overlayPath != null) {
      return loadTableFromFile(overlayPath, schema, csvParser);
    }
    try (InputStream is = TableLoader.class.getResourceAsStream("/" + rootRelativePath)) {
      if (is != null) {
        return loadFromResourceInputStream(is, rootRelativePath, schema);
      }
    }
    checkState(
        zip != null,
        "cannot find metadata path in overlay directory, and no zip file was specified: %s",
        rootRelativePath);
    return loadTableFromZipEntry(zip, rootRelativePath, schema);
  }

  private static ImmutableMap<String, Path> readOverlayFiles(String overlayDirPath)
      throws IOException {
    ImmutableMap<String, Path> overlayMap = ImmutableMap.of();
    if (!overlayDirPath.isEmpty()) {
      Path overlayDir = Paths.get(overlayDirPath);
      try (Stream<Path> files = Files.walk(overlayDir, 2, FOLLOW_LINKS)) {
        // Files are resolved against the root directory. Key is relative path string.
        overlayMap =
            files
                .filter(f -> IS_OVERLAP_PATH.matcher(f.toString()).matches())
                .collect(toImmutableMap(Object::toString, Path::toAbsolutePath));
      }
    }
    return overlayMap;
  }

  private static <T> CsvTable<T> loadFromResourceInputStream(
      InputStream is, String path, CsvSchema<T> schema) throws IOException {
    logger.atInfo().log("Resource overlay: /%s", path);
    try (Reader reader = new InputStreamReader(is, UTF_8)) {
      return CsvTable.importCsv(schema, reader);
    } catch (IOException e) {
      throw new IOException("error loading resource: " + path, e);
    }
  }

  private static <T> CsvTable<T> loadTableFromFile(
      Path path, CsvSchema<T> schema, CsvParser csvParser) throws IOException {
    logger.atInfo().log("File overlay: %s", path);
    try (Reader reader = Files.newBufferedReader(path, UTF_8)) {
      return CsvTable.importCsv(schema, reader, csvParser);
    } catch (IOException e) {
      throw new IOException("error loading file: " + path, e);
    }
  }

  private static <T> CsvTable<T> loadTableFromZipEntry(
      ZipFile zip, String path, CsvSchema<T> schema) throws IOException {
    ZipEntry zipEntry = zip.getEntry(path);
    if (zipEntry == null) {
      return CsvTable.builder(schema).build();
    }
    try (Reader reader = new InputStreamReader(zip.getInputStream(zipEntry), UTF_8)) {
      return CsvTable.importCsv(schema, reader);
    } catch (IOException e) {
      throw new IOException("error loading zip entry: " + path, e);
    }
  }

  @Override
  public void close() throws IOException {
    if (zip != null) {
      zip.close();
    }
  }
}
