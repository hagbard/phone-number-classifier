load("//:GenTestRules.bzl", "GenTestRules")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "metadata_loader",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "//:autoservice",
        "//:autovalue",
        "//client-api:phonenumbers",
        "//metadata-proto:metadata_java_proto",
        "@com_google_protobuf_protobuf_java",
        "@maven//:com_google_code_findbugs_jsr305",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:com_google_guava_guava",
        "@maven//:org_checkerframework_checker_qual",
    ],
)

java_library(
    name = "tests",
    srcs = glob(["src/test/java/**/*.java"]),
    resources = glob(["src/test/resources/*.pb"]),
    deps = [
        ":metadata_loader",
        "//client-api:phonenumbers",
        "//metadata-proto:metadata_java_proto",
        "@com_google_protobuf_protobuf_java",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_i18n_libphonenumber_metadata",
        "@maven//:com_google_truth_extensions_truth_java8_extension",
        "@maven//:com_google_truth_truth",
        "@maven//:junit_junit",
    ],
)

GenTestRules(
    name = "GeneratedTestRules",
    test_files = glob(["src/test/java/**/*Test.java"]),
    deps = [
        ":metadata_loader",
        ":tests",
    ],
)
