# Offline Tools for Generating Classifier Metadata

This artifact provides all the necessary tools to generate metadata for phone
number classifiers. This is the data which underpins all the functionality of
the PhoneNumbers library, and if you are creating your own classifiers, you will
likely need to use these tools.

## Generating Metadata

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
}
```

If you were using this config to generate data for use with real code, you would set the
`default_output_type:` to either `PROTO` or `JSON` (or override this setting by using
`--out_type` on the command line).

The example configuration provides data for the following classifier features:
* Phone number validation for all regions (all classifiers have this).
* Two additional classifiers (named `LPN:TYPE` and `REGION`).
* Formatting for both national and international formats.
* Parsing and example numbers for all regions.

which match the semantics of Libphonenumber's classification for many common use cases.

By extending the abstract base classes provided in the `javascript` (TypeScript) or
`client-api` (Java) projects, you can now expose these features to your client code.

For examples of client API code which is compatible with this example data, see:
* `javascript/tests/example-classifier.ts`
* `examples/src/main/java/net/goui/phonenumbers/examples/ExampleClassifier.java`

## Generating Test Data

As well as metadata, these tools can be used to (re)generate regression test data.
This is necessary if a new version of the metadata zip is used to generate classifier
data (since ranges may be removed or modified).

Regression test data is stored as a JSON file and looks something like:

```json
{
  "testdata": [
    {
      "cc": "1",
      "number": "2016264238",
      "result": [
        { "type": "LPN:TYPE", "values": ["FIXED_LINE_OR_MOBILE"] },
        { "type": "REGION", "values": ["US"] }
      ],
      "format": [
        { "type": "NATIONAL_FORMAT", "value": "(201) 626-4238" },
        { "type": "INTERNATIONAL_FORMAT", "value": "+1 201-626-4238" }
      ]
    },
  ],
  ...
}
```

In order to provide maximum coverage, test data is created according to the
defined classifiers for a specific configuration, so you must regenerate each
test data file from its associated configuration.

If, after downloading a new version of
[`metadata.zip`](https://github.com/google/libphonenumber/blob/master/metadata/metadata.zip)
regression tests start to fail, locate the affected "golden data" JSON file and
its associated configuration and regenerate it with:

```shell
$ bazel build //offline-tools:analyze
$ ./bazel-bin/offline-tools/analyze \
    --zip metadata.zip \
    --config path/to/config.textproto \
    --testdata path/to/golden_data.json
```

## Updating Metadata

The `PhoneNumbers` library supports local patching of metadata to mitigate situations
where Libphonenumber's data is incorrect. Patching should be rare, but it can be very
useful to quickly isolate issues.

To see details of the current patched data, see
`offline-tools/src/main/resources/metadata/README.txt`.

If you do download a new
[`metadata.zip`](https://github.com/google/libphonenumber/blob/master/metadata/metadata.zip)
you can use the `DiffTool` to show differences caused by the locally patched data.
The best approach is to run it once with the old `metadata.zip` file, and once
with the new version and then compare the diffs with each other. If the diffs are the
same then any changes in the new metadata are "safe".

```shell
$ bazel build //offline-tools:diff
$ ./bazel-bin/offline-tools/diff --zip metadata_old.zip
$ ./bazel-bin/offline-tools/diff --zip metadata_new.zip
```

> *Note*: In future this tool may support a 3-way diff to make this easier. 