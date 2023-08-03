package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import net.goui.phonenumber.DigitSequence;
import net.goui.phonenumber.proto.Metadata;

final class CallingCodeClassifier {

  static CallingCodeClassifier from(
      Metadata.CallingCodeProto callingCodeProto, int typeCount, IntFunction<String> tokenDecoder) {
    ImmutableList<MatcherFunction> matchers =
        callingCodeProto.getMatcherDataList().stream()
            .map(MatcherFunction::fromProto)
            .collect(toImmutableList());
    // For now, assume that if there are no validity matcher indices, we just use 0.
    Function<List<Integer>, MatcherFunction> matcherFactory =
        indices -> indices.isEmpty() ? matchers.get(0) : combinedMatcherOf(matchers, indices);

    MatcherFunction validityMatcher =
        matcherFactory.apply(callingCodeProto.getValidityMatcherIndexList());

    List<Metadata.NationalNumberDataProto> nnd = callingCodeProto.getNationalNumberDataList();
    checkState(
        nnd.size() == typeCount,
        "invalid phone number metadata (unexpected national number data): %s",
        callingCodeProto);
    ImmutableList<TypeClassifier> typeClassifiers =
        IntStream.range(0, typeCount)
            .mapToObj(i -> TypeClassifier.create(nnd.get(i), tokenDecoder, matcherFactory))
            .collect(toImmutableList());

    ImmutableList<DigitSequence> nationalPrefixes =
        callingCodeProto.getNationalPrefixList().stream()
            .map(tokenDecoder::apply)
            .map(DigitSequence::parse)
            .collect(toImmutableList());

    String mainRegion = tokenDecoder.apply(callingCodeProto.getPrimaryRegion());

    String example = callingCodeProto.getExampleNumber();
    Optional<DigitSequence> exampleNumber =
        !example.isEmpty() ? Optional.of(DigitSequence.parse(example)) : Optional.empty();

    return new CallingCodeClassifier(
        validityMatcher, typeClassifiers, nationalPrefixes, mainRegion, exampleNumber);
  }

  private static MatcherFunction combinedMatcherOf(
      ImmutableList<MatcherFunction> matchers, List<Integer> indices) {
    return MatcherFunction.combine(indices.stream().map(matchers::get).collect(toImmutableList()));
  }

  private final MatcherFunction validityMatcher;
  private final ImmutableList<TypeClassifier> typeClassifiers;
  private final ImmutableList<DigitSequence> nationalPrefixes;
  private final String mainRegion;
  private final Optional<DigitSequence> exampleNumber;

  private CallingCodeClassifier(
      MatcherFunction validityMatcher,
      ImmutableList<TypeClassifier> typeClassifiers,
      ImmutableList<DigitSequence> nationalPrefixes,
      String mainRegion,
      Optional<DigitSequence> exampleNumber) {
    this.validityMatcher = validityMatcher;
    this.typeClassifiers = typeClassifiers;
    this.nationalPrefixes = nationalPrefixes;
    this.mainRegion = mainRegion;
    this.exampleNumber = exampleNumber;
  }

  public MatcherFunction getValidityMatcher() {
    return validityMatcher;
  }

  TypeClassifier getTypeClassifier(int typeIndex) {
    return typeClassifiers.get(typeIndex);
  }

  public ImmutableList<DigitSequence> getNationalPrefixes() {
    return nationalPrefixes;
  }

  public Optional<DigitSequence> getExampleNumber() {
    return exampleNumber;
  }

  public String getMainRegion() {
    return mainRegion;
  }
}
