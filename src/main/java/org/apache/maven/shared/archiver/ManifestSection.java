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
package org.apache.maven.shared.archiver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ManifestSection class.
 */
public class ManifestSection {

    private String name = null;

    private final Map<String, String> manifestEntries = new LinkedHashMap<>();

    /**
     * Adds a single manifest entry.
     *
     * <p>A {@code null} value is accepted and will produce an empty-string attribute in the manifest.
     * This is intentional: it allows callers to explicitly request an empty manifest entry.
     * Be careful not to pass {@code null} unintentionally (e.g., from an unguarded map lookup),
     * as the resulting empty attribute will not indicate any error.</p>
     *
     * @param key the manifest attribute name
     * @param value the manifest attribute value; {@code null} is treated as an empty string
     */
    public void addManifestEntry(String key, String value) {
        manifestEntries.put(key, value);
    }

    /**
     * Returns the manifest entries map.
     *
     * <p>Values in the returned map may be {@code null}; such entries are treated as empty strings
     * when written to the manifest.</p>
     *
     * @return the entries
     */
    public Map<String, String> getManifestEntries() {
        return manifestEntries;
    }

    /**
     * <p>Getter for the field <code>name</code>.</p>
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * <p>Setter for the field <code>name</code>.</p>
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Adds all entries from the given map as manifest attributes.
     *
     * <p>A {@code null} value in the map is accepted and will produce an empty-string attribute
     * in the manifest. This is intentional: it allows callers to explicitly request an empty
     * manifest entry. Be careful not to pass {@code null} values unintentionally, as they will
     * not indicate any error.</p>
     *
     * @param map the manifest entries to add; map values may be {@code null}, which are treated as empty strings
     */
    public void addManifestEntries(Map<String, String> map) {
        manifestEntries.putAll(map);
    }

    /**
     * <p>isManifestEntriesEmpty.</p>
     *
     * @return true if empty false otherwise
     */
    public boolean isManifestEntriesEmpty() {
        return manifestEntries.isEmpty();
    }
}
