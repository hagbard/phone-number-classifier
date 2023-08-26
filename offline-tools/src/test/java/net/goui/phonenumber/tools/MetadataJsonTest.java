/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;
import static net.goui.phonenumber.proto.Metadata.MetadataProto.MatcherType.DIGIT_SEQUENCE_MATCHER;
import static net.goui.phonenumber.tools.MetadataJson.toJson;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import net.goui.phonenumber.proto.Metadata.CallingCodeProto;
import net.goui.phonenumber.proto.Metadata.MatcherDataProto;
import net.goui.phonenumber.proto.Metadata.MatcherFunctionProto;
import net.goui.phonenumber.proto.Metadata.MetadataProto;
import net.goui.phonenumber.proto.Metadata.MetadataProto.VersionInfo;
import net.goui.phonenumber.proto.Metadata.NationalNumberDataProto;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.typemeta.funcj.json.model.JsValue;

@RunWith(JUnit4.class)
public class MetadataJsonTest {
  @Test
  public void testMetadata() {
    NationalNumberDataProto nnd = // {"v":3,"f":[{"v":4,"r":[0,1]}]}
        NationalNumberDataProto.newBuilder()
            .setDefaultValue(3)
            .addMatcher(matcherFunction(4, 0, 1))
            .build();

    CallingCodeProto ccd = // {"c":44,"n":[<NND>],"m":[<MATCHER>]}
        CallingCodeProto.newBuilder()
            .setCallingCode(44)
            .addValidityMatcherIndex(1)
            .addNationalNumberData(nnd)
            .addMatcherData(matcher(32, bytes(0x01, 0x23, 0x45))) // {"l":32,"b":"\u0123\u45ff"}
            .addMatcherData(
                matcher(96, bytes(0x67, 0x89, 0xab, 0xcd))) // {"l":96,"b":"\u6789\uabcd"}
            .build();

    MetadataProto metadata =
        MetadataProto.newBuilder()
            .setVersion(version("some/uri", 1))
            .addAllType(asList(1, 2))
            .addSingleValuedType(0)
            .addClassifierOnlyType(1)
            .addMatcherType(DIGIT_SEQUENCE_MATCHER)
            .addCallingCodeData(ccd)
            .addAllToken(asList("", "type1", "type2", "val1", "val2"))
            .build();

    // This is NOT testing what we emit, but the other tests show that adequately and this is
    // vaguely human readable.
    assertThat(MetadataJson.toDebugJsonString(metadata))
        .isEqualTo(
            String.join(
                "\n",
                "{",
                // 3-letter labels appear once in the entire data file, so not a space issue.
                "  \"ver\": {\"maj\": 1, \"min\": 0, \"uri\": \"some/uri\", \"ver\": 1},",
                // Indices into the token list.
                "  \"typ\": [1, 2],",
                // NOT token indices, but rather a bit mask of the type indices (1<<0) & (1<<1).
                "  \"svm\": 1,",
                "  \"com\": 2,",
                // There are >200 country calling codes, so this data must be compact.
                "  \"ccd\": [",
                "    {",
                "      \"c\": 44,", // calling code
                "      \"v\": 1,",
                "      \"n\": [{\"v\": 3, \"f\": [{\"v\": 4, \"r\": [0, 1]}]}],",
                "      \"m\": [{\"l\": 32, \"b\": \"ASNF\"}, {\"l\": 96, \"b\": \"Z4mrzQ\"}],",
                "      \"p\": {\"r\": 0}",
                "    }",
                "  ],",
                // The token list allows sharing of strings between country calling code data.
                "  \"tok\": [\"\", \"type1\", \"type2\", \"val1\", \"val2\"]",
                "}"));
  }

