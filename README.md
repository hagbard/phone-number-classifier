# Phone Number Classifier

This is an in-development tool and should not be used by anyone yet.

## Download a version of the Libphonenumber metadata ZIP file

Latest version is available from:
https://github.com/google/libphonenumber/blob/master/metadata/metadata.zip

If you intend to regenerate test data, you need the version of the metadata associated with the version of `libphonenumber` you are using (this is defined in the `offline-tools/pom.xml` Maven dependencies or the `WORKSPACE.bazel` file. Without the matching version of the metadata file, generated test data (using `libphonenumber`) may not match the classifier metadata derived from the `metadata.zip`.

Place this file in the project root directory.

## Build/install Java code

From `<root>` directory.

### With Bazel installed:

```shell
$ bazel build ...
$ bazel test ...
```

### With Maven installed:

```shell
$ mvn package
$ mvn test
```

## Build JavaScript code

From `<root>/javascript` directory.

### First time only:

```shell
$ yarn
```

### Subsequent build/test:

```shell
$ tsc -p .
$ yarn jest dist/tests/index.test.js
```

Obviously you need Yarn and the TypeScript compiler (tsc) installed first.

## Rebuilding client metadata and test data

This is where it's important that the `metadata.zip` version matches the `libphonenumber` version, otherwise you are likely to see spurious (false-negative) test failures.

From `<root>/` directory.

### Rebuild example metadata and test data:

```shell
$ bazel run //offline-tools:generate \
    --zip ${PWD}/metadata.zip \
    --config_dir ${PWD}/examples/src/main/resources \
    --config_pattern '.*\.textproto' \
    --out_type PROTO
$ bazel run //offline-tools:analyze \
    --zip ${PWD}/metadata.zip \
    --config ${PWD}/examples/src/main/resources/lpn_dfa_compact.textproto \
    --testdata ${PWD}/examples/src/test/resources/lpn_golden_data.json
```

### Rebuild JavaScript metadata and test data:

```shell
$ bazel run //offline-tools:generate \
    --zip ${PWD}/metadata.zip \
    --config_dir ${PWD}/javascript/tests \
    --config_pattern '.*\.textproto' \
    --out_type JSON
$ bazel run //offline-tools:analyze \
    --zip ${PWD}/metadata.zip \
    --config ${PWD}/examples/javascript/tests/lpn_dfa_compact.textproto \
    --testdata ${PWD}/examples/javascript/tests/lpn_golden_data.json
```
