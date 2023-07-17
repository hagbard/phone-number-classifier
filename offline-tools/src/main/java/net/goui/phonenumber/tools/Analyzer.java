/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.MatcherType.DIGIT_SEQUENCE_MATCHER;
import static org.typemeta.funcj.json.model.JSAPI.arr;
import static org.typemeta.funcj.json.model.JSAPI.field;
import static org.typemeta.funcj.json.model.JSAPI.obj;
import static org.typemeta.funcj.json.model.JSAPI.str;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.goui.phonenumber.tools.Metadata.RangeMap;
import org.typemeta.funcj.json.algebra.JsonToDoc;
import org.typemeta.funcj.json.model.JSAPI;
import org.typemeta.funcj.json.model.JsArray;
import org.typemeta.funcj.json.model.JsObject;
import org.typemeta.funcj.json.model.JsValue;

public class Analyzer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  static final class Flags {
    @Parameter(names = "--zip", description = "Standard format zip file path")
    private String zipPath = "";

    @Parameter(names = "--dir", description = "Root directory (unzipped files)")
    private String dirPath = "";

    @Parameter(
        names = "--csv_separator",
        description = "CSV separator for unzipped files (single char)")
    private String csvSeparator = ",";

    @Parameter(names = "--config", description = "Config text proto path")
    private String configPath = "";

    @Parameter(names = "--testdata", description = "Test data output path")
    private String testDataPath = "";

    @Parameter(names = "--log_level", description = "JDK log level name")
    private String logLevel = "INFO";
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

    MetadataConfig config =
        flags.configPath.isEmpty()
            ? MetadataConfig.simple(Metadata.DEFAULT_BASE_TYPES, DIGIT_SEQUENCE_MATCHER, 0, 1)
            : MetadataConfig.load(Paths.get(flags.configPath));
    Metadata originalMetadata =
        Metadata.load(
            flags.zipPath, flags.dirPath, flags.csvSeparator, config.getOutputTransformer());

    if (!flags.testDataPath.isEmpty()) {
      logger.atInfo().log("writing test data to: %s", flags.testDataPath);
      writeTestData(originalMetadata, Paths.get(flags.testDataPath));
    }
  }

  private static void writeTestData(Metadata metadata, Path testdataPath) throws IOException {
    Random rnd = new Random();
    ImmutableSet<DigitSequence> callingCodes = metadata.getAvailableCallingCodes();
    List<JsValue> testData = new ArrayList<>();
    for (DigitSequence cc : callingCodes) {
      RangeMap rangeMap = metadata.getRangeMap(cc);
      RangeTree allRanges = rangeMap.getAllRanges();
      Set<JsArray> uniqueResults = new HashSet<>();
      for (int n = 0; n < 50; n++) {
        DigitSequence seq = allRanges.sample((long) (rnd.nextDouble() * allRanges.size()));
        JsArray result = jsArray(rangeMap.getTypes(), t -> classify(rangeMap, t, seq));
        if (uniqueResults.add(result)) {
          testData.add(
              obj(
                  field("cc", str(cc.toString())),
                  field("number", str(seq.toString())),
                  field("result", result)));
        }
      }
    }
    try (Writer w = new OutputStreamWriter(Files.newOutputStream(testdataPath), UTF_8)) {
      w.write(JsonToDoc.toString(obj(field("testdata", arr(testData))), 2, 80));
    }
  }

  private static JsObject classify(RangeMap rangeMap, ClassifierType type, DigitSequence seq) {
    return obj(
        field("type", str(type.id())),
        field("values", jsArray(rangeMap.getClassifier(type).classify(seq), JSAPI::str)));
  }

  private static <T> JsArray jsArray(Collection<T> src, Function<T, JsValue> fn) {
    return arr(src.stream().map(fn).collect(toList()));
  }

  static void debug(Metadata originalMetadata, Metadata simplifiedMetadata, MetadataConfig config) {
    net.goui.phonenumber.proto.Metadata.MetadataProto oldProto =
        MetadataProtoBuilder.toMetadataProto(originalMetadata, config);
    net.goui.phonenumber.proto.Metadata.MetadataProto newProto =
        MetadataProtoBuilder.toMetadataProto(simplifiedMetadata, config);
    System.out.println("==== ORIGINAL DATA ====");
    System.out.println(TextFormat.printer().printToString(oldProto));
    System.out.println("====== NEW DATA ======");
    System.out.println(TextFormat.printer().printToString(newProto));
    System.out.println("======================");

    ImmutableMap<Integer, net.goui.phonenumber.proto.Metadata.CallingCodeProto> oldMap =
        oldProto.getCallingCodeDataList().stream()
            .collect(
                toImmutableMap(
                    net.goui.phonenumber.proto.Metadata.CallingCodeProto::getCallingCode,
                    identity()));
    int oldTotalBytes = 0;
    int newTotalBytes = 0;
    for (net.goui.phonenumber.proto.Metadata.CallingCodeProto newData :
        newProto.getCallingCodeDataList()) {
      net.goui.phonenumber.proto.Metadata.CallingCodeProto oldData =
          checkNotNull(oldMap.get(newData.getCallingCode()));

      DigitSequence cc = DigitSequence.of(Integer.toString(newData.getCallingCode()));
      RangeTree beforeRanges = originalMetadata.getRangeMap(cc).getAllRanges();
      RangeTree afterRanges = simplifiedMetadata.getRangeMap(cc).getAllRanges();
      long oldCount = beforeRanges.size();
      long newCount = afterRanges.size();
      double falsePositivePercent = 100.0 * ((((double) newCount) / ((double) oldCount)) - 1.0);

      int oldBytes = oldData.getSerializedSize();
      int newBytes = newData.getSerializedSize();
      System.out.format(
          "%s, %+.3g%%, %d, %d%s\n",
          cc, falsePositivePercent, oldBytes, newBytes, newBytes > oldBytes ? " ***" : "");
      oldTotalBytes += oldBytes;
      newTotalBytes += newBytes;
    }
    System.out.format("TOTAL (proto bytes): %d, %d\n", oldTotalBytes, newTotalBytes);
  }
}
