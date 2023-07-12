/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.metadata;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.function.Predicate;

public final class ClassifierLoader {
  public static ImmutableList<RawClassifier> loadMatchedVersions(
      Predicate<VersionInfo> predicate, Comparator<VersionInfo> ordering) {
    ImmutableList<ErrorOr<RawClassifier>> loaded =
        ServiceLoader.load(ClassifierService.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(s -> predicate.test(s.getStatedVersion()))
            .parallel()
            .map(ClassifierService::loadChecked)
            .collect(toImmutableList());

    if (loaded.stream().anyMatch(ErrorOr::isError)) {
      RuntimeException e = new RuntimeException("Error(s) loading classifier metadata.");
      loaded.stream().filter(ErrorOr::isError).map(ErrorOr::getError).forEach(e::addSuppressed);
      throw e;
    }

    return loaded.stream()
        .filter(ErrorOr::isSuccess)
        .map(ErrorOr::get)
        .sorted(Comparator.comparing(RawClassifier::getVersion, ordering))
        .collect(toImmutableList());
  }
}
