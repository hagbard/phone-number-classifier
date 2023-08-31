/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumbers.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.i18n.phonenumbers.metadata.regex.RegexFormatter.FormatOption.FORCE_CAPTURING_GROUPS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static net.goui.phonenumbers.tools.ClassifierType.INTERNATIONAL_FORMAT;
import static net.goui.phonenumbers.tools.ClassifierType.NATIONAL_FORMAT;
import static net.goui.phonenumbers.tools.proto.Config.MetadataConfigProto.MatcherType.DIGIT_SEQUENCE_MATCHER;
import static org.typemeta.funcj.json.model.JSAPI.arr;
import static org.typemeta.funcj.json.model.JSAPI.field;
import static org.typemeta.funcj.json.model.JSAPI.obj;
import static org.typemeta.funcj.json.model.JSAPI.str;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.base.CharMatcher;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.model.MetadataTableSchema;
import com.google.i18n.phonenumbers.metadata.regex.RegexFormatter;
import com.google.i18n.phonenumbers.metadata.regex.RegexGenerator;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.goui.phonenumbers.proto.Metadata.CallingCodeProto;
import net.goui.phonenumbers.proto.Metadata.MetadataProto;
import org.typemeta.funcj.json.algebra.JsonToDoc;
import org.typemeta.funcj.json.model.JSAPI;
import org.typemeta.funcj.json.model.JsArray;
import org.typemeta.funcj.json.model.JsObject;
import org.typemeta.funcj.json.model.JsValue;

