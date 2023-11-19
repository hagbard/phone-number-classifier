# Phone Numbers

**Phone Numbers** is a fast, optimized library for validating and classifying national and international phone numbers in Java and JavaScript.

Based on the data provided by Google's **libphonenumber** library, the provided tools let you generate metadata tuned to your needs. Create small data with lower accuracy for mobile applications, or high precision data for server side code. Easily exclude metadata you don't need and even create new classifications to suit your business logic.

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
