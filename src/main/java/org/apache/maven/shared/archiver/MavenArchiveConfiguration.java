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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Capture common archive configuration.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 */
// TODO Is this general enough to be in Plexus Archiver?
public class MavenArchiveConfiguration {

    private boolean compress = true;

    private boolean recompressAddedZips = true;

    private boolean addMavenDescriptor = true;

    private Path manifestFile;

    // TODO: Rename this attribute to manifestConfiguration;
    private ManifestConfiguration manifest;

    private Map<String, String> manifestEntries = new LinkedHashMap<>();

    private List<ManifestSection> manifestSections = new LinkedList<>();

    /**
     * @since 2.2
     */
    private boolean forced = true;

    /**
     * @since 2.3
     */
    private Path pomPropertiesFile;

    /**
     * <p>isCompress.</p>
     *
     * @return {@link #compress}
     */
    public boolean isCompress() {
        return compress;
    }

    /**
     * <p>isRecompressAddedZips.</p>
     *
     * @return {@link #recompressAddedZips}
     */
    public boolean isRecompressAddedZips() {
        return recompressAddedZips;
    }

    /**
     * <p>Setter for the field <code>recompressAddedZips</code>.</p>
     *
     * @param recompressAddedZips {@link #recompressAddedZips}
     */
    public void setRecompressAddedZips(boolean recompressAddedZips) {
        this.recompressAddedZips = recompressAddedZips;
    }

    /**
     * <p>isAddMavenDescriptor.</p>
     *
     * @return {@link #addMavenDescriptor}
     */
    public boolean isAddMavenDescriptor() {
        return addMavenDescriptor;
    }

    /**
     * <p>Getter for the field <code>manifestFile</code>.</p>
     *
     * @return {@link #manifestFile}
     */
    public Path getManifestFile() {
        return manifestFile;
    }

    /**
     * <p>Getter for the field <code>manifest</code>.</p>
     *
     * @return {@link #manifest}
     */
    // TODO: Change the name of this method into getManifestConfiguration()
    public ManifestConfiguration getManifest() {
        if (manifest == null) {
            manifest = new ManifestConfiguration();
        }
        return manifest;
    }

    /**
     * <p>Setter for the field <code>compress</code>.</p>
     *
     * @param compress set compress to true/false
     */
    public void setCompress(boolean compress) {
        this.compress = compress;
    }

    /**
     * <p>Setter for the field <code>addMavenDescriptor</code>.</p>
     *
     * @param addMavenDescriptor activate to add maven descriptor or not
     */
    public void setAddMavenDescriptor(boolean addMavenDescriptor) {
        this.addMavenDescriptor = addMavenDescriptor;
    }

    /**
     * <p>Setter for the field <code>manifestFile</code>.</p>
     *
     * @param manifestFile the manifest file
     */
    public void setManifestFile(Path manifestFile) {
        this.manifestFile = manifestFile;
    }

    /**
     * <p>Setter for the field <code>manifest</code>.</p>
     *
     * @param manifest {@link ManifestConfiguration}
     */
    public void setManifest(ManifestConfiguration manifest) {
        this.manifest = manifest;
    }

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
     * @return are there entries true yes false otherwise
     */
    public boolean isManifestEntriesEmpty() {
        return manifestEntries.isEmpty();
    }

    /**
     * Returns the manifest entries map.
     *
     * <p>Values in the returned map may be {@code null}; such entries are treated as empty strings
     * when written to the manifest.</p>
     *
     * @return {@link #manifestEntries}
     */
    public Map<String, String> getManifestEntries() {
        return manifestEntries;
    }

    /**
     * Sets the manifest entries map, replacing any previously configured entries.
     *
     * <p>The map may contain {@code null} values. A {@code null} value is treated as an empty string
     * when the manifest is written, producing an empty attribute for that key. This is intentional
     * behaviour — but be careful not to pass {@code null} values unintentionally (e.g., from an
     * unguarded map lookup), as they will be silently accepted and produce an empty manifest entry
     * with no error.</p>
     *
     * @param manifestEntries the manifest entries; map values may be {@code null}
     */
    public void setManifestEntries(Map<String, String> manifestEntries) {
        this.manifestEntries = manifestEntries;
    }

    /**
     * <p>addManifestSection.</p>
     *
     * @param section {@link ManifestSection}
     */
    public void addManifestSection(ManifestSection section) {
        manifestSections.add(section);
    }

    /**
     * <p>addManifestSections.</p>
     *
     * @param list added list of {@link ManifestSection}
     */
    public void addManifestSections(List<ManifestSection> list) {
        manifestSections.addAll(list);
    }

    /**
     * <p>isManifestSectionsEmpty.</p>
     *
     * @return if manifestSections is empty or not
     */
    public boolean isManifestSectionsEmpty() {
        return manifestSections.isEmpty();
    }

    /**
     * <p>Getter for the field <code>manifestSections</code>.</p>
     *
     * @return {@link #manifestSections}
     */
    public List<ManifestSection> getManifestSections() {
        return manifestSections;
    }

    /**
     * <p>Setter for the field <code>manifestSections</code>.</p>
     *
     * @param manifestSections set The list of {@link ManifestSection}
     */
    public void setManifestSections(List<ManifestSection> manifestSections) {
        this.manifestSections = manifestSections;
    }

    /**
     * <p>
     * Returns, whether recreating the archive is forced (default). Setting this option to false means, that the
     * archiver should compare the timestamps of included files with the timestamp of the target archive and rebuild the
     * archive only, if the latter timestamp precedes the former timestamps. Checking for timestamps will typically
     * offer a performance gain (in particular, if the following steps in a build can be suppressed, if an archive isn't
     * recrated) on the cost that you get inaccurate results from time to time. In particular, removal of source files
     * won't be detected.
     * </p>
     * <p>
     * An archiver doesn't necessarily support checks for uptodate. If so, setting this option to true will simply be
     * ignored.
     * </p>
     *
     * @return true, if the target archive should always be created; false otherwise
     * @see #setForced(boolean)
     */
    public boolean isForced() {
        return forced;
    }

    /**
     * <p>
     * Sets, whether recreating the archive is forced (default). Setting this option to false means, that the archiver
     * should compare the timestamps of included files with the timestamp of the target archive and rebuild the archive
     * only, if the latter timestamp precedes the former timestamps. Checking for timestamps will typically offer a
     * performance gain (in particular, if the following steps in a build can be suppressed, if an archive isn't
     * recrated) on the cost that you get inaccurate results from time to time. In particular, removal of source files
     * won't be detected.
     * </p>
     * <p>
     * An archiver doesn't necessarily support checks for uptodate. If so, setting this option to true will simply be
     * ignored.
     * </p>
     *
     * @param forced true, if the target archive should always be created; false otherwise
     * @see #isForced()
     */
    public void setForced(boolean forced) {
        this.forced = forced;
    }

    /**
     * Returns the location of the "pom.properties" file. May be null, in which case a default value is choosen.
     *
     * @return "pom.properties" location or null
     */
    public Path getPomPropertiesFile() {
        return pomPropertiesFile;
    }

    /**
     * Sets the location of the "pom.properties" file. May be null, in which case a default value is choosen.
     *
     * @param pomPropertiesFile "pom.properties" location or null
     */
    public void setPomPropertiesFile(Path pomPropertiesFile) {
        this.pomPropertiesFile = pomPropertiesFile;
    }
}
