package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Comparator.naturalOrder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Comparator;
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
      String mainRegion = rawClassifier.getMainRegion(cc);
      List<String> regions =
          new ArrayList<>(rawClassifier.getValueMatcher(cc, "REGION").getPossibleValues());
      // Note: Since region data is associated with ranges, and ranges can be restricted by
      // configuration, it's possible to get a calling codes in which some or all of the original
      // region code are missing. This is fine for classification, but this API promises to have
      // the "main" region present in the list, so we make sure (if it's missing) to add it here.
      if (!regions.contains(mainRegion)) {
        regions.add(mainRegion);
      }
      // Region 001 is treated specially since it's the only region with more than one calling code,
      // so it cannot be put into the calling code map. It's also not expected to ever appear with
      // any other region codes in the data.
      boolean hasWorldRegion = regions.contains("001");
      // We only need to do any work if there's more than one region for this calling code.
      if (regions.size() > 1) {
        checkState(
            !hasWorldRegion, "Region 001 must never appear with other region codes: %s", regions);
        // Sort regions, and then move the main region to the front (if not already there).
        regions.sort(naturalOrder());
        int idx = regions.indexOf(mainRegion);
        if (idx > 0) {
          regions.remove(idx);
          regions.add(0, mainRegion);
        }
      }
      for (String regionString : regions) {
        T regionCode = converter.apply(regionString);
        regionCodeMap.put(cc, regionCode);
        // At this point, if (hasWorldRegion == true) it's the only region,
        // so we aren't dropping any other regions here.
        if (!hasWorldRegion) {
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
