/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.goui.phonenumber.proto.Metadata.*;
import net.goui.phonenumber.tools.Metadata.RangeClassifier;
import net.goui.phonenumber.tools.Metadata.RangeMap;

public class GenerateMetadata {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final class Flags {
    @Parameter(names = "--zip", description = "Standard format zip file path")
    private String zipPath = "";

    @Parameter(names = "--dir", description = "Root overlay directory (unzipped files)")
    private String dirPath = "";

    @Parameter(
        names = "--csv_separator",
        description = "CSV separator for unzipped overlay files (single char)")
    private String csvSeparator = ",";

    @Parameter(names = "--config_dir", description = "Config directory path (optional)")
    private String configDir = "";

    @Parameter(
        names = "--config",
        description = "Config text proto name or path (absolute or in config_dir)")
    private String configPath = "";

    @Parameter(names = "--config_pattern", description = "Config text proto regex (in config_dir)")
    private String configPattern = "";

    @Parameter(names = "--out", description = "Output proto path (optional)")
    private String outPath = "";

    @Parameter(names = "--out_type", description = "Output proto path (optional)")
    private String outType = "PROTO";

    @Parameter(names = "--log_level", description = "JDK log level name")
    private String logLevel = "INFO";
  }

  @SuppressWarnings("unused")
  enum OutType {
    PROTO(".pb") {
      @Override
      void write(MetadataProto outputProto, OutputStream os) throws IOException {
        outputProto.writeTo(os);
      }
    },
    JSON(".json") {
      @Override
      void write(MetadataProto outputProto, OutputStream os) throws IOException {
        MetadataJsonWriter.write(outputProto, os);
      }
    };

    private final String extension;

    OutType(String extension) {
      this.extension = extension;
    }

    abstract void write(MetadataProto outputProto, OutputStream os) throws IOException;

    public String getExtension() {
      return extension;
    }
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

    Optional<Path> configDir =
        !flags.configDir.isEmpty() ? Optional.of(Paths.get(flags.configDir)) : Optional.empty();

    if (!flags.configPath.isEmpty()) {
      Path configPath = Paths.get(flags.configPath);
      writeMetadataForConfig(flags, configDir.map(d -> d.resolve(configPath)).orElse(configPath));
    } else if (!flags.configPattern.isEmpty()) {
      Predicate<String> isConfig = Pattern.compile(flags.configPattern).asMatchPredicate();
      try (Stream<Path> configs =
          Files.list(configDir.orElse(Paths.get(".")))
              .filter(f -> isConfig.test(f.getFileName().toString()))) {
        Iterator<Path> it = configs.iterator();
        while (it.hasNext()) {
          writeMetadataForConfig(flags, it.next());
        }
      }
    }
  }

  private static void writeMetadataForConfig(Flags flags, Path configPath) throws IOException {
    MetadataConfig config = MetadataConfig.load(configPath);
    Metadata originalMetadata =
        Metadata.load(
            flags.zipPath,
            flags.dirPath,
            flags.csvSeparator,
            Metadata.DEFAULT_BASE_TYPES,
            config.getOutputTransformer());

    Metadata simplifiedMetadata = MetadataSimplifier.simplify(originalMetadata, config);
    validateNoChangeToOriginalRanges(originalMetadata, simplifiedMetadata);

    simplifiedMetadata = simplifiedMetadata.trimValidRanges();

    MetadataProto outputProto = MetadataProtoBuilder.toMetadataProto(simplifiedMetadata, config);
    Path outPath = Paths.get(flags.outPath);
    OutType outType = OutType.valueOf(flags.outType);
    if (flags.outPath.isEmpty()) {
      outPath = getDerivedOutputPath(configPath, outType.getExtension());
    }
    logger.atInfo().log("Writing: %s", outPath);
    try (OutputStream os = Files.newOutputStream(outPath)) {
      outType.write(outputProto, os);
    }
  }

  private static Path getDerivedOutputPath(Path configPath, String extension) {
    return configPath.resolveSibling(
        configPath.getFileName().toString().replaceAll("\\.[^.]+$", extension));
  }

  private static void validateNoChangeToOriginalRanges(
      Metadata originalMetadata, Metadata simplifiedMetadata) {
    for (DigitSequence cc : simplifiedMetadata.getAvailableCallingCodes()) {
      RangeMap simplifiedRangeMap = simplifiedMetadata.getRangeMap(cc);
      RangeMap originalRangeMap = originalMetadata.getRangeMap(cc);
      RangeTree excessRanges =
          originalRangeMap.getAllRanges().subtract(simplifiedRangeMap.getAllRanges());
      checkArgument(
          excessRanges.isEmpty(),
          "[cc=%s] simplified range map MUST contain at least the same ranges as the original: %s",
          cc,
          excessRanges);
      checkArgument(
          simplifiedRangeMap.getTypes().equals(originalRangeMap.getTypes()),
          "[cc=%s] simplified range map MUST contain the same number types as the original:\n"
              + "simplified: %s\n"
              + "original: %s",
          cc,
          simplifiedRangeMap.getTypes(),
          originalRangeMap.getTypes());
      for (ClassifierType type : originalRangeMap.getTypes()) {
        RangeClassifier simplifiedRanges = simplifiedRangeMap.getClassifier(type);
        RangeClassifier originalRanges = originalRangeMap.getClassifier(type);

        checkArgument(
            simplifiedRanges.orderedKeys().equals(originalRanges.orderedKeys()),
            "[cc=%s, type=%s] simplified range map MUST contain the same keys as the original:\n"
                + "simplified: %s\n"
                + "original: %s",
            cc,
            type,
            simplifiedRanges.orderedKeys(),
            originalRanges.orderedKeys());

        for (String key : originalRanges.orderedKeys()) {
          excessRanges = originalRanges.getRanges(key).subtract(simplifiedRanges.getRanges(key));
          checkArgument(
              excessRanges.isEmpty(),
              "[cc=%s, type=%s, key=%s] simplified ranges MUST not be smaller than the original: %s",
              cc,
              type,
              key,
              excessRanges);
        }
      }
    }
  }
}
