load("//:GenTestRules.bzl", "GenTestRules")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "testing",
    testonly = 1,
    srcs = glob(["src/main/java/**/*.java"]),
    deps = [
        "//client-api:phonenumbers",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_truth_truth",
        "@maven//:org_typemeta_funcj_json",
    ],
)
