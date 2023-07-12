/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.metadata;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import org.checkerframework.checker.nullness.qual.Nullable;

final class ErrorOr<T> {

  public static <T> ErrorOr<T> success(T value) {
    checkNotNull(value, "success must return a non-null value");
    return new ErrorOr<>(value, null);
  }

  public static ErrorOr<RawClassifier> failure(Throwable error) {
    checkNotNull(error, "failure must provide a non-null throwable");
    return new ErrorOr<>(null, error);
  }

  @Nullable private final T value;
  @Nullable private final Throwable error;

  private ErrorOr(@Nullable T value, @Nullable Throwable error) {
    this.value = value;
    this.error = error;
  }

  public T get() {
    checkState(value != null, "cannot obtain a value from an error: %s", this);
    return value;
  }

  public Throwable getError() {
    checkState(error != null, "no error for: %s", this);
    return error;
  }

  public boolean isSuccess() {
    return value != null;
  }

  public boolean isError() {
    return error != null;
  }

  @Override
  public String toString() {
    return isSuccess()
        ? String.format("ErrorOr{value=%s}", value)
        : String.format("ErrorOr{error='%s'}", getError().getMessage());
  }
}
