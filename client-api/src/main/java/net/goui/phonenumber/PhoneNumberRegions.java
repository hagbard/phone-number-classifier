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

  PhoneNumberRegions(RawClassifier rawClassifier, Function<String, T> converter) {
    checkState(
        rawClassifier.getSupportedNumberTypes().contains("REGION"),
        "Region information not present in underlying metadata");

    ImmutableListMultimap.Builder<DigitSequence, T> regionCodeMap = ImmutableListMultimap.builder();
    ImmutableMap.Builder<T, DigitSequence> callingCodeMap = ImmutableMap.builder();
    for (DigitSequence cc : rawClassifier.getSupportedCallingCodes()) {
      List<T> regions = new ArrayList<>();
      String mainRegion = rawClassifier.getMainRegion(cc);
      regions.add(converter.apply(mainRegion));
      rawClassifier.getValueMatcher(cc, "REGION").getPossibleValues().stream()
          .sorted()
          .filter(r -> !r.equals(mainRegion))
          .map(converter)
          .forEach(regions::add);
      regionCodeMap.putAll(cc, regions);
      regions.forEach(r -> callingCodeMap.put(r, cc));
    }
    this.regionCodeMap = regionCodeMap.build();
    this.callingCodeMap = callingCodeMap.buildOrThrow();
  }

  /**
   * Returns a sorted list of the CLDR region codes for the given country calling code.
   *
   * <p>This list is sorted alphabetically, except the first element, which is always the "main
   * region" associated with the calling code (e.g. "US" for "1", "GB" for "44").
   *
   * <p>If the given calling code is not supported, an empty list is returned.
   */
  public ImmutableList<T> getRegions(DigitSequence callingCode) {
    return regionCodeMap.get(callingCode);
  }

  /**
   * Returns the country calling code for the specified CLDR region code.
   *
   * <p>If the given region code is not supported, {@link Optional#empty()} is returned.
   */
  public Optional<DigitSequence> getCallingCode(T region) {
    return Optional.ofNullable(callingCodeMap.get(region));
  }
}
