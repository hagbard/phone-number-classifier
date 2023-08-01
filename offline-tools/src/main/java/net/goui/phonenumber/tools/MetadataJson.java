/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

This program and the accompanying materials are made available under the terms of the
Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.tools;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.stream.Collectors.toList;
import static org.typemeta.funcj.json.model.JSAPI.arr;
import static org.typemeta.funcj.json.model.JSAPI.field;
import static org.typemeta.funcj.json.model.JSAPI.num;
import static org.typemeta.funcj.json.model.JSAPI.obj;
import static org.typemeta.funcj.json.model.JSAPI.str;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.goui.phonenumber.proto.Metadata.CallingCodeProto;
import net.goui.phonenumber.proto.Metadata.MatcherDataProto;
import net.goui.phonenumber.proto.Metadata.MatcherFunctionProto;
import net.goui.phonenumber.proto.Metadata.MetadataProto;
import net.goui.phonenumber.proto.Metadata.MetadataProto.VersionInfo;
import net.goui.phonenumber.proto.Metadata.NationalNumberDataProto;
import org.typemeta.funcj.json.algebra.JsonToDoc;
import org.typemeta.funcj.json.model.JSAPI;
import org.typemeta.funcj.json.model.JsArray;
import org.typemeta.funcj.json.model.JsObject;
import org.typemeta.funcj.json.model.JsValue;

/** Convertor from MetadataProto to JSON encoded text. */
final class MetadataJson {
  /** Writes the given metadata proto as UTF-8 encoded JSON data to a specified output stream. */
  public static String toJsonString(MetadataProto metadata) {
    return toJson(metadata).toString();
  }

  /** Formats the given metadata proto as a human readable JSON string (for debugging). */
  public static String toDebugJsonString(MetadataProto metadata) {
    return JsonToDoc.toString(toJson(metadata), 2, 80);
  }

  private static <T> JsArray jsArray(Collection<T> src, Function<T, JsValue> fn) {
    return arr(src.stream().map(fn).collect(toList()));
  }

  @VisibleForTesting
  static JsObject toJson(MetadataProto metadata) {
    List<JsObject.Field> fields = new ArrayList<>();
    fields.add(field("ver", toJson(metadata.getVersion())));
    if (!metadata.getTypeList().isEmpty()) {
      fields.add(field("typ", jsArray(metadata.getTypeList(), JSAPI::num)));
    }
    if (!metadata.getSingleValuedTypeList().isEmpty()) {
      fields.add(field("svm", num(bitMask(metadata.getSingleValuedTypeList()))));
    }
    if (!metadata.getClassifierOnlyTypeList().isEmpty()) {
      fields.add(field("com", num(bitMask(metadata.getClassifierOnlyTypeList()))));
    }
    fields.add(field("ccd", jsArray(metadata.getCallingCodeDataList(), MetadataJson::toJson)));
    fields.add(field("tok", jsArray(metadata.getTokenList(), JSAPI::str)));
    return obj(fields);
  }

  /**
   * Returns an (up to) 32-bit mask for the given values, which must all be in the range [0, 32).
   */
  private static int bitMask(List<Integer> values) {
    checkArgument(
        values.stream().max(Integer::compare).orElse(0) < 32,
        "out of range values for bitmask (must all be < 32): %s",
        values);
    return values.stream().mapToInt(Integer::valueOf).reduce(0, (m, v) -> m | (1 << v));
  }

  @VisibleForTesting
  static JsObject toJson(VersionInfo proto) {
    return obj(
        field("maj", num(proto.getMajorVersion())),
        field("min", num(proto.getMinorVersion())),
        field("uri", str(proto.getDataSchemaUri())),
        field("ver", num(proto.getDataSchemaVersion())));
  }

  @VisibleForTesting
  static JsObject toJson(CallingCodeProto proto) {
    List<JsObject.Field> fields = new ArrayList<>();
    fields.add(field("c", num(proto.getCallingCode())));

    JsArray nationalPrefixes = jsArray(proto.getNationalPrefixList(), JSAPI::num);
    if (!nationalPrefixes.isEmpty()) {
      fields.add(field("p", arrayOrSingleton(nationalPrefixes)));
    }
    String exampleNumber = proto.getExampleNumber();
    if (!exampleNumber.isEmpty()) {
      fields.add(field("e", str(exampleNumber)));
    }
    JsArray ranges = jsArray(proto.getValidityMatcherIndexList(), JSAPI::num);
    if (!ranges.isEmpty()) {
      fields.add(field("r", arrayOrSingleton(ranges)));
    }
    JsArray nnd = jsArray(proto.getNationalNumberDataList(), MetadataJson::toJson);
    if (!nnd.isEmpty()) {
      fields.add(field("n", nnd));
    }
    fields.add(field("m", jsArray(proto.getMatcherDataList(), MetadataJson::toJson)));
    return obj(fields);
  }

  @VisibleForTesting
  static JsObject toJson(NationalNumberDataProto proto) {
    JsObject.Field functions = field("f", jsArray(proto.getMatcherList(), MetadataJson::toJson));
    if (proto.getDefaultValue() == 0) {
      return obj(functions);
    } else {
      return obj(field("v", num(proto.getDefaultValue())), functions);
    }
  }

  @VisibleForTesting
  static JsObject toJson(MatcherFunctionProto proto) {
    JsObject.Field value = field("v", num(proto.getValue()));
    JsArray ranges = jsArray(proto.getMatcherIndexList(), JSAPI::num);
    return ranges.isEmpty() ? obj(value) : obj(value, field("r", arrayOrSingleton(ranges)));
  }

  @VisibleForTesting
  static JsObject toJson(MatcherDataProto proto) {
    return obj(
        field("l", num(proto.getPossibleLengthsMask())),
        field("b", str(toBase64(proto.getMatcherData()))));
  }

  private static JsValue arrayOrSingleton(JsArray array) {
    checkArgument(!array.isEmpty(), "should not attempt to write empty arrays");
    return array.size() > 1 ? array : array.get(0).asNumber();
  }

  private static String toBase64(ByteString bytes) {
    return Base64.getEncoder().withoutPadding().encodeToString(bytes.toByteArray());
  }
}
