package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.goui.phonenumber.metadata.RawClassifier;

public final class PhoneNumberRegions<T> {
  private final ImmutableListMultimap<DigitSequence, T> regionCodeMap;
  private final ImmutableMap<T, DigitSequence> callingCodeMap;

  public PhoneNumberRegions(RawClassifier rawClassifier, Function<String, T> converter) {
    checkState(
        rawClassifier.getSupportedNumberTypes().contains("REGION"),
        "Region information not present in underlying metadata");

    ImmutableListMultimap.Builder<DigitSequence, T> regionCodeMap = ImmutableListMultimap.builder();
    ImmutableMap.Builder<T, DigitSequence> callingCodeMap = ImmutableMap.builder();
    for (DigitSequence cc : rawClassifier.getSupportedCallingCodes()) {
      List<String> regions = new ArrayList<>();
      String mainRegion = rawClassifier.getMainRegion(cc);
      regions.add(mainRegion);
      rawClassifier.getValueMatcher(cc, "REGION").getPossibleValues().stream()
          .sorted()
          .filter(r -> !r.equals(mainRegion))
          .forEach(regions::add);
      checkState(regions.size() == 1 || !regions.contains("001"),
          "World region 001 must never appear with other region codes: %s", regions);
      for (String regionString : regions) {
        T regionCode = converter.apply(regionString);
        regionCodeMap.put(cc, regionCode);
        // The world region has more than one calling code so cannot be part of a single valued map.
        if (!regionString.equals("001")) {
          callingCodeMap.put(regionCode, cc);
        }
      }
    }
    this.regionCodeMap = regionCodeMap.build();
    this.callingCodeMap = callingCodeMap.buildOrThrow();
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
}