  @Test
  public void testVersion() {
    VersionInfo version =
        VersionInfo.newBuilder()
            .setMajorVersion(1)
            .setMinorVersion(2)
            .setDataSchemaUri("any \"value\"")
            .setDataSchemaVersion(3)
            .build();
    assertThat(toJson(version).toString())
        .isEqualTo("{\"maj\":1,\"min\":2,\"uri\":\"any \\\"value\\\"\",\"ver\":3}");
  }

  @Test
  public void testCallingCodeData() {
    NationalNumberDataProto nnd = // {"v":99,"f":[{"v":12}]}
        NationalNumberDataProto.newBuilder()
            .setDefaultValue(99)
            .addMatcher(matcherFunction(12))
            .build();
    MatcherDataProto matcher = matcher(32, bytes(0x01, 0x23, 0x45)); // {"l":32,"b":"\u0123\u45ff"}
    CallingCodeProto ccd =
        CallingCodeProto.newBuilder()
            .setCallingCode(44)
            .addValidityMatcherIndex(1)
            .addNationalNumberData(nnd)
            .addMatcherData(matcher)
            .build();

    assertThat(toJson(ccd).toString())
        .isEqualTo(
            "{\"c\":44,\"v\":1,\"n\":[{\"v\":99,\"f\":[{\"v\":12}]}],\"m\":[{\"l\":32,\"b\":\"ASNF\"}],\"p\":{\"r\":0}}");
  }

  @Test
  public void testNationalNumberData() {
    NationalNumberDataProto.Builder nnd =
        NationalNumberDataProto.newBuilder()
            .addMatcher(matcherFunction(12, 1, 2)) // {"v":12,"r":[1,2]}
            .addMatcher(matcherFunction(34)); // {"v":34}]}

    NationalNumberDataProto withoutDefaultValue = nnd.build();
    assertJson(toJson(withoutDefaultValue), "{\"f\":[{\"v\":12,\"r\":[1,2]},{\"v\":34}]}");

    NationalNumberDataProto withDefaultValue = nnd.setDefaultValue(99).build();
    assertJson(toJson(withDefaultValue), "{\"v\":99,\"f\":[{\"v\":12,\"r\":[1,2]},{\"v\":34}]}");
  }

  @Test
  public void testMatcherFunction() {
    MatcherFunctionProto.Builder function = MatcherFunctionProto.newBuilder().setValue(123);

    // DO NOT emit emit array when no matcher indices are added (client assumes [0] in this case).
    MatcherFunctionProto withoutRangeIndices = function.build();
    assertJson(toJson(withoutRangeIndices), "{\"v\":123}");

    MatcherFunctionProto withRangeIndices = function.addAllMatcherIndex(asList(4, 5, 6)).build();
    assertJson(toJson(withRangeIndices), "{\"v\":123,\"r\":[4,5,6]}");
  }

  @Test
  public void testMatcherData() {
    MatcherDataProto matcher = matcher(123, bytes(0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF));

    assertJson(toJson(matcher), "{\"l\":123,\"b\":\"ASNFZ4mrze8\"}");
  }

  private static void assertJson(JsValue json, String expected) {
    assertThat(json.toString()).isEqualTo(expected);
  }

  private static VersionInfo version(String uri, int version) {
    return VersionInfo.newBuilder()
        .setMajorVersion(1)
        .setMinorVersion(0)
        .setDataSchemaUri(uri)
        .setDataSchemaVersion(version)
        .build();
  }

  private static MatcherDataProto matcher(int value, ByteString bytes) {
    return MatcherDataProto.newBuilder()
        .setPossibleLengthsMask(value)
        .setMatcherData(bytes)
        .build();
  }

  private static MatcherFunctionProto matcherFunction(int value, int... matcherIndices) {
    return MatcherFunctionProto.newBuilder()
        .setValue(value)
        .addAllMatcherIndex(Ints.asList(matcherIndices))
        .build();
  }

  private static ByteString bytes(int... values) {
    return ByteString.copyFrom(Bytes.toArray(Ints.asList(values)));
  }
}
