/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.function.Function.identity;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.goui.phonenumber.PhoneNumberFormatter.FormatType;
import net.goui.phonenumber.PhoneNumbers.E164PhoneNumber;
import net.goui.phonenumber.metadata.ClassifierLoader;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.VersionInfo;

/**
 * Base class from which type safe custom classifiers can be easily derived.
 *
 * <p>This class should be extended by anyone making their own phone number classifier for custom
 * schemas. This permits a "raw classifier" which deals only with string types and values to be
 * easily wrapped in a type safe API.
 *
 * <h3>Using Simplified Metadata</h3>
 *
 * <p>When simplified metadata is used for a classifier, the chance of false positive results is
 * increased. This means that a number which would be "invalid" when using precise metadata may now
 * be viewed as valid for one or more values. However, any number which would be valid when using
 * precise metadata will always return the same result when using simplified data (i.e. you cannot
 * get false negative results).
 *
 * <h3>Expected Class Usage</h3>
 *
 * <p>The basic pattern for using this class is as follows:
 *
 * <pre>{@code
 * public final class MyClassifier extends AbstractPhoneNumberClassifier {
 *   public static MyClassifier load(SchemaVersion version, SchemaVersion... rest) {
 *     return new MyClassifier(
 *         AbstractPhoneNumberClassifier.loadRawClassifier(version, rest));
 *   }
 *
 *   // Add one private+final field for each classifier/matcher you wish to expose to users.
 *   // Use methods such as "forEnum(...)" to obtain a factory for the matcher/classifier you need.
 *   private final SingleValuedMatcher<MyCustomEnum> myCustomAttributeMatcher =
 *       forEnum("CUSTOM:TYPE", MyCustomEnum.class).singleValuedMatcher();
 *
 *   private MyClassifier(RawClassifier rawClassifier) {
 *     super(rawClassifier);
 *   }
 *
 *   // Expose each classifier/matcher via a getter, with the name "for<NameOfAttribute>".
 *   public SingleValuedMatcher<MyCustomEnum> forMyCustomAttribute() {
 *     return myCustomAttributeMatcher;
 *   }
 * }
 * }</pre>
 *
 * <p>In the example above, a {@link SingleValuedMatcher} will be created which uses {@code
 * MyCustomEnum} to represent values for the custom attribute.
 *
 * <p>From the user's perspective they can now do:
 *
 * <pre>{@code
 * MyCustomEnum attribute = myClassifier.forMyCustomAttribute().identify(number);
 * }</pre>
 *
 * where {@code MyCustomEnum} would be something like:
 *
 * <pre>{@code
 * enum MyCustomEnum { FOO, BAR, BAZ }
 * }</pre>
 *
 * and the metadata configuration file contains something like:
 *
 * <pre>{@code
 * classifier {
 *   // A custom type name is of the form "<prefix>:<suffix>"
 *   type_name: "CUSTOM:TYPE"
 *   custom {
 *     is_single_valued: true
 *     // Value names must exactly match enum names in MyCustomEnum.
 *     value { name: "FOO" ranges: "... define range expression here ..." }
 *     value { name: "BAR" ranges: "... define range expression here ..." }
 *     default_value: "BAZ"
 *   }
 * }
 * }</pre>
 *
 * <p><em>Important</em>: The enum values must exactly match the values defined in the custom
 * classifier configuration from which the metadata for this schema is defined. Adding new values to
 * the metadata will require new enum values to be added in client code, which makes this a
 * potentially breaking change for clients. So if you need to add new attribute values, you must add
 * them first in the client code (where additional enum values are ignored) and only update the
 * generated metadata once all clients are updated.
 *
 * <p>Alternatively, you can define a new schema for the extended type and encourage users to
 * upgrade, but this requires a new version of the client code.
 *
 * <p>To avoid these problems, it's best to carefully consider which attributes you care about
 * before publishing a schema definition publicly.
 */
public abstract class AbstractPhoneNumberClassifier {
  private static final int MAJOR_DATA_VERSION = 1;
  private static final int MINIMUM_MINOR_DATA_VERSION = 0;

  private static final Predicate<VersionInfo> DATA_VERSION_PREDICATE =
      v ->
          v.getMajorDataVersion() == MAJOR_DATA_VERSION
              && v.getMinorDataVersion() >= MINIMUM_MINOR_DATA_VERSION;

  private static final Comparator<VersionInfo> VERSION_ORDERING =
      Comparator.comparing(VersionInfo::getSchemaVersion)
          .thenComparing(VersionInfo::getMajorDataVersion)
          .thenComparing(VersionInfo::getMinorDataVersion)
          .reversed();

