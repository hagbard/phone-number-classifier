# Java API for Phone Number Classification

This artifact contains basic classes required to build a specialized Java phone
number classifier. If you are using this artifact directly then you may need to
create your own API (alternatively you may be able to find an existing
classifier implementation which suits your needs).

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

## Deciding on Your Features

To create your own client API, you must decide what features you application
needs from a phone number classifier.

A classifier can have the following functionality:
1. Validating phone numbers (without determining a "number type").
    * This basic functionality is present for any classifier. 
2. Classifying phone numbers with respect to some attribute.
    * E.g. "number type", "tariff", "region" etc.
    * This is optional functionality, and creating a classifier without unwanted
      classifiers will greatly reduce the metadata requirements.
3. Parsing phone numbers from user entered text.
    * E.g. Parsing a number such as "(079) 555 1234" in region CH (Switzerland).
    * This is optional functionality, but enabled by default since it uses only
      a small amount of metadata and is commonly useful.
    * Parsing E.164 phone numbers is always available via the `PhoneNumbers`
      class.
4. Formatting phone numbers in either "national" or "international" format.
    * E.g. Formatting an E.164 number such as "+41795551234" as "(079) 555 1234"
      for national dialling.
5. Including example numbers for each region alongside parsing data.
    * This is disabled by default since example number data can be a non-trivial
      fraction of the total data size when using highly simplified data.

## Defining Your Metadata

Once you have determined the API you want to expose, you need to write a
metadata configuration file from which the metadata is generated.

Metadata is generated using code in the `offline-tools` artifact, and requires a
simple configuration file. The configuration file should:

1. Enable any optional "base" metadata needed by you API.
    * E.g. Enabling the `REGION` classifier, so you can determine the CLDR
      region code for phone numbers. 
2. Enable parsing or formatting data needed by your API.
3. Define any custom classifier types for your API.
    * E.g. Defining custom types as combinations of existing base types.
    * This is how you can mimic the types present in Libphonenumber.
4. Specify the degree to which metadata should be simplified.
    * This increases false positive matches for numbers but can greatly
      reduce metadata size.

## Building a Custom Classifier

Once this is decided, it is easy to make your own subclass of
`AbstractPhoneNumberClassifier` which exposes only the API you need to your
business logic. You can use helper methods to easily create you classifier,
parser and formatter APIs to return to your business logic, and a fully
functional classifier class can easily be written in one page of code.

In Java, you will also need to provide a service class which provides the data
to your application, or provide your own mechanism for acquiring the metadata.

A service provider which uses a Java resource to hold the metadata can be
created using a trivial subclass of `AbstractResourceClassifierService` from
the `metadata-loader` artifact. You may also be able to use an existing
classifier in cases where your needs are similar to an existing classifier.

## See It For Yourself

Fully working examples of classifiers are available in the `examples` artifact
with commented code and metadata configuration files. This shows that a useful,
application specific, phone number classifier can be created in a short time and
with minimal effort:

See the classes (in package `net.goui.phonenumbers.examples`):
* `ExampleClassifier`: 14 lines of code.
* `service.SimpleDfaMinimalData`: 3 lines of code.

And the configuration file:
* `resources/simple_dfa_minimal.textproto`: 40 lines of commented configuration.

This is all it takes to prepare your own, smaller, faster, phone number
classifier.