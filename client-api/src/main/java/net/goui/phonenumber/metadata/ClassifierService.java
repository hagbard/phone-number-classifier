/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.metadata;

public abstract class ClassifierService {
  private final VersionInfo statedVersion;

  protected ClassifierService(VersionInfo version) {
    this.statedVersion = version;
  }

  protected final VersionInfo getStatedVersion() {
    return statedVersion;
  }

  final ErrorOr<RawClassifier> loadChecked() {
    RawClassifier classifier;
    try {
      classifier = load();
    } catch (Exception e) {
      return ErrorOr.failure(e);
    }
    if (classifier.getVersion().satisfies(statedVersion)) {
      return ErrorOr.success(classifier);
    } else {
      return ErrorOr.failure(new IllegalStateException(String.format(
              "Actual data version '%s' is incompatible with stated version '%s'.",
          classifier.getVersion(), statedVersion)));
    }
  }

  /** A subclass should defer loading of metadata until this method is invoked. */
  protected abstract RawClassifier load() throws Exception;
}