  /**
   * Immutable specification for a metadata schema. This is used when loading metadata to ensure it
   * is compatible with the expectations in the client code.
   */
  @AutoValue
  public abstract static class SchemaVersion {
    /** Creates a schema version with which to match metadata. */
    public static SchemaVersion of(String schema, int minVersion) {
      checkArgument(!schema.isEmpty(), "schema string must not be empty");
      checkArgument(minVersion >= 0, "schema minimum version must not be negative");
      return new AutoValue_AbstractPhoneNumberClassifier_SchemaVersion(schema, minVersion);
    }

    /** Returns the schema URI (an arbitrary identifier string). */
    public abstract String schema();

    /** Return the minimal schema version which would satisfy this version. */
    public abstract int minVersion();

    /** Returns whether this version is satisfied by the given version. */
    final boolean isSatisfiedBy(VersionInfo version) {
      return version.getSchema().equals(schema()) && version.getSchemaVersion() >= minVersion();
    }

    @Override
    public String toString() {
      return String.format("schema{'%s' [version >= %d]}", schema(), minVersion());
    }
  }

  /**
   * Called from a subclass static factory method to load metadata compatible with at least one of
   * the given schemas.
   *
   * @return a raw classifier configured with the "best match" metadata from the available service
   *     loaders.
   */
  protected static RawClassifier loadRawClassifier(SchemaVersion version, SchemaVersion... rest) {
    Predicate<VersionInfo> schemaPredicate = version::isSatisfiedBy;
    for (SchemaVersion v : rest) {
      schemaPredicate = schemaPredicate.or(v::isSatisfiedBy);
    }
    ImmutableList<RawClassifier> rawClassifiers =
        ClassifierLoader.loadMatchedVersions(
            DATA_VERSION_PREDICATE.and(schemaPredicate), VERSION_ORDERING);
    if (!rawClassifiers.isEmpty()) {
      return rawClassifiers.get(0);
    }
    List<SchemaVersion> versions = new ArrayList<>();
    versions.add(version);
    versions.addAll(Arrays.asList(rest));
    throw new IllegalStateException(
        String.format("no matching classifier metadata found for versions: %s", versions));
  }

  private final RawClassifier rawClassifier;

  /**
   * @param rawClassifier a raw classifier matching the expected schema used by the subclass.
   */
  protected AbstractPhoneNumberClassifier(RawClassifier rawClassifier) {
    this.rawClassifier = checkNotNull(rawClassifier);
  }

  // Protected so subclasses can expose it for regression testing.
  protected final RawClassifier rawClassifier() {
    return rawClassifier;
  }

  public ImmutableSet<DigitSequence> getSupportedCallingCodes() {
    return rawClassifier.getSupportedCallingCodes();
  }

  public boolean isSupportedCallingCode(DigitSequence callingCode) {
    return getSupportedCallingCodes().contains(callingCode);
  }

  /**
   * Returns a formatter for the given format type.
   *
   * <p>Subclasses are expected to create and cache formatter instances during construction to avoid
   * users risking runtime exceptions later.
   *
   * <p>This method will fail if the underlying format metadata for the number type is not present,
   * but since a classifier subclass should know what metadata its schema defines to be present,
   * this should not be an issue (the subclass can test for formatting data before invoking this if
   * necessary).
   *
   * @throws IllegalStateException if the format type is not present in the underlying metadata.
   */
  protected PhoneNumberFormatter createFormatter(FormatType type) {
    checkState(canFormat(type), "No format data available for type: %s", type);
    return new PhoneNumberFormatter(rawClassifier, type);
  }

  /**
   * Returns whether a classifier has the required format metadata for a type.
   *
   * <p>In general a subclass of `AbstractPhoneNumberClassifier` should know implicitly if a format
   * type is supported, so a runtime check should not be needed. However, it is foreseeable that
   * some schemas could define formatting to be optional and fall back to simple block formatting
   * for missing data.
   */
  protected boolean canFormat(PhoneNumberFormatter.FormatType type) {
    return rawClassifier.getSupportedNumberTypes().contains(type.id);
  }

  /**
   * Returns region information for this classifier.
   *
   * <p>Subclasses are expected to create and cache region information during construction to avoid
   * users risking runtime exceptions later.
   *
   * <p>This method will fail if the underlying region metadata is not present, but since a
   * classifier subclass should know what metadata its schema defines to be present, this should not
   * be an issue (the subclass can test for region data before invoking this if necessary).
   *
   * @throws IllegalStateException if region information is not present in the underlying metadata.
   */
  protected <T> PhoneNumberParser<T> createParser(Function<String, T> converter) {
    return new PhoneNumberParser<>(rawClassifier, converter);
  }

  /**
   * Returns an example phone number for the given calling code (if available).
   *
   * <p>It is not always possible to guarantee example numbers will exist for every metadata
   * configuration, and it is unsafe to invent example numbers at random (since they might be
   * accidentally callable, which can cause problems).
   */
  public Optional<PhoneNumber> getExampleNumber(DigitSequence callingCode) {
    return rawClassifier
        .getExampleNationalNumber(callingCode)
        .map(nn -> E164PhoneNumber.of(callingCode, nn));
  }

  /**
   * Tests a phone number against the possible lengths of any number in its numbering plan. This
   * method is fast, but takes no account of the number type.
   *
   * <p>If this returns {@link LengthResult#TOO_SHORT}, {@link LengthResult#TOO_LONG} or {@link
   * LengthResult#INVALID_LENGTH}, then the number definitely cannot be valid for any possible type.
   *
   * <p>However, if it returns {@link LengthResult#POSSIBLE}, it may still not be a valid number.
   *
   * @param number an E.164 phone number, including country calling code.
   * @return a simply classification based only on the number's length for its calling code.
   */
  public final LengthResult testLength(PhoneNumber number) {
    return rawClassifier.testLength(number.getCallingCode(), number.getNationalNumber());
  }

  /**
   * Matches a phone number against the set of valid ranges.
   *
   * <p>This is a more accurate test than {@link #testLength(PhoneNumber)}, and returns useful
   * information about the phone number, but will take longer to perform.
   *
   * <p>For example, if this returns {@link MatchResult#PARTIAL_MATCH}, then the given number is a
   * prefix of at least one valid number (e.g. adding more digits may produce a valid number).
   * However, if it returns {@link MatchResult#INVALID}, then no additional digits can make the
   * number valid. This can be useful for giving feedback to users when they are entering numbers.
   *
   * @param number an E.164 phone number, including country calling code.
   * @return a classification based on the valid number ranges for its calling code.
   */
  public final MatchResult match(PhoneNumber number) {
    return rawClassifier.match(number.getCallingCode(), number.getNationalNumber());
  }

  /**
   * Factory class for creating type-safe classifiers. This is used as part of a fluent statement to
   * create type-safe classifiers in subclasses.
   */
  protected static final class ClassifierFactory<V> {
    private final Supplier<TypeClassifier<V>> newFn;

    ClassifierFactory(Supplier<TypeClassifier<V>> newFn) {
      this.newFn = checkNotNull(newFn);
    }

    /**
     * Returns a simple (non-partial matching) classifier. It is necessary to use this API when the
     * underlying metadata has disabled partial matching.
     */
    public Classifier<V> classifier() {
      return newFn.get();
    }

    /**
     * Returns a classifier capable of partial matching. Use this API if the underlying metadata
     * supports partial matching to expose additional methods to users.
     */
    public Matcher<V> matcher() {
      return newFn.get().ensureMatcher();
    }

    /**
     * Returns a simple (non partial matching) classifier, for which values are unique. This adds
     * the {@link SingleValuedClassifier#identify(PhoneNumber)} method for identifying unique values
     * more simply.
     *
     * <p>To use this classifier, the metadata it uses must have been built with {@code
     * is_single_valued: true} in the configuration, or be one of the known single valued basic
     * types (e.g. "TYPE" or "TARIFF", but not "REGION" etc.).
     */
    public SingleValuedClassifier<V> singleValuedClassifier() {
      return newFn.get().ensureSingleValued();
    }

    /**
     * Returns a classifier capable of partial matching, for which values are unique. This adds the
     * {@link SingleValuedClassifier#identify(PhoneNumber)} method for identifying unique values
     * more simply.
     *
     * <p>To use this classifier, the metadata it uses must have been built with {@code
     * is_single_valued: true} in the configuration, or be one of the known single valued basic
     * types (e.g. "TYPE" or "TARIFF", but not "REGION" etc.).
     */
    public SingleValuedMatcher<V> singleValuedMatcher() {
      return newFn.get().ensureSingleValued().ensureMatcher();
    }
  }

  /** Factory method for subclasses to create type safe classifiers. */
  protected final <V> ClassifierFactory<V> forType(
      String typeName,
      Class<V> typeClass,
      Function<String, V> toValue,
      Function<? super V, String> toString) {
    return new ClassifierFactory<>(() -> newClassifier(typeName, typeClass, toValue, toString));
  }

