# Phone Number Classifier

This is an in-development tool and should not be used by anyone yet.

## To build/install Java code (from `<root>/` directory):

### With Bazel installed:

```shell
bazel build ...
bazel test ...
```

### With Maven installed:

```shell
mvn install -DskipTests
mvn test
```

## To build JavaScript code (from `<root>/javascript/` directory)

### First time only:

```shell
yarn
```

### Subsequent build/test:

```shell
tsc -p .
yarn jest dist/tests/index.test.js
```

Obviously you need Yarn and the TypeScript compiler (tsc) installed first.