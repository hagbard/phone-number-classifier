package(
    default_visibility = ["//visibility:public"],
)

exports_files(["GenTestRules.bzl"])

java_plugin(
    name = "autovalue_plugin",
    processor_class = "com.google.auto.value.processor.AutoValueProcessor",
    deps = [
        "@maven//:com_google_auto_value_auto_value",
    ],
)

java_library(
    name = "autovalue",
    exported_plugins = [
        ":autovalue_plugin",
    ],
    neverlink = 1,
    exports = [
        "@maven//:com_google_auto_value_auto_value",
        "@maven//:com_google_auto_value_auto_value_annotations",
    ],
)

java_plugin(
    name = "autoservice_plugin",
    processor_class = "com.google.auto.service.processor.AutoServiceProcessor",
    deps = [
        "@maven//:com_google_auto_service_auto_service",
    ],
)

java_library(
    name = "autoservice",
    exported_plugins = [
        ":autoservice_plugin",
    ],
    neverlink = 1,
    exports = [
        "@maven//:com_google_auto_service_auto_service",
    ],
)