/** Tool for writing test data and other auxiliary range analysis. */
public class Analyzer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

  /** The base types we extract from the underlying metadata by default. */
  public static final ImmutableSet<ClassifierType> DEFAULT_BASE_TYPES =
      ImmutableSet.of(
          ClassifierType.TYPE,
          ClassifierType.AREA_CODE_LENGTH,
          ClassifierType.TARIFF,
          ClassifierType.NATIONAL_FORMAT,
          ClassifierType.INTERNATIONAL_FORMAT,
          ClassifierType.REGION);

  private static final RegexGenerator REGEX_GENERATOR =
      RegexGenerator.basic()
          .withDfaFactorization()
          .withSubgroupOptimization()
          .withDotMatch()
          .withTailOptimization();

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
    Logger.getLogger("net.goui.phonenumbers").setLevel(level);
  }

  public static void main(String[] args) throws IOException {
    Flags flags = new Flags();
    JCommander.newBuilder().addObject(flags).build().parse(args);
    setLogging(flags.logLevel);

    MetadataConfig config =
        flags.configPath.isEmpty()
            ? MetadataConfig.simple(DEFAULT_BASE_TYPES, DIGIT_SEQUENCE_MATCHER, 0, 1)
            : MetadataConfig.load(Paths.get(flags.configPath));
    // Don't call trimValidRanges() until we have the simplified version.
    //
    // Note that transformedMetadata has data for all calling codes, and it's only when we call
    // simplify() that it is trimmed. This means that test data is written for *all* calling
    // codes (which is what we want, since some functionality is still testable).
    Metadata transformedMetadata =
        Metadata.load(flags.zipPath, flags.dirPath, flags.csvSeparator)
            .transform(config.getOutputTransformer());
    transformedMetadata = transformedMetadata.trimValidRanges(/* includeEmptyCallingCodes= */ true);
    if (!flags.testDataPath.isEmpty()) {
      logger.atInfo().log("writing test data to: %s", flags.testDataPath);
      writeTestData(transformedMetadata, Paths.get(flags.testDataPath));
    }
    Metadata simplifiedMetadata =
        MetadataSimplifier.simplify(transformedMetadata, config)
            .trimValidRanges(/* includeEmptyCallingCodes= */ true);
    printRegex(transformedMetadata, simplifiedMetadata);
  }

  private static void printRegex(Metadata original, Metadata simplified) {
    for (DigitSequence cc : simplified.getAvailableCallingCodes()) {
      System.out.println("<<<< " + cc);
      System.out.println(CharMatcher.whitespace().removeFrom(getRegex(original, cc)));
      System.out.println(">>>> " + cc);
      System.out.println(getRegex(simplified, cc));
    }
  }

  private static String getRegex(Metadata original, DigitSequence cc) {
    RangeMap rangeMap = original.getRangeMap(cc);
    if (rangeMap.getAllRanges().isEmpty()) {
      return "<empty>";
    }
    String regex = REGEX_GENERATOR.toRegex(rangeMap.getAllRanges());
    return RegexFormatter.format(regex, FORCE_CAPTURING_GROUPS);
  }

  private static final ImmutableMap<ClassifierType, PhoneNumberFormat> FORMAT_MAP =
      ImmutableMap.of(
          NATIONAL_FORMAT,
          PhoneNumberFormat.NATIONAL,
          INTERNATIONAL_FORMAT,
          PhoneNumberFormat.INTERNATIONAL);

  private static void writeTestData(Metadata metadata, Path testdataPath) throws IOException {
    Random rnd = new Random();
    ImmutableSet<DigitSequence> callingCodes = metadata.getAvailableCallingCodes();
    List<JsValue> testData = new ArrayList<>();
    for (DigitSequence cc : callingCodes) {
      RangeMap rangeMap = metadata.getRangeMap(cc);
      RangeTree allRanges = rangeMap.getAllRanges();
      if (allRanges.isEmpty()) {
        continue;
      }
      Multiset<JsArray> uniqueResults = HashMultiset.create();
      for (int n = 0; n < 50; n++) {
        DigitSequence nn = allRanges.sample((long) (rnd.nextDouble() * allRanges.size()));
        JsArray result =
            jsArray(
                Sets.difference(rangeMap.getTypes(), FORMAT_MAP.keySet()),
                t -> classify(rangeMap, t, nn));
        uniqueResults.add(result);
        if (uniqueResults.count(result) <= 5) {
          List<JsObject.Field> fields = new ArrayList<>();
          fields.add(field("cc", str(cc.toString())));
          fields.add(field("number", str(nn.toString())));
          fields.add(field("result", result));

          Sets.SetView<ClassifierType> formatTypes =
              Sets.intersection(rangeMap.getTypes(), FORMAT_MAP.keySet());
          ImmutableList<JsValue> formats =
              formatTypes.stream()
                  .map(t -> format(t, cc, nn, metadata))
                  .filter(Objects::nonNull)
                  .collect(toImmutableList());
          fields.add(field("format", arr(formats)));
          testData.add(obj(fields));
        }
      }
    }
    try (Writer w = new OutputStreamWriter(Files.newOutputStream(testdataPath), UTF_8)) {
      w.write(JsonToDoc.toString(obj(field("testdata", arr(testData))), 2, 80));
    }
  }

  private static JsObject classify(RangeMap rangeMap, ClassifierType type, DigitSequence nn) {
    return obj(
        field("type", str(type.id())),
        field("values", jsArray(rangeMap.getClassifier(type).classify(nn), JSAPI::str)));
  }

  private static String getMainRegion(DigitSequence cc) {
    return PHONE_NUMBER_UTIL.getRegionCodeForCountryCode(Integer.parseUnsignedInt(cc.toString()));
  }

  private static JsObject format(
      ClassifierType type, DigitSequence cc, DigitSequence nn, Metadata metadata) {
    RangeClassifier formatClassifier = metadata.getRangeMap(cc).getClassifier(type);
    checkState(formatClassifier.isSingleValued(), "formats are single valued");
    if (formatClassifier.classify(nn).isEmpty()) {
      logger.atInfo().log("skip missing %s format for: +%s%s", type, cc, nn);
      return null;
    }

    // Since numbers with the "world" region don't have a unique calling code, it's impossible to
    // reliably re-parse the numbers from national format, so we shouldn't emit them into the data.
    String region = getMainRegion(cc);
    if (region.equals("001") && type == NATIONAL_FORMAT) {
      return null;
    }

    DigitSequence np = getNationalPrefix(metadata, cc);
    Optional<PhoneNumber> optLpn = parseNationalNumber(cc, np.toString() + nn.toString(), region);
    if (optLpn.isEmpty()) {
      optLpn = parseNationalNumber(cc, nn.toString(), region);
      if (optLpn.isEmpty()) {
        optLpn = parseE164("+" + cc + nn);
      }
    }
    PhoneNumber lpn =
        optLpn.orElseThrow(() -> new AssertionError("cannot parse: " + cc + " - " + nn));
    String formatted = PHONE_NUMBER_UTIL.format(lpn, FORMAT_MAP.get(type));
    return obj(field("type", str(type.id())), field("value", str(formatted)));
  }

  private static Optional<PhoneNumber> parseNationalNumber(
      DigitSequence cc, String nn, String region) {
    try {
      PhoneNumber lpn = PHONE_NUMBER_UTIL.parse(nn, region);
      if (cc.toString().equals(Integer.toString(lpn.getCountryCode()))) {
        return Optional.of(lpn);
      }
    } catch (NumberParseException ignored) {
    }
    return Optional.empty();
  }

  private static Optional<PhoneNumber> parseE164(String e164) {
    try {
      return Optional.of(PHONE_NUMBER_UTIL.parse(e164, "ZZ"));
    } catch (NumberParseException ignored) {
    }
    return Optional.empty();
  }

  private static DigitSequence getNationalPrefix(Metadata metadata, DigitSequence cc) {
    return metadata
        .root()
        .get(cc, MetadataTableSchema.NATIONAL_PREFIX)
        .orElse(MetadataTableSchema.DigitSequences.of(DigitSequence.empty()))
        .getValues()
        .asList()
        .get(0);
  }

  static void debug(Metadata originalMetadata, Metadata simplifiedMetadata, MetadataConfig config) {
    MetadataProto oldProto = MetadataProtoBuilder.toMetadataProto(originalMetadata, config);
    MetadataProto newProto = MetadataProtoBuilder.toMetadataProto(simplifiedMetadata, config);
    System.out.println("==== ORIGINAL DATA ====");
    System.out.println(TextFormat.printer().printToString(oldProto));
    System.out.println("====== NEW DATA ======");
    System.out.println(TextFormat.printer().printToString(newProto));
    System.out.println("======================");

    ImmutableMap<Integer, CallingCodeProto> oldMap =
        oldProto.getCallingCodeDataList().stream()
            .collect(toImmutableMap(CallingCodeProto::getCallingCode, identity()));
    int oldTotalBytes = 0;
    int newTotalBytes = 0;
    for (CallingCodeProto newData : newProto.getCallingCodeDataList()) {
      CallingCodeProto oldData = checkNotNull(oldMap.get(newData.getCallingCode()));

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

  private static <T> JsArray jsArray(Collection<T> src, Function<T, JsValue> fn) {
    return arr(src.stream().map(fn).collect(toList()));
  }
}
