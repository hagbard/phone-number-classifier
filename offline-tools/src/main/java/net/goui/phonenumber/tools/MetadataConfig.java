/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.protobuf.TextFormat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.goui.phonenumber.proto.Metadata.MetadataProto.VersionInfo;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.ConfigOverrideProto;
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.MatcherType;

/** Encapsulation of a metadata configuration file. */
@AutoValue
abstract class MetadataConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int MIN_ALLOWED_PREFIX_LENGTH = 1;

  /**
   * Loads the metadata configuration from a given path (see {@link MetadataConfigProto} for
   * documentation).
   */
  public static MetadataConfig load(Path configPath) throws IOException {
    logger.atInfo().log("Loading config: %s", configPath);
    return from(TextFormat.parse(Files.readString(configPath), MetadataConfigProto.class));
  }

  /**
   * Creates a metadata configuration from a loaded proto message (see {@link MetadataConfigProto}
   * for documentation).
   */
  public static MetadataConfig from(MetadataConfigProto configProto) {
    checkArgument(configProto.hasVersion(), "Version must be specified: %s", configProto);
    VersionInfo version =
        VersionInfo.newBuilder()
            .setDataSchemaUri(configProto.getVersion().getDataSchemaUri())
            .setDataSchemaVersion(configProto.getVersion().getDataSchemaVersion())
            .setMajorVersion(configProto.getVersion().getMajorVersion())
            .setMinorVersion(configProto.getVersion().getMinorVersion())
            .build();

    Map<DigitSequence, CallingCodeConfig> configMap = new LinkedHashMap<>();
    for (ConfigOverrideProto override : configProto.getOverrideList()) {
      ImmutableSet<DigitSequence> overrideCallingCodes =
          loadCallingCodes(override.getCallingCodes(), override.getCallingCodeList());
      checkArgument(
          !overrideCallingCodes.isEmpty(), "Must specify calling codes in overrides: %s", override);
      CallingCodeConfig overrideConfig =
          CallingCodeConfig.of(
              override.getMaximumFalsePositivePercent(), override.getMinimumPrefixLength());
      for (DigitSequence cc : overrideCallingCodes) {
        checkArgument(
            !configMap.containsKey(cc),
            "Duplicate calling code '%s' in override: %s",
            cc,
            override);
        configMap.put(cc, overrideConfig);
      }
    }
    CallingCodeConfig defaultConfig =
        CallingCodeConfig.of(
            configProto.getDefaultMaximumFalsePositivePercent(),
            configProto.getDefaultMinimumPrefixLength());
    ImmutableSet<DigitSequence> defaultCallingCodes =
        loadCallingCodes(configProto.getCallingCodes(), configProto.getCallingCodeList());

    Optional<CallingCodeConfig> implicitDefault = Optional.empty();
    if (!defaultCallingCodes.isEmpty()) {
      // Default is explicit, so no "implicit default" config needed.
      defaultCallingCodes.forEach(cc -> configMap.put(cc, defaultConfig));
    } else if (!configProto.getExcludeByDefault()) {
      // Unlisted calling codes are included, so define the implicit default config.
      implicitDefault = Optional.of(defaultConfig);
    }

    return new AutoValue_MetadataConfig(
        version,
        !configProto.getExcludeParserMetadata(),
        configProto.getIncludeExampleNumbers(),
        configProto.getIncludeEmptyCallingCodes(),
        RangeMapTransformer.from(configProto),
        ImmutableMap.copyOf(configMap),
        implicitDefault,
        ImmutableSet.copyOf(configProto.getMatcherTypeList()));
  }

  /** Returns a very simplified metadata configuration which can serve as a default. */
  public static MetadataConfig simple(
      Set<ClassifierType> classifierTypes,
      MatcherType matcherType,
      int maxFalsePositivePercent,
      int minPrefixLength) {
    CallingCodeConfig defaultConfig =
        CallingCodeConfig.of(maxFalsePositivePercent, minPrefixLength);
    return new AutoValue_MetadataConfig(
        VersionInfo.getDefaultInstance(),
        /* includeParserInfo= */ true,
        /* includeExampleNumbers= */ false,
        /* includeEmptyCallingCodes= */ false,
        RangeMapTransformer.identity(classifierTypes),
        ImmutableMap.of(),
        Optional.of(defaultConfig),
        ImmutableSet.of(matcherType));
  }

  private static ImmutableSet<DigitSequence> loadCallingCodes(
      String callingCodes, List<Integer> callingCodeList) {
    Stream<String> ccs;
    if (!callingCodes.isEmpty()) {
      checkArgument(callingCodeList.isEmpty());
      ccs = Splitter.on(",").trimResults().splitToStream(callingCodes);
    } else {
      ccs = callingCodeList.stream().map(Object::toString);
    }
    return ccs.map(DigitSequence::of).collect(toImmutableSet());
  }

  /** Returns the schema version define in this configuration. */
  public abstract VersionInfo getVersion();

  public abstract boolean includeParserInfo();

  public abstract boolean includeExampleNumbers();

  public abstract boolean includeEmptyCallingCodes();

  /** Returns the transformer defined by the set of classifiers in the configuration */
  public abstract RangeMapTransformer getOutputTransformer();

  // Internal field, should not be needed outside this class.
  abstract ImmutableMap<DigitSequence, CallingCodeConfig> explicitCallingCodeMap();

  // Internal field, should not be needed outside this class.
  abstract Optional<CallingCodeConfig> defaultConfig();

  /** Returns the set of output types for the matcher data (either regex or DFA matcher data). */
  public abstract ImmutableSet<MatcherType> matcherTypes();

  /**
   * Returns the configuration for a specific calling code (either the default configuration or an
   * override).
   */
  public final Optional<CallingCodeConfig> getCallingCodeConfig(DigitSequence cc) {
    Optional<CallingCodeConfig> config = Optional.ofNullable(explicitCallingCodeMap().get(cc));
    return config.or(this::defaultConfig);
  }

  /** A configuration for how to process data on a per calling code basis. */
  @AutoValue
  public abstract static class CallingCodeConfig {
    static CallingCodeConfig of(int maxFalsePositivePercent, int minPrefixLength) {
      checkArgument(maxFalsePositivePercent >= 0);
      checkArgument(minPrefixLength >= 0);
      return new AutoValue_MetadataConfig_CallingCodeConfig(
          maxFalsePositivePercent, Math.max(minPrefixLength, MIN_ALLOWED_PREFIX_LENGTH));
    }

    /**
     * The percent increase of addition validation ranges allowed during simplification (see {@link
     * MetadataConfigProto} for documentation).
     */
    public abstract int maxFalsePositivePercent();

    /**
     * The minimum number prefix length which any simplification operation must not go below (see
     * {@link MetadataConfigProto} for documentation).
     */
    public abstract int minPrefixLength();
  }
}
