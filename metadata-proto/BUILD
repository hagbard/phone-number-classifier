load("@rules_proto//proto:defs.bzl", "proto_library")

package(
    default_visibility = ["//visibility:public"],
)

proto_library(
    name = "metadata_proto",
    srcs = ["src/main/protobuf/metadata.proto"],
)

java_proto_library(
    name = "metadata_java_proto",
    deps = ["metadata_proto"],
)
