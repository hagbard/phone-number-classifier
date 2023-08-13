package net.goui.phonenumber;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class PhoneNumberResult {
  static PhoneNumberResult of(PhoneNumber phoneNumber, MatchResult matchResult) {
    return new AutoValue_PhoneNumberResult(phoneNumber, matchResult);
  }

  public abstract PhoneNumber getPhoneNumber();

  public abstract MatchResult getResult();
}
