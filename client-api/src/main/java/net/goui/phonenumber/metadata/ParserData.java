package net.goui.phonenumber.metadata;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import net.goui.phonenumber.DigitSequence;

@AutoValue
public abstract class ParserData {

  public static ParserData create(
      ImmutableSet<String> regions,
      ImmutableSet<DigitSequence> nationalPrefixes,
      boolean isNationalPrefixOptional) {
    return new AutoValue_ParserData(regions, nationalPrefixes, isNationalPrefixOptional);
  }

  public abstract ImmutableSet<String> getRegions();

  public abstract ImmutableSet<DigitSequence> getNationalPrefixes();

  public abstract boolean isNationalPrefixOptional();
}
