/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

/**
 * Internal class for converting strings to/from a specific type. This is used to wrap the string
 * based API of RawClassifier into a strongly typed API in AbstractPhoneNumberClassifier.
 */
// See: https://github.com/microsoft/TypeScript/issues/30611#issuecomment-570773496
export class Converter<V> {

  static fromStringEnum<T extends string, V extends string>(
      enumType: { [key in T]: V }): Converter<V> {
    // String enums just map the keys to the values:
    //   enum MyEnum { Foo = "FOO", Bar = "BAR" }
    // ends up as:
    //   { "Foo": "FOO", "Bar": "BAR" }
    // they do NOT provide a double mapping.
    //
    // We say that users should define the string value as the raw value name, but we can convert
    // from UpperCamelCase as well.
    let keys: string[] = Object.keys(enumType);
    let map: Map<V, string> =
        new Map(keys.map(k => [enumType[k as T] as V, Converter.toRawTypeName(k)]));
    return Converter.fromMap(map);
  }

  static fromNumericEnum<T extends string, V extends number>(
      enumType: { [key in T]: V }): Converter<V> {
    // Numeric enums are "double mapped" objects, so:
    //   enum MyEnum { Foo, Bar }
    // ends up as:
    //   { "Foo": 0, "Bar": 1, "0": "Foo", "1": "Bar" }
    // Note that numeric values are number, but when reversed they are strings.
    let keys: string[] =
        Object.keys(enumType).filter(key => typeof enumType[key as T] === "number");
    let map: Map<V, string> =
        new Map(keys.map(k => [enumType[k as T] as V, Converter.toRawTypeName(k)]));
    return Converter.fromMap(map);
  }

  // Converts "UpperCamelCase" enum names to "UPPER_SNAKE_CASE" number type names.
  private static toRawTypeName(camelCase: string) {
    return camelCase.replace(
        /([a-z]+)([A-Z]|$)/g,
        (_, g1: string, g2: string) => g1.toUpperCase() + (g2 ? "_" + g2 : ""));
  }

  private static fromMap<V>(map: Map<V, string>): Converter<V> {
    let inverse: Map<string, V> = new Map(Array.from(map.keys()).map(k => [map.get(k)!, k]));
    if (map.size != inverse.size) {
      throw new Error(`enum map is not a bijection: ${map}`);
    }
    return new Converter<V>((v: V) => map.get(v), (s: string) => inverse.get(s));
  }

  static identity(): Converter<string> {
    return new Converter<string>((s: string) => s, (s: string) => s);
  }

  constructor(
      private readonly toStringFn: (v: V) => string | undefined,
      private readonly fromStringFn: (s: string) => V | undefined) {}

  /**
   * Tests the converter with a given set of values to ensure it will convert all of them. This is
   * called from code which has been given a converter and needs to ensure it will function
   * correctly. If an unexpected value is encountered, this method will throw an Error (the "raw"
   * string converter will never fail).
   */
  ensureValues(requiredValues: ReadonlySet<string>) {
    // The enum is allowed to have more values than a possible in any given piece of metadata.
    for (let s of requiredValues) {
      this.fromString(s);
    }
  }

  /** Converts the given value to its unique string representation. */
  toString(v: V): string {
    let s = this.toStringFn(v);
    if (s !== undefined) {
      return s;
    }
    throw new Error(`illegal value for converter: ${v}`);
  }

  /** Converts the given string to a value. */
  fromString(s: string): V {
    let v = this.fromStringFn(s);
    if (v !== undefined) {
      return v;
    }
    throw new Error(`unexpected raw value for converter: ${s}`);
  }
}
