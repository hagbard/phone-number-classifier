load("//:GenTestRules.bzl", "GenTestRules")

java_library(
    name = "examples",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/*.pb"]),
    deps = [
        "//:autoservice",
        "//client-api:phonenumbers",
        "//metadata-loader:metadata_loader",
        "@maven//:com_google_auto_service_auto_service_annotations",
        "@maven//:com_google_guava_guava",
        "@maven//:com_ibm_icu_icu4j",
    ],
)

java_binary(
    name = "simple",
    main_class = "net.goui.phonenumbers.examples.Simple",
    runtime_deps = [":examples"],
)

java_library(
    name = "tests",
    testonly = 1,
    srcs = glob(["src/test/java/**/*.java"]),
    resources = ["src/test/resources/lpn_golden_data.txt"],
    deps = [
        ":examples",
        "//client-api:phonenumbers",
        "//testing",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_extensions_truth_java8_extension",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["src/test/java/**/*Test.java"]),
    deps = [
        ":tests",
    ],
)
