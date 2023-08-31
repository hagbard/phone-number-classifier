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
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.AREA_CODE_LENGTH;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.ExtType;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.ExtType.FIXED_LINE_OR_MOBILE;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.FORMAT;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.TYPE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.i18n.phonenumbers.metadata.DigitSequence;
import com.google.i18n.phonenumbers.metadata.RangeSpecification;
import com.google.i18n.phonenumbers.metadata.RangeTree;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec;
import com.google.i18n.phonenumbers.metadata.model.FormatSpec.FormatTemplate;
import com.google.i18n.phonenumbers.metadata.table.Column;
import com.google.i18n.phonenumbers.metadata.table.RangeTable;
import com.google.i18n.phonenumbers.metadata.table.RangeTable.OverwriteMode;
import java.util.Optional;

/**
 * A very special case class to transform Argentina ranges and number formats to account for the
 * unique way in which these numbers are handled.
 *
 * <p>In the Argentinian numbering plan the distinction between fixed line and mobile numbers is not
 * explicit, but must still be accounted for when dialing. To dial a mobile number from within
 * Argentina you must add the "mobile token" {@code 15} after the area code.
 *
 * <ul>
 *   <li>2 digit area code: {@code 011 15-1234-5678} (Buenos Aires)
 *   <li>3 digit area code: {@code 0380 15-123-4567} (La Rioja)
 *   <li>4 digit area code: {@code 02966 15-12-3456} (Río Gallegos)
 * </ul>
 *
 * <p>However, to dial the same number from outside Argentina, a mobile prefix must be added
 * <em>before</em> the area code:
 *
 * <ul>
 *   <li>2 digit area code: {@code +54 9 11 1234-5678} (Buenos Aires)
 *   <li>3 digit area code: {@code +54 9 380 123-4567} (La Rioja)
 *   <li>4 digit area code: {@code +54 9 2966 12-3456} (Río Gallegos)
 * </ul>
 *
 * <p>And finally, for fixed line numbers, neither of the above rules apply.
 *
 * <p>The mobile vs fixed line distinction is not explicit in the raw range data, and all Argentine
 * numbers are 10-digits in length. The underlying data simply encodes 10-digit ranges and assigns
 * {@code FIXED_LINE_OR_MOBILE} to these "geographic" numbers.
 *
 * <p>In order to support mobile numbers and mobile formatting, without needing special code to
 * handle it, the following changes are made:
 *
 * <ol>
 *   <li>New synthetic 11-digit mobile ranges are added for existing 10-digit {@code
 *       FIXED_LINE_OR_MOBILE} numbers (e.g. {@code 1234567890} is duplicated as {@code
 *       91234567890}).
 *   <li>The new ranges ranges are assigned to type {@code MOBILE}.
 *   <li>The new ranges have their area code length extended by 1.
 *   <li>The new ranges are assigned new synthetic formats derived from the original format.
 *   <li>All other remaining columns are copied from the original ranges into the new ranges.
 *   <li>The <em>original</em> ranges are assigned to type {@code FIXED_LINE}.
 * </ol>
 *
 * <p>This does mean that 11-digit numbers are now "possible by length" in Argentina (which isn't
 * strictly true) and that mobile numbers have area codes of length 3, 4 and 5, rather than the
 * expected 2, 3 and 4.
 *
 * <p>It also doesn't provide any functionality to know that the numbers {@code 91234567890} and
 * {@code 1234567890} are really aliases, and these pairs of numbers have no explicit connection in
 * the data.
 *
 * <p>For details see: <a href="https://en.wikipedia.org/wiki/Telephone_numbers_in_Argentina">
 * Telephone numbers in Argentina</a>.
 */
final class SpecialCaseArgentinaRanges {
  private static final RangeSpecification MOBILE_PREFIX =
      RangeSpecification.from(DigitSequence.of("9"));

  private static final int AFFECTED_NUMBER_LENGTH = 10;
  private static final ImmutableSet<Integer> AFFECTED_AREA_CODE_LENGTHS = ImmutableSet.of(2, 3, 4);
  private static final ImmutableSet<Column<?>> AFFECTED_COLUMNS =
      ImmutableSet.of(TYPE, AREA_CODE_LENGTH, FORMAT);

