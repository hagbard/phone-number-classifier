# Offline Tools for Generating Classifier Metadata

This artifact provides all the necessary tools to generate metadata for phone
number classifiers. This is the data which underpins all the functionality of
the PhoneNumbers library, and if you are creating your own classifiers, you will
likely need to use these tools.

In order to generate metadata, you run the `GenerateMetadata` tool, and pass in
both the metadata zip (as downloaded from the Libphonenumber project) and a
configuration file. To get started, let's use `example_metadata_config.textproto`
in this directory.

Assuming that we are in the root directory of this project and have downloaded
[`metadata.zip`](https://github.com/google/libphonenumber/blob/master/metadata/metadata.zip)
into that directory, we can just run:

```shell
$ bazel build //offline-tools:generate
$ ./bazel-bin/offline-tools/generate \
    --zip metadata.zip \
    --config_dir offline-tools \
    --config example_metadata_config.textproto
```

which should produce the file `example_metadata_config.json` alongside the config file.

The output file should look something like:

```json
{
  "ver": {
    "maj": 1,
    "min": 0,
    "uri": "goui.net/libphonenumber/example",
    "ver": 1
  },
  "typ": [252, 253, 254, 255],
  "svm": 13,
  ...
```

If you were using this config to generate data for use with real code, you would set the
`default_output_type:` to either `PROTO` or `JSON` (or override this setting by using
`--out_type` on the command line).

The example configuration provides data for the following classifier features:
* Phone number validation for all regions (all classifiers have this).
* Two additional classifiers (named `LPN:TYPE` and `REGION`).
* Formatting for both national and international formats.
* Parsing and example numbers for all regions.

which match the semantics of Libphoneumber's classification for many common use cases.

By extending the abstract base classes provided in the `javascript` (TypeScript) or
`client-api` (Java) projects, you can now expose these features to your client code.

For examples of client API code, see:
* `javascript/tests/example-classifier.ts`
* `examples/src/main/java/net/goui/phonenumbers/examples/LibPhoneNumberClassifier.java`

* `javascript-simple/src/index.ts` (reduced API)
* `examples/src/main/java/net/goui/phonenumbers/examples/SimpleClassifier.java`