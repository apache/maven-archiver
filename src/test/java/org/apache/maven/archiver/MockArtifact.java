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

import java.io.File;
import java.util.Collection;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.artifact.versioning.VersionRange;

/*
 * TODO move to maven-artifact-test
 */
@SuppressWarnings("deprecation")
public class MockArtifact implements Artifact {
    private String groupId;

    private String artifactId;

    private String version;

    private File file;

    private String scope;

    private String type;

    private String classifier;

    private String baseVersion;

    private ArtifactHandler artifactHandler;

    private boolean snapshot;

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String string) {
        this.version = string;
    }

    public void setSnapshotVersion(String snapshotVersion, String baseVersion) {
        snapshot = true;
        version = snapshotVersion;
        this.baseVersion = baseVersion;
    }

    public String getScope() {
        return scope;
    }

    public String getType() {
        return type;
    }

    public String getClassifier() {
        return classifier;
    }

    public boolean hasClassifier() {
        return classifier != null;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public String getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(String string) {
        this.baseVersion = string;
    }

    public String getId() {
        return null;
    }

    public String getDependencyConflictId() {
        return null;
    }

    public void addMetadata(ArtifactMetadata artifactMetadata) {}

    public ArtifactMetadata getMetadata(Class<?> metadataClass) {
        return null;
    }

    public Collection<ArtifactMetadata> getMetadataList() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setRepository(ArtifactRepository artifactRepository) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public ArtifactRepository getRepository() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void updateVersion(String string, ArtifactRepository artifactRepository) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public String getDownloadUrl() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setDownloadUrl(String string) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public ArtifactFilter getDependencyFilter() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setDependencyFilter(ArtifactFilter artifactFilter) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public ArtifactHandler getArtifactHandler() {
        return artifactHandler;
    }

    public List<String> getDependencyTrail() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setDependencyTrail(List<String> list) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public VersionRange getVersionRange() {
        return VersionRange.createFromVersion(version);
    }

    public void setVersionRange(VersionRange versionRange) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public void selectVersion(String string) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public void setResolved(boolean b) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public boolean isResolved() {
        return false; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setResolvedVersion(String string) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setArtifactHandler(ArtifactHandler artifactHandler) {
        this.artifactHandler = artifactHandler;
    }

    public boolean isRelease() {
        return false; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setRelease(boolean b) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public List<ArtifactVersion> getAvailableVersions() {
        return null; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setAvailableVersions(List<ArtifactVersion> list) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public boolean isOptional() {
        return false; // To change body of implemented methods use File | Settings | File Templates.
    }

    public void setOptional(boolean b) {
        // To change body of implemented methods use File | Settings | File Templates.
    }

    public ArtifactVersion getSelectedVersion() throws OverConstrainedVersionException {
        return VersionRange.createFromVersion(version).getSelectedVersion(this);
    }

    public boolean isSelectedVersionKnown() throws OverConstrainedVersionException {
        return VersionRange.createFromVersion(version).isSelectedVersionKnown(this);
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public void setScope(String string) {
        this.scope = string;
    }

    public int compareTo(Artifact o) {
        return 0; // To change body of implemented methods use File | Settings | File Templates.
    }
}
