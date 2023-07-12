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
import net.goui.phonenumber.tools.proto.Config.MetadataConfigProto.MatcherType;

@AutoValue
abstract class MetadataConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final int MIN_ALLOWED_PREFIX_LENGTH = 1;

  public static MetadataConfig load(Path configPath) throws IOException {
    logger.atInfo().log("Building metadata from config: %s", configPath);
    return load(TextFormat.parse(Files.readString(configPath), MetadataConfigProto.class));
  }

  public static MetadataConfig load(MetadataConfigProto configProto) {
    checkArgument(configProto.hasVersion(), "version must be specified: %s", configProto);
    VersionInfo version =
        VersionInfo.newBuilder()
            .setDataSchemaUri(configProto.getVersion().getDataSchemaUri())
            .setDataSchemaVersion(configProto.getVersion().getDataSchemaVersion())
            .setMajorVersion(configProto.getVersion().getMajorVersion())
            .setMinorVersion(configProto.getVersion().getMinorVersion())
            .build();

    Map<DigitSequence, CallingCodeConfig> configMap = new LinkedHashMap<>();
    for (MetadataConfigProto.ConfigOverrideProto override : configProto.getOverrideList()) {
      ImmutableSet<DigitSequence> overrideCallingCodes =
          loadCallingCodes(override.getCallingCodes(), override.getCallingCodeList());
      checkArgument(
          !overrideCallingCodes.isEmpty(), "must specify calling codes in overrides: %s", override);
      CallingCodeConfig overrideConfig =
          CallingCodeConfig.of(
              override.getMaximumFalsePositivePercent(), override.getMinimumPrefixLength());
      for (DigitSequence cc : overrideCallingCodes) {
        checkArgument(
            !configMap.containsKey(cc),
            "duplicate calling code '%s' in override: %s",
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
        RangeMapTransformer.from(configProto),
        ImmutableMap.copyOf(configMap),
        implicitDefault,
        ImmutableSet.copyOf(configProto.getMatcherTypeList()));
  }

  public static MetadataConfig simple(
      Set<ClassifierType> classifierTypes,
      MatcherType matcherType,
      int maxFalsePositivePercent,
      int minPrefixLength) {
    CallingCodeConfig defaultConfig =
        CallingCodeConfig.of(maxFalsePositivePercent, minPrefixLength);
    return new AutoValue_MetadataConfig(
        VersionInfo.getDefaultInstance(),
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

  public abstract VersionInfo getVersion();

  public abstract RangeMapTransformer getOutputTransformer();

  abstract ImmutableMap<DigitSequence, CallingCodeConfig> explicitCallingCodeMap();

  abstract Optional<CallingCodeConfig> defaultConfig();

  abstract ImmutableSet<MatcherType> matcherTypes();

  public final ImmutableSet<ClassifierType> getOutputTypes() {
    return getOutputTransformer().getOutputTypes();
  }

  public final Optional<CallingCodeConfig> getCallingCodeConfig(DigitSequence cc) {
    Optional<CallingCodeConfig> config = Optional.ofNullable(explicitCallingCodeMap().get(cc));
    return config.or(this::defaultConfig);
  }

  @AutoValue
  abstract static class CallingCodeConfig {
    static CallingCodeConfig of(int maxFalsePositivePercent, int minPrefixLength) {
      checkArgument(maxFalsePositivePercent >= 0);
      checkArgument(minPrefixLength >= 0);
      return new AutoValue_MetadataConfig_CallingCodeConfig(
          maxFalsePositivePercent, Math.max(minPrefixLength, MIN_ALLOWED_PREFIX_LENGTH));
    }

    abstract int maxFalsePositivePercent();

    abstract int minPrefixLength();
  }
}
