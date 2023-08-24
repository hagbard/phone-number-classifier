const spv = require("phonenumbers_simple_example");
const pjs = require("phonenumbers_js");

/*
 * Example of using the SimplePhoneNumberValidator and other PhoneNumbers library classes in a
 * trivial common JS file.
 */
console.log("---- Example PhoneNumbers validator ----");
let validator = new spv.SimplePhoneNumberValidator();
showE164(validator.getExampleNumber("1").toString());
showE164(validator.getExampleNumber("44").toString());
showE164(validator.getExampleNumber("33").toString());
// Partial number (just the +44 example number with the end chopped off).
showE164("+447400123");

function showE164(s) {
  console.log(`---- Validate: ${s} ----`);
  // In theory this could be null, but we know that large regions have example numbers.
  let number = pjs.PhoneNumber.fromE164(s);
  console.log(`number = ${number.toString()}`);
  // Use enums for the base library to show the names for the returned statuses.
  console.log(`length test = ${pjs.LengthResult[validator.testLength(number)]}`);
  console.log(`match = ${pjs.MatchResult[validator.match(number)]}`);
  console.log(`national format = '${validator.formatNational(number)}'`);
  console.log(`international format = '${validator.formatInternational(number)}'`);
  // Re-parse the national format number with the calling code.
  let parsed = validator.parseLeniently(validator.formatNational(number).toString(), number.getCallingCode());
  console.log(`parsed = ${parsed.toString()}`);
}
