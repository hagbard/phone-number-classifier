package net.goui.phonenumber;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class PhoneNumberResult {
  static PhoneNumberResult result(
      DigitSequence callingCode, DigitSequence nationalNumber, MatchResult matchResult) {
    return new AutoValue_PhoneNumberResult(PhoneNumbers.create(callingCode, nationalNumber), matchResult);
  }

  public abstract PhoneNumber getPhoneNumber();

  public abstract MatchResult getResult();
}
