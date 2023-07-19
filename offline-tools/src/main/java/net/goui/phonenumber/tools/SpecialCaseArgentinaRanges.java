package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.*;
import static com.google.i18n.phonenumbers.metadata.model.RangesTableSchema.ExtType.FIXED_LINE_OR_MOBILE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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

final class SpecialCaseArgentinaRanges {
  private static final RangeSpecification MOBILE_PREFIX =
      RangeSpecification.from(DigitSequence.of("9"));

  private static final ImmutableSet<Integer> AFFECTED_AREA_CODE_LENGTHS = ImmutableSet.of(2, 3, 4);
  private static final ImmutableSet<Column<?>> AFFECTED_COLUMNS =
      ImmutableSet.of(TYPE, AREA_CODE_LENGTH, FORMAT);

  public static RangeTable addSyntheticMobileRanges(
      RangeTable rangeTable, ImmutableMap<String, FormatSpec> formatsTable) {
    // Argentina has some separate FIXED_LINE and MOBILE, but we don't rewrite those.
    RangeTree fixedOrMobile = rangeTable.getRanges(TYPE, FIXED_LINE_OR_MOBILE);
    RangeTable.Builder rewritten = rangeTable.toBuilder();
    for (int n : AFFECTED_AREA_CODE_LENGTHS) {
      RangeTree affectedRanges = fixedOrMobile.intersect(rangeTable.getRanges(AREA_CODE_LENGTH, n));

      RangeTree syntheticMobileRanges = affectedRanges.prefixWith(MOBILE_PREFIX);
      RangeTree unwantedOverlap = syntheticMobileRanges.intersect(rangeTable.getAllRanges());
      checkState(
          unwantedOverlap.isEmpty(),
          "underlying Argentina ranges must not overlap synthetic mobile ranges; overlap: %s",
          unwantedOverlap);

      String syntheticFormatId = getSyntheticFormatId(getFormatIdForAreaCodeLength(n, rangeTable));

      rewritten.assign(TYPE, ExtType.FIXED_LINE, affectedRanges, OverwriteMode.ALWAYS);
      rewritten.assign(TYPE, ExtType.MOBILE, syntheticMobileRanges, OverwriteMode.NEVER);
      rewritten.assign(AREA_CODE_LENGTH, n + 1, syntheticMobileRanges, OverwriteMode.NEVER);
      rewritten.assign(FORMAT, syntheticFormatId, syntheticMobileRanges, OverwriteMode.NEVER);

      RangeTable subTable = rangeTable.subTable(affectedRanges, rangeTable.getSchema());
      for (Column<?> c : Sets.difference(rangeTable.getColumns(), AFFECTED_COLUMNS)) {
        for (Object v : subTable.getAssignedValues(c)) {
          RangeTree copiedMobileRanges = subTable.getRanges(c, v).prefixWith(MOBILE_PREFIX);
          rewritten.assign(c, v, copiedMobileRanges, OverwriteMode.NEVER);
        }
      }
    }
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
      // Assume modified prefix is unique.
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

  private static String getSyntheticFormatId(String affectedId) {
    return "__mobile__" + affectedId;
  }

  private SpecialCaseArgentinaRanges() {}
}