  /** Factory method for subclasses to create type safe classifiers. */
  protected final <V extends Enum<V>> ClassifierFactory<V> forEnum(
      String typeName, Class<V> enumClass) {
    return new ClassifierFactory<>(
        () -> newClassifier(typeName, enumClass, s -> Enum.valueOf(enumClass, s), Enum::name));
  }

  /**
   * Factory method for subclasses to create string based classifiers. In general you should use
   * type-safe classifiers whenever possible.
   */
  protected final ClassifierFactory<String> forString(String typeName) {
    return new ClassifierFactory<>(
        () -> newClassifier(typeName, String.class, identity(), identity()));
  }

  private <V> TypeClassifier<V> newClassifier(
      String typeName,
      Class<V> typeClass,
      Function<String, V> toValue,
      Function<? super V, String> toString) {
    checkArgument(
        rawClassifier().getSupportedNumberTypes().contains(typeName),
        "unsupported type '%s' for classifier; possible types: %s",
        typeName,
        rawClassifier().getSupportedNumberTypes());
    return new TypeClassifier<>(this, typeName, toValue, toString, typeClass);
  }

  /** Simple (non-partial matching) classifier API supported by all classifiers. */
  public interface Classifier<V> {
    /**
     * Classifies a complete phone number into a set of possible values for a number type.
     *
     * <p>For example, when classifying regions, the number {@code "+447691123456"} is classified as
     * belonging to any of the regions {@code "GB", "GG" or "JE"}.
     *
     * <p>If an invalid number is given, it will not be classified as any value, resulting in an
     * empty set. The also applies if the number is a partial number or has extra digits.
     *
     * <p>If the underlying metadata for the type being used in "single valued", then the resulting
     * set will never contain more than one entry (though it may be empty). However, in this case it
     * is recommended to call {@link SingleValuedClassifier#identify(PhoneNumber)} instead.
     */
    Set<V> classify(PhoneNumber number);
  }

  /** Extended classifier API which permits partial matching for number types. */
  public interface Matcher<V> extends Classifier<V> {
    /**
     * Returns the possible values which a phone number or prefix could be classified as.
     *
     * <p>This method is intended for use when phone numbers are being entered or analyzed for
     * issues. Most commonly it is expected that partial/incomplete phone numbers would be passed to
     * this method in order to determine what values are still possible as the number is entered.
     * This can be used to provided feedback to users, such as indicating likely errors at the point
     * they occur.
     *
     * <p>If a complete phone number is given, this is the same as calling {@link
     * Classifier#classify(PhoneNumber)}.
     */
    Set<V> getPossibleValues(PhoneNumber number);

    /**
     * Matches a phone number or prefix to determine its status with respect to a given value.
     *
     * <p>For example, a phone number may be a {@link MatchResult#PARTIAL_MATCH} for {@code
     * FIXED_LINE} but would be {@link MatchResult#INVALID} for {@code MOBILE}. This method is
     * intended for use when phone numbers are being entered or analyzed for issues.
     */
    MatchResult match(PhoneNumber number, V value);

    /**
     * Matches a phone number or prefix to determine its status with respect to a set of values.
     *
     * <p>This is fundamentally the same as {@link #match(PhoneNumber, V)}, but merges the results
     * for several values. This is useful if a user's business logic treats two or more values in
     * the same way (e.g. {@code MOBILE} and {@code FIXED_LINE_OR_MOBILE}).
     */
    MatchResult match(PhoneNumber number, V... values);

    /**
     * Matches a phone number or prefix to determine its status with respect to a set of values.
     *
     * <p>This is fundamentally the same as {@link #match(PhoneNumber, V)}, but merges the results
     * for several values. This is useful if a user's business logic treats two or more values in
     * the same way (e.g. {@code MOBILE} and {@code FIXED_LINE_OR_MOBILE}).
     */
    MatchResult match(PhoneNumber number, Set<V> values);
  }

  /** Single valued classifier API, which has an easier way to classify values uniquely. */
  public interface SingleValuedClassifier<V> extends Classifier<V> {
    /**
     * Classifies a complete phone number as a unique value for a number type.
     *
     * <p>If an invalid number is given, it will not be classified as any value, returning {@link
     * Optional#empty()}. This also applies if the number is a partial number or has extra digits.
     *
     * <p>For single valued types, this is expected to be more convenient than calling {@link
     * Classifier#classify(PhoneNumber)} and getting back a set with zero or one elements in it.
     */
    Optional<V> identify(PhoneNumber number);
  }

  /** Single valued matcher API, which has an easier way to classify values uniquely. */
  public interface SingleValuedMatcher<V> extends Matcher<V>, SingleValuedClassifier<V> {}
}
