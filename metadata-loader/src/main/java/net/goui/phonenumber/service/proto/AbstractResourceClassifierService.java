/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 Copyright (c) 2023, David Beaumont (https://github.com/hagbard).

 This program and the accompanying materials are made available under the terms of the
 Eclipse Public License v. 2.0 available at https://www.eclipse.org/legal/epl-2.0, or the
 Apache License, Version 2.0 available at https://www.apache.org/licenses/LICENSE-2.0.

 SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/

package net.goui.phonenumber.service.proto;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.io.InputStream;
import net.goui.phonenumber.metadata.ClassifierService;
import net.goui.phonenumber.metadata.RawClassifier;
import net.goui.phonenumber.metadata.VersionInfo;
import net.goui.phonenumbers.proto.Metadata.MetadataProto;

/**
 * Helper class to support easy loading of metadata from class resources. A subclass need only
 * implement the constructor to provide version information and a resource path.
 *
 * <pre>{@code
 * public LpnDfaPreciseData() {
 *   super(<version>, "<resource-path>");
 * }
 * }</pre>
 */
public abstract class AbstractResourceClassifierService extends ClassifierService {
  private final String resourceName;

  /**
   * Constructs a {@link ClassifierService} for metadata held in a class resource.
   *
   * @param version a version compatible with the metadata referenced by {@code resourceName}.
   * @param resourceName the name/path of the metadata resource (with respect to this class).
   */
  protected AbstractResourceClassifierService(VersionInfo version, String resourceName) {
    super(version);
    this.resourceName = resourceName;
  }

  protected final RawClassifier load() throws IOException {
    MetadataProto proto;
    try (InputStream is = getClass().getResourceAsStream(resourceName)) {
      proto = MetadataProto.parseFrom(is);
    }
    ProtoBasedNumberClassifier classifier = new ProtoBasedNumberClassifier(proto);
    checkState(
        classifier.getVersion().satisfies(getStatedVersion()),
        "loaded metadata version (%s) does not satisfy the stated version (%s)",
        classifier.getVersion(),
        getStatedVersion());
    return classifier;
  }
}
