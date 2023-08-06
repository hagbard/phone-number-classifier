package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import java.util.Optional;
import java.util.function.Function;
import net.goui.phonenumber.metadata.ParserData;
import net.goui.phonenumber.metadata.RawClassifier;

public final class PhoneNumberParser<T> {
  private final ImmutableListMultimap<DigitSequence, T> regionCodeMap;
  private final ImmutableMap<T, DigitSequence> callingCodeMap;
  private final ImmutableSetMultimap<DigitSequence, DigitSequence> nationalPrefixMap;
  private final ImmutableSet<DigitSequence> nationalPrefixOptional;

  public PhoneNumberParser(RawClassifier rawClassifier, Function<String, T> converter) {
    T worldRegion = checkNotNull(converter.apply("001"));
    ImmutableListMultimap.Builder<DigitSequence, T> regionCodeMap = ImmutableListMultimap.builder();
    ImmutableMap.Builder<T, DigitSequence> callingCodeMap = ImmutableMap.builder();
    ImmutableSetMultimap.Builder<DigitSequence, DigitSequence> nationalPrefixMap =
        ImmutableSetMultimap.builder();
    ImmutableSet.Builder<DigitSequence> nationalPrefixOptional = ImmutableSet.builder();
    for (DigitSequence cc : rawClassifier.getSupportedCallingCodes()) {
      ParserData parserData = rawClassifier.getParserData(cc);
      checkState(parserData != null, "Parser data unavailable for: " + cc);
      ImmutableSet<T> regions =
          parserData.getRegions().stream().map(converter).collect(toImmutableSet());

      // Region 001 is treated specially since it's the only region with more than one calling code,
      // so it cannot be put into the calling code map. It cannot also appear with other regions.
      // region codes.
      if (!regions.contains(worldRegion)) {
        regions.forEach(r -> callingCodeMap.put(r, cc));
      } else if (regions.size() > 1) {
        throw new Error("Region 001 must never appear with other region codes: " + regions);
      }
      regionCodeMap.putAll(cc, regions);

      if (!parserData.getNationalPrefixes().isEmpty()) {
        nationalPrefixMap.putAll(cc, parserData.getNationalPrefixes());
        if (parserData.isNationalPrefixOptional()) {
          nationalPrefixOptional.add(cc);
        }
      }
    }
    this.regionCodeMap = regionCodeMap.build();
    this.callingCodeMap = callingCodeMap.buildOrThrow();
    this.nationalPrefixMap = nationalPrefixMap.build();
    this.nationalPrefixOptional = nationalPrefixOptional.build();
  }

  /**
   * Returns a sorted list of CLDR region codes for the given country calling code.
   *
   * <p>This list is sorted alphabetically, except the first element, which is always the "main
   * region" associated with the calling code (e.g. "US" for "1", "GB" for "44").
   *
   * <p>em>NOTE</em>: There are some calling codes not associated with any region code (e.g. {@code
   * 888} and {@code 979}). In the original Libphonenumber metadata these are associated with the
   * special "world" region {@code "001"}. This is the only case where a returned region code is not
   * one of the "normal" 2-letter codes.
   *
   * <p>If the given calling code is not supported, an empty list is returned.
   */
  public ImmutableList<T> getRegions(DigitSequence callingCode) {
    return regionCodeMap.get(callingCode);
  }

  /**
   * Returns the unique country calling code for the specified CLDR region code.
   *
   * <p><em>NOTE</em>: There are some calling codes not associated with any region code (e.g. {@code
   * 888} and {@code 979}). In the original Libphonenumber metadata these are associated with the
   * special "world" region {@code "001"}. Unfortunately this results in the "001" region not having
   * a unique country calling code. As such, this method does not accept the "001" region as input,
   * even though {@code getRegions(DigitSequence.parse("888"))} will return it.
   *
   * <p>If the given region code is not supported, {@link Optional#empty()} is returned.
   */
  public Optional<DigitSequence> getCallingCode(T region) {
    return Optional.ofNullable(callingCodeMap.get(region));
  }

  public ImmutableSet<DigitSequence> getNationalPrefixes(DigitSequence callingCode) {
    return nationalPrefixMap.get(callingCode);
  }

  public boolean isNationalPrefixOptional(DigitSequence callingCode) {
    return nationalPrefixOptional.contains(callingCode);
  }
}