  public static RangeTable addSyntheticMobileRanges(
      RangeTable rangeTable, ImmutableMap<String, FormatSpec> formatsTable) {
    // Argentina has some separate FIXED_LINE and MOBILE, but we don't rewrite those.
    RangeTree fixedOrMobile = rangeTable.getRanges(TYPE, FIXED_LINE_OR_MOBILE);
    // If this ever fails we could just extract only the 10 digits numbers, but it's likely
    // something else is going to be affected, so don't assume that's safe.
    checkState(
        Iterables.getOnlyElement(fixedOrMobile.getLengths()) == AFFECTED_NUMBER_LENGTH,
        "Unexpected lengths for FIXED_LINE_OR_MOBILE ranges: %s",
        fixedOrMobile.getLengths());

    RangeTable.Builder rewritten = rangeTable.toBuilder();
    RangeTree allAffected = RangeTree.empty();
    for (int n : AFFECTED_AREA_CODE_LENGTHS) {
      // Process each area code length separately since this affects the synthetic formats.
      RangeTree affectedRanges = fixedOrMobile.intersect(rangeTable.getRanges(AREA_CODE_LENGTH, n));
      allAffected = allAffected.union(affectedRanges);

      RangeTree syntheticMobileRanges = affectedRanges.prefixWith(MOBILE_PREFIX);
      RangeTree unwantedOverlap = syntheticMobileRanges.intersect(rangeTable.getAllRanges());
      checkState(
          unwantedOverlap.isEmpty(),
          "Underlying Argentina ranges must not overlap synthetic mobile ranges; overlap: %s",
          unwantedOverlap);

      String syntheticFormatId = getSyntheticFormatId(getFormatIdForAreaCodeLength(n, rangeTable));

      // Set modified values in the new synthetic ranges (there should be any assigned values).
      rewritten.assign(TYPE, ExtType.MOBILE, syntheticMobileRanges, OverwriteMode.NEVER);
      rewritten.assign(AREA_CODE_LENGTH, n + 1, syntheticMobileRanges, OverwriteMode.NEVER);
      rewritten.assign(FORMAT, syntheticFormatId, syntheticMobileRanges, OverwriteMode.NEVER);

      // Copy any other column data across as-is.
      RangeTable subTable = rangeTable.subTable(affectedRanges, rangeTable.getSchema());
      for (Column<?> c : Sets.difference(rangeTable.getColumns(), AFFECTED_COLUMNS)) {
        for (Object v : subTable.getAssignedValues(c)) {
          RangeTree copiedMobileRanges = subTable.getRanges(c, v).prefixWith(MOBILE_PREFIX);
          rewritten.assign(c, v, copiedMobileRanges, OverwriteMode.NEVER);
        }
      }

      // Finally reset the existing FIXED_LINE_OR_MOBILE ranges to just FIXED_LINE.
      rewritten.assign(TYPE, ExtType.FIXED_LINE, affectedRanges, OverwriteMode.ALWAYS);
    }

    // Double check that all ranges were process (i.e. no unexpected area code lengths).
    RangeTree unprocessed = fixedOrMobile.subtract(allAffected);
    checkState(
        unprocessed.isEmpty(),
        "Not all FIXED_LINE_OR_MOBILE ranges accounted for; remaining range: %s",
        unprocessed);
    return rewritten.build();
  }

  public static ImmutableMap<String, FormatSpec> addSyntheticMobileFormats(
      RangeTable rangeTable, ImmutableMap<String, FormatSpec> formatsTable) {
    ImmutableMap.Builder<String, FormatSpec> rewritten = ImmutableMap.builder();
    rewritten.putAll(formatsTable);
    for (int n : AFFECTED_AREA_CODE_LENGTHS) {
      String affectedId = getFormatIdForAreaCodeLength(n, rangeTable);
      FormatSpec formatSpec = checkNotNull(formatsTable.get(affectedId));
      checkState(
          formatSpec.carrier().isEmpty(),
          "Expected no carrier code format for %s-digit area codes; was: %s",
          n,
          formatSpec.carrier());
      FormatSpec rewrittenSpec =
          FormatSpec.of(
              nationalFormatRewriteForMobile(formatSpec),
              Optional.empty(),
              Optional.of(internationalFormatRewriteForMobile(formatSpec)),
              formatSpec.local().map(FormatTemplate::getSpecifier),
              formatSpec.nationalPrefixOptional(),
              Optional.empty());
      // Assume modified prefix is unique (if it isn't, buildOrThrow() will fail).
      rewritten.put(getSyntheticFormatId(affectedId), rewrittenSpec);
    }
    return rewritten.buildOrThrow();
  }

  private static String getFormatIdForAreaCodeLength(int n, RangeTable rangeTable) {
    RangeTable subTable =
        rangeTable.subTable(rangeTable.getRanges(AREA_CODE_LENGTH, n), rangeTable.getSchema());
    checkState(!subTable.isEmpty(), "Missing %s digit area codes", n);

    ImmutableSet<String> assignedFormatIds = subTable.getAssignedValues(FORMAT);
    checkState(
        assignedFormatIds.size() == 1,
        "Expect only one format for %s-digit area codes; was: %s",
        n,
        assignedFormatIds);
    return assignedFormatIds.stream().iterator().next();
  }

  private static String nationalFormatRewriteForMobile(FormatSpec formatSpec) {
    FormatTemplate spec = formatSpec.national();
    checkState(spec.hasNationalPrefix(), "National formats must have national prefix.");
    checkState(
        spec.minLength() == 10
            && spec.maxLength() == 10
            && spec.getSpecifier().matches("^#X{2,4} X+-X+$"),
        "Unexpected national format specifier: %s",
        spec);
    // Transforms the 10 digit national number formats into 11-digit formats for synthetic mobile
    // ranges. The additional synthetic '9' is removed during formatting via the '{X>}' replacement.
    //
    // "#XX XXXX-XXXX" --> "#{X>}XX 15-XXXX-XXXX"
    // "#XXX XXX-XXXX" --> "#{X>}XXX 15-XXX-XXXX"
    // "#XXXX XX-XXXX" --> "#{X>}XXXX 15-XX-XXXX"
    return spec.getSpecifier().replaceAll("^#(X{2,4}) ", "#{X>}$1 15-");
  }

  private static String internationalFormatRewriteForMobile(FormatSpec formatSpec) {
    FormatTemplate spec =
        formatSpec
            .international()
            .orElseThrow(() -> new IllegalStateException("Missing international format specifier"));
    checkState(
        spec.minLength() == 10
            && spec.maxLength() == 10
            && spec.getSpecifier().matches("^X{2,4} X+-X+$"),
        "Unexpected international format specifier: %s",
        spec);
    // For international mobile numbers, just format the synthetic '9' prefix at the front.
    return "X " + spec.getSpecifier();
  }

  // Applying any unique prefix here will work (and if it isn't unique, code will fail).
  private static String getSyntheticFormatId(String affectedId) {
    return "__mobile__" + affectedId;
  }

  private SpecialCaseArgentinaRanges() {}
}
