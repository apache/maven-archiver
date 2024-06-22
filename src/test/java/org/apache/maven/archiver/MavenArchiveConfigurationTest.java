/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.archiver;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * @author Karl Heinz Marbaise <a href="mailto:khmarbaise@apache.org">khmarbaise@apache.org</a>.
 */
class MavenArchiveConfigurationTest {

    private MavenArchiveConfiguration archive;

    @BeforeEach
    void before() {
        archive = new MavenArchiveConfiguration();
        archive.setManifest(new ManifestConfiguration());
        archive.setForced(false);
        archive.setCompress(false);
    }

    @Test
    void addingSingleEntryShouldBeReturned() {
        archive.addManifestEntry("key1", "value1");
        Map<String, String> manifestEntries = archive.getManifestEntries();
        assertThat(manifestEntries).containsExactly(entry("key1", "value1"));
    }

    @Test
    void addingTwoEntriesShouldBeReturnedInInsertOrder() {
        archive.addManifestEntry("key1", "value1");
        archive.addManifestEntry("key2", "value2");
        Map<String, String> manifestEntries = archive.getManifestEntries();
        assertThat(manifestEntries).containsExactly(entry("key1", "value1"), entry("key2", "value2"));
    }

    @Test
    void addingThreeEntriesShouldBeReturnedInInsertOrder() {
        archive.addManifestEntry("key1", "value1");
        archive.addManifestEntry("key2", "value2");
        archive.addManifestEntry("key3", "value3");
        Map<String, String> manifestEntries = archive.getManifestEntries();
        assertThat(manifestEntries)
                .containsExactly(entry("key1", "value1"), entry("key2", "value2"), entry("key3", "value3"));
    }
}
