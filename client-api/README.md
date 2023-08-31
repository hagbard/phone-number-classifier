# Client API for Phone Number Classification

This artifact contains basic classes required to build a specialized phone
number classifier. If you are using this artifact directly then you may need
to create your own API (alternatively you may be able to find an existing
classifier implementation which suits your needs).

The PhoneNumbers library makes it easy to create a phone number classifier
which suits your application needs and allows you to create significantly
smaller (less detailed) metadata to suit your requirements.

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

Once you have determined the API you want to expose, you need to write a metadata
configuration file from which the metadata is generated.

Metadata is generated using code in the `offline-tools` artifact, and requires a
simple configuration file. The configuration file should:

1. Enable any optional "base" metadata needed by you API.
    * E.g. Enabling the `REGION` metadata if you wish to be able to classify
      phone numbers according to their CLDR region code. 
2. Enable any parsing or formatting data needed by your API.
3. Define any custom classifier types for your API.
    * E.g. Defining custom types as combinations of existing base types.
    * This is how you can mimic the types present in Libphonenumber.

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
* `SimpleClassifier`: 14 lines of code.
* `service.SimpleDfaMinimalData`: 3 lines of code.

And the configuration file:
* `resources/simple_dfa_minimal.textproto`: 40 lines of commented configuration.

This is all it takes to prepare your own, smaller, faster, phone number
classifier.