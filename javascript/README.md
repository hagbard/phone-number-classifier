# PhoneNumbers validation library

The PhoneNumbers library allows you to build fast, efficient client code to classify and validate
phone numbers. By letting clients decide what data they need, and control how it is processed, it
is possible to parse, validate and format phone numbers, for some or all regions in the world, in
a fraction of the code/data required by Libphonenumber.

Assuming a simple use case which requires parsing, validation and formatting for "normal" phone
numbers, we can compare the required metadata size between Libphonenumber and this library

| Library         | Protocol Buffer Metadata Size | JSON Metadata Size |
|-----------------|-------------------------------|--------------------|
| Libphonenumber  | 225kB (built in)              | 250kB (built in)   |
| PhoneNumbers    | 39KB (23.5kB zipped)          | 69kB (26kB zipped) | 

And for a completely minimal representation, with the maximally simplified metadata, and no parsing
or formatting support, the metadata size (zipped) can be as small as 5Kb.

See https://github.com/hagbard/phone-numbers/blob/main/examples/src/main/resources/README.md
for more details.

## Integration

To create a new phone number classifier with appropriate metadata, you need to:
1. Write a subclass of `AbstractPhoneNumberClassifier` (see `phone-number-classifier.js`)
    * See https://github.com/hagbard/phone-numbers/blob/main/javascript/tests/index.test.ts
        for a simple example.
2. Implement the classifiers you wish to expose to your application code.
    * Use the help methods you subclass's constructor (e.g.
        `super.forValues(...).singleValuedMatcher();`)
3. Download/clone the offline tools part of this project from GitHub:
    * https://github.com/hagbard/phone-numbers
    * `gh repo clone hagbard/phone-numbers`
4. Write a metadata configuration to build matching metadata.
    * See https://github.com/hagbard/phone-numbers/blob/main/offline-tools/src/main/protobuf/config.proto
    * And https://github.com/hagbard/phone-numbers/blob/main/javascript/tests/lpn_dfa_compact.textproto
5. Run the `GenerateMetadata` tool to emit the `json` file to match your custom schema and bundle
    that alongside your client code.

If you are using this library via a wrapper library, you may only need to select and download the
metadata you need based on your size/accuracy requirements.

To reduce JavaScript code size, comments are omitted from the generated JavaScript code, but you
can read them in the TypeScript source:
https://github.com/hagbard/phone-numbers/tree/main/javascript/src.