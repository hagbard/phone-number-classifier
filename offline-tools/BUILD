load("//:GenTestRules.bzl", "GenTestRules")

package(
    default_visibility = ["//visibility:public"],
)

java_library(
    name = "offline_tools",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/*.pb"]),
    deps = [
        ":config_java_proto",
        "//:autovalue",
        "//metadata-proto:metadata_java_proto",
        "@maven//:com_beust_jcommander",
        "@maven//:com_google_auto_service_auto_service_annotations",
        "@maven//:com_google_auto_value_auto_value_annotations",
        "@maven//:com_google_errorprone_error_prone_annotations",
        "@maven//:com_google_flogger_flogger",
        "@maven//:com_google_flogger_flogger_grpc_context",
        "@maven//:com_google_flogger_flogger_system_backend",
        "@maven//:com_google_guava_guava",
        "@maven//:com_google_i18n_libphonenumber_metadata",
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:com_ibm_icu_icu4j",
        "@maven//:io_grpc_grpc_context",
        "@maven//:org_checkerframework_checker_qual",
        "@maven//:org_typemeta_funcj_core",
        "@maven//:org_typemeta_funcj_json",
    ],
)

proto_library(
    name = "config_proto",
    srcs = ["src/main/protobuf/config.proto"],
)

java_proto_library(
    name = "config_java_proto",
    deps = ["config_proto"],
)

java_binary(
    name = "generate",
    main_class = "net.goui.phonenumber.tools.GenerateMetadata",
    runtime_deps = [":offline_tools"],
)
