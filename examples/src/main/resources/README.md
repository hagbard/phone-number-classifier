# Metadata Configuration Examples

This directory contains example configurations for producing phone number metadata with various
features and levels of simplification.

## Configuration Features and Data Sizes

For size comparison, note that Libphonenumber's data, in its binary form is:

* Protocol buffer: 225kB
* JavaScript:      250kB
  and Libphonenumber provides no mechanism for simplifying its metadata.

### lpn_dfa_precise.textproto:

Metadata providing the same type classifications as libphonenumber, with the same precision.
This supports Libphonenumber types, region information and national/international formatting.

For most cases, this metadata could be used to provide a direct replacement for Libphonenumber.

Data size:

* Protocol buffer: 150kB (93kB zipped)
* Json:            234kB (111kB zipped)

### lpn_dfa_compact.textproto:

Metadata providing the same type classifications as libphonenumber, with a reduced precision.
This supports Libphonenumber types, region information and national/international formatting.

Where precision is not essential and false-positive matches are acceptable, this metadata
can be used to replace Libphonenumber with a significant reduction in data size.

Data size:

* Protocol buffer: 104kB (63.5kB zipped)
* Json:            173kB (73kB zipped)

### simple_dfa_minimal.testproto:

Metadata providing validation, region information and formatting, without the overhead of
Libphonenumber types.

In cases where type information is not important, but region information and formatting are
needed, this metadata provides an excellent tradeoff between data size and functionality.

Data size:

* Protocol buffer: 52kB (32kB zipped)
* Json:            91kB (35kB zipped)

### dfa_smallest.testproto:

A truly minimal metadata configuration which only provides validation information and
applies maximum simplification to number ranges.

In cases where only loose validation is needed, and false-positive matches are not an
issue, this metadata might just be what you need.

Data size:

* Protocol buffer: 8.5kB (5kB zipped)
* Json:            16.5kB (5kB zipped)

## Setup

1. Download metadata.zip from https://github.com/google/libphonenumber/tree/master/metadata.
2. (if using Maven) run `mvn install -DskipTests` from the project root directory.

## Metadata Regeneration

### To (re)generate metadata in this directory

From the project root directory, run either:

```shell
bazel run //offline-tools:generate -- \
    --zip "$PWD/metadata.zip" \
    --config_dir "$PWD/examples/src/main/resources" \
    --config_pattern '.*\.textproto' \
    --out_type PROTO
```

or:

```shell
mvn compile -f offline-tools exec:java \
    "-Dexec.mainClass=net.goui.phonenumber.tools.GenerateMetadata" \
    "-Dexec.args=--zip metadata.zip --config_dir examples/src/main/resources --config_pattern .*\.textproto --out_type PROTO"
```

### To regenerate test data (simple_golden_data):

```shell
bazel run //offline-tools:analyze -- \
    --zip "$PWD/metadata.zip" \
    --config "$PWD/examples/src/main/resources/lpn_dfa_precise.textproto" \
    --testdata "$PWD/examples/src/test/resources/lpn_golden_data.json"

bazel run //offline-tools:analyze -- \
    --zip "$PWD/metadata.zip" \
    --config "$PWD/examples/src/main/resources/simple_dfa_minimal.textproto" \
    --testdata "$PWD/examples/src/test/resources/simple_golden_data.json"
```

or:

```shell
mvn compile -f offline-tools exec:java \
    "-Dexec.mainClass=net.goui.phonenumber.tools.Analyzer" \
    "-Dexec.args=--zip metadata.zip --config examples/src/main/resources/lpn_dfa_precise.textproto --testdata examples/src/test/resources/lpn_golden_data.json"

mvn compile -f offline-tools exec:java \
    "-Dexec.mainClass=net.goui.phonenumber.tools.Analyzer" \
    "-Dexec.args=--zip metadata.zip --config examples/src/main/resources/simple_dfa_minimal.textproto --testdata examples/src/test/resources/simple_golden_data.json"
```

Notes:

1. The use of $PWD in Bazel commands is needed since Bazel runs commands in a different directory,
   so you need to make paths absolute (`$PWD` is a variable of the absolute path to the project
   root).
2. Maven can get confused about protocol buffers, so if you see errors about them, try
   `mvn clean` and `mvn install -DskipTests`.