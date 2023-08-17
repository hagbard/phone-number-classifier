package net.goui.phonenumber;

import com.google.auto.value.AutoValue;

/**
 * Encapsulates a parsed phone number and an associated match result. This is returned for strict
 * parsing and the match result can be used to provide user feedback for phone number entry.
 */
@AutoValue
public abstract class PhoneNumberResult<T> {

  public enum ParseFormat {
    NATIONAL,
    INTERNATIONAL,
    UNKNOWN;
  }

  static <T> PhoneNumberResult<T> of(
      PhoneNumber phoneNumber, MatchResult matchResult, ParseFormat formatType) {
    return new AutoValue_PhoneNumberResult<T>(phoneNumber, matchResult, formatType);
  }

  /** Returns the parsed phone number (which need not be valid). */
  public abstract PhoneNumber getPhoneNumber();

  /**
   * Returns the match result for the phone number, according to the parser's metadata. If the
   * result is {@link MatchResult#MATCHED}, then parsing was completely successful and unambiguous.
   */
  public abstract MatchResult getMatchResult();

  public abstract ParseFormat getInferredFormat();

  boolean isBetterThan(PhoneNumberResult<T> other) {
    return getMatchResult().compareTo(other.getMatchResult()) <= 0;
  }
}
