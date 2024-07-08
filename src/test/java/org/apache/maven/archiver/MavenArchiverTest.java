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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Organization;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.apache.maven.archiver.MavenArchiver.parseBuildOutputTimestamp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MavenArchiverTest {
    static class ArtifactComparator implements Comparator<Artifact> {
        public int compare(Artifact o1, Artifact o2) {
            return o1.getArtifactId().compareTo(o2.getArtifactId());
        }

        public boolean equals(Object o) {
            return false;
        }
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(
            strings = {
                ".",
                "dash-is-invalid",
                "plus+is+invalid",
                "colon:is:invalid",
                "new.class",
                "123.at.start.is.invalid",
                "digit.at.123start.is.invalid"
            })
    void testInvalidModuleNames(String value) {
        assertThat(MavenArchiver.isValidModuleName(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a.b", "a_b", "trailing0.digits123.are456.ok789", "UTF8.chars.are.okay.äëïöüẍ", "ℤ€ℕ"})
    void testValidModuleNames(String value) {
        assertThat(MavenArchiver.isValidModuleName(value)).isTrue();
    }

    @Test
    void testGetManifestExtensionList() throws Exception {
        MavenArchiver archiver = new MavenArchiver();

        MavenSession session = getDummySession();

        Model model = new Model();
        model.setArtifactId("dummy");

        MavenProject project = new MavenProject(model);
        // we need to sort the artifacts for test purposes
        Set<Artifact> artifacts = new TreeSet<>(new ArtifactComparator());
        project.setArtifacts(artifacts);

        // there should be a mock or a setter for this field.
        ManifestConfiguration config = new ManifestConfiguration() {
            public boolean isAddExtensions() {
                return true;
            }
        };

        Manifest manifest = archiver.getManifest(session, project, config);

        assertThat(manifest.getMainAttributes()).isNotNull();

        assertThat(manifest.getMainAttributes().getValue("Extension-List")).isNull();

        Artifact artifact1 = mock(Artifact.class);
        when(artifact1.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact1.getArtifactId()).thenReturn("dummy1");
        when(artifact1.getVersion()).thenReturn("1.0");
        when(artifact1.getType()).thenReturn("dll");
        when(artifact1.getScope()).thenReturn("compile");

        artifacts.add(artifact1);

        manifest = archiver.getManifest(session, project, config);

        assertThat(manifest.getMainAttributes().getValue("Extension-List")).isNull();

        Artifact artifact2 = mock(Artifact.class);
        when(artifact2.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact2.getArtifactId()).thenReturn("dummy2");
        when(artifact2.getVersion()).thenReturn("1.0");
        when(artifact2.getType()).thenReturn("jar");
        when(artifact2.getScope()).thenReturn("compile");

        artifacts.add(artifact2);

        manifest = archiver.getManifest(session, project, config);

        assertThat(manifest.getMainAttributes().getValue("Extension-List")).isEqualTo("dummy2");

        Artifact artifact3 = mock(Artifact.class);
        when(artifact3.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact3.getArtifactId()).thenReturn("dummy3");
        when(artifact3.getVersion()).thenReturn("1.0");
        when(artifact3.getType()).thenReturn("jar");
        when(artifact3.getScope()).thenReturn("test");

        artifacts.add(artifact3);

        manifest = archiver.getManifest(session, project, config);

        assertThat(manifest.getMainAttributes().getValue("Extension-List")).isEqualTo("dummy2");

        Artifact artifact4 = mock(Artifact.class);
        when(artifact4.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact4.getArtifactId()).thenReturn("dummy4");
        when(artifact4.getVersion()).thenReturn("1.0");
        when(artifact4.getType()).thenReturn("jar");
        when(artifact4.getScope()).thenReturn("compile");

        artifacts.add(artifact4);

        manifest = archiver.getManifest(session, project, config);

        assertThat(manifest.getMainAttributes().getValue("Extension-List")).isEqualTo("dummy2 dummy4");
    }

    @Test
    void testMultiClassPath() throws Exception {
        final File tempFile = File.createTempFile("maven-archiver-test-", ".jar");

        try {
            MavenArchiver archiver = new MavenArchiver();

            MavenSession session = getDummySession();

            Model model = new Model();
            model.setArtifactId("dummy");

            MavenProject project = new MavenProject(model) {
                public List<String> getRuntimeClasspathElements() {
                    return Collections.singletonList(tempFile.getAbsolutePath());
                }
            };

            // there should be a mock or a setter for this field.
            ManifestConfiguration manifestConfig = new ManifestConfiguration() {
                public boolean isAddClasspath() {
                    return true;
                }
            };

            MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
            archiveConfiguration.setManifest(manifestConfig);
            archiveConfiguration.addManifestEntry("Class-Path", "help/");

            Manifest manifest = archiver.getManifest(session, project, archiveConfiguration);
            String classPath = manifest.getMainAttributes().getValue("Class-Path");
            assertThat(classPath)
                    .as("User specified Class-Path entry was not prepended to manifest")
                    .startsWith("help/")
                    .as("Class-Path generated by addClasspath was not appended to manifest")
                    .endsWith(tempFile.getName());
        } finally {
            // noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    @Test
    void testRecreation() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(false);

        Path directory = Paths.get("target", "maven-archiver");
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        long history = System.currentTimeMillis() - 60000L;
        jarFile.setLastModified(history);
        long time = jarFile.lastModified();

        try (Stream<Path> paths = Files.walk(directory)) {
            FileTime fileTime = FileTime.fromMillis(time);
            paths.forEach(path -> assertThatCode(() -> Files.setLastModifiedTime(path, fileTime))
                    .doesNotThrowAnyException());
        }

        archiver.createArchive(session, project, config);

        config.setForced(true);
        archiver.createArchive(session, project, config);
        // I'm not sure if it could only be greater than time or if it is sufficient to be greater or equal..
        assertThat(jarFile.lastModified()).isGreaterThanOrEqualTo(time);
    }

    @Test
    void testNotGenerateImplementationVersionForMANIFESTMF() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(false);
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        try (JarFile jar = new JarFile(jarFile)) {
            assertThat(jar.getManifest().getMainAttributes())
                    .doesNotContainKey(Attributes.Name.IMPLEMENTATION_VERSION); // "Implementation-Version"
        }
    }

    @Test
    void testGenerateImplementationVersionForMANIFESTMF() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setDescription("foo " + ls + " bar ");
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getDescription());
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        try (JarFile jar = new JarFile(jarFile)) {
            assertThat(jar.getManifest().getMainAttributes())
                    .containsKey(Attributes.Name.IMPLEMENTATION_VERSION)
                    .containsEntry(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.1");
        }
    }

    private MavenArchiver getMavenArchiver(JarArchiver jarArchiver) {
        MavenArchiver archiver = new MavenArchiver();
        archiver.setArchiver(jarArchiver);
        archiver.setOutputFile(jarArchiver.getDestFile());
        return archiver;
    }

    @Test
    void testDashesInClassPath_MSHARED_134() throws Exception {
        File jarFile = new File("target/test/dummyWithDashes.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        Set<Artifact> artifacts =
                getArtifacts(getMockArtifact1(), getArtifactWithDot(), getMockArtifact2(), getMockArtifact3());

        project.setArtifacts(artifacts);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(false);

        final ManifestConfiguration mftConfig = config.getManifest();
        mftConfig.setMainClass("org.apache.maven.Foo");
        mftConfig.setAddClasspath(true);
        mftConfig.setAddExtensions(true);
        mftConfig.setClasspathPrefix("./lib/");

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
    }

    @Test
    void testDashesInClassPath_MSHARED_182() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);
        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        Set<Artifact> artifacts =
                getArtifacts(getMockArtifact1(), getArtifactWithDot(), getMockArtifact2(), getMockArtifact3());

        project.setArtifacts(artifacts);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(false);

        final ManifestConfiguration mftConfig = config.getManifest();
        mftConfig.setMainClass("org.apache.maven.Foo");
        mftConfig.setAddClasspath(true);
        mftConfig.setAddExtensions(true);
        mftConfig.setClasspathPrefix("./lib/");
        config.addManifestEntry("Key1", "value1");
        config.addManifestEntry("key2", "value2");

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        final Attributes mainAttributes = getJarFileManifest(jarFile).getMainAttributes();
        assertThat(mainAttributes.getValue("Key1")).isEqualTo("value1");
        assertThat(mainAttributes.getValue("Key2")).isEqualTo("value2");
    }

    @Test
    void testCarriageReturnInManifestEntry() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setDescription("foo " + ls + " bar ");
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getDescription());
        // config.addManifestEntry( "EntryWithTab", " foo tab " + ( '\u0009' ) + ( '\u0009' ) // + " bar tab" + ( //
        // '\u0009' // ) );
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final Manifest manifest = getJarFileManifest(jarFile);
        Attributes attributes = manifest.getMainAttributes();
        assertThat(project.getDescription().indexOf(ls)).isGreaterThan(0);
        Attributes.Name description = new Attributes.Name("Description");
        String value = attributes.getValue(description);
        assertThat(value).isNotNull();
        assertThat(value.indexOf(ls)).isLessThanOrEqualTo(0);
    }

    @Test
    void testDeprecatedCreateArchiveAPI() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenProject project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);

        MavenSession session = getDummySessionWithoutMavenVersion();
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Attributes manifest = getJarFileManifest(jarFile).getMainAttributes();

        // no version number
        assertThat(manifest)
                .containsEntry(new Attributes.Name("Created-By"), "Maven Archiver")
                .containsEntry(Attributes.Name.SPECIFICATION_TITLE, "archiver test")
                .containsEntry(Attributes.Name.SPECIFICATION_VERSION, "0.1")
                .containsEntry(Attributes.Name.SPECIFICATION_VENDOR, "Apache")
                .containsEntry(Attributes.Name.IMPLEMENTATION_TITLE, "archiver test")
                .containsEntry(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.1")
                .containsEntry(Attributes.Name.IMPLEMENTATION_VENDOR, "Apache")
                .containsEntry(new Attributes.Name("Build-Jdk-Spec"), System.getProperty("java.specification.version"));
    }

    @Test
    void testMinimalManifestEntries() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultEntries(false);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final Manifest jarFileManifest = getJarFileManifest(jarFile);
        Attributes manifest = jarFileManifest.getMainAttributes();

        assertThat(manifest).hasSize(1).containsOnlyKeys(new Attributes.Name("Manifest-Version"));
        assertThat(manifest.getValue("Manifest-Version")).isEqualTo("1.0");
    }

    @Test
    void testManifestEntries() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setAddBuildEnvironmentEntries(true);

        Map<String, String> manifestEntries = new HashMap<>();
        manifestEntries.put("foo", "bar");
        manifestEntries.put("first-name", "olivier");
        manifestEntries.put("Automatic-Module-Name", "org.apache.maven.archiver");
        manifestEntries.put("keyWithEmptyValue", null);
        config.setManifestEntries(manifestEntries);

        ManifestSection manifestSection = new ManifestSection();
        manifestSection.setName("UserSection");
        manifestSection.addManifestEntry("key", "value");
        List<ManifestSection> manifestSections = new ArrayList<>();
        manifestSections.add(manifestSection);
        config.setManifestSections(manifestSections);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final Manifest jarFileManifest = getJarFileManifest(jarFile);
        Attributes manifest = jarFileManifest.getMainAttributes();

        // no version number
        assertThat(manifest)
                .containsEntry(new Attributes.Name("Created-By"), "Maven Archiver")
                .containsEntry(
                        new Attributes.Name("Build-Tool"),
                        session.getSystemProperties().get("maven.build.version"))
                .containsEntry(
                        new Attributes.Name("Build-Jdk"),
                        String.format("%s (%s)", System.getProperty("java.version"), System.getProperty("java.vendor")))
                .containsEntry(
                        new Attributes.Name("Build-Os"),
                        String.format(
                                "%s (%s; %s)",
                                System.getProperty("os.name"),
                                System.getProperty("os.version"),
                                System.getProperty("os.arch")))
                .containsEntry(Attributes.Name.SPECIFICATION_TITLE, "archiver test")
                .containsEntry(Attributes.Name.SPECIFICATION_VERSION, "0.1")
                .containsEntry(Attributes.Name.SPECIFICATION_VENDOR, "Apache")
                .containsEntry(Attributes.Name.IMPLEMENTATION_TITLE, "archiver test")
                .containsEntry(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.1")
                .containsEntry(Attributes.Name.IMPLEMENTATION_VENDOR, "Apache")
                .containsEntry(Attributes.Name.MAIN_CLASS, "org.apache.maven.Foo")
                .containsEntry(new Attributes.Name("foo"), "bar")
                .containsEntry(new Attributes.Name("first-name"), "olivier");

        assertThat(manifest.getValue("Automatic-Module-Name")).isEqualTo("org.apache.maven.archiver");

        assertThat(manifest)
                .containsEntry(new Attributes.Name("Build-Jdk-Spec"), System.getProperty("java.specification.version"));

        assertThat(manifest.getValue(new Attributes.Name("keyWithEmptyValue"))).isEmpty();
        assertThat(manifest).containsKey(new Attributes.Name("keyWithEmptyValue"));

        manifest = jarFileManifest.getAttributes("UserSection");

        assertThat(manifest).containsEntry(new Attributes.Name("key"), "value");
    }

    @Test
    void testManifestWithInvalidAutomaticModuleNameThrowsOnCreateArchive() throws Exception {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();

        Map<String, String> manifestEntries = new HashMap<>();
        manifestEntries.put("Automatic-Module-Name", "123.in-valid.new.name");
        config.setManifestEntries(manifestEntries);

        try {
            archiver.createArchive(session, project, config);
        } catch (ManifestException e) {
            assertThat(e.getMessage()).isEqualTo("Invalid automatic module name: '123.in-valid.new.name'");
        }
    }

    /*
     * Test to make sure that manifest sections are present in the manifest prior to the archive has been created.
     */
    @Test
    void testManifestSections() throws Exception {
        MavenArchiver archiver = new MavenArchiver();

        MavenSession session = getDummySession();

        MavenProject project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();

        ManifestSection manifestSection = new ManifestSection();
        manifestSection.setName("SectionOne");
        manifestSection.addManifestEntry("key", "value");
        List<ManifestSection> manifestSections = new ArrayList<>();
        manifestSections.add(manifestSection);
        config.setManifestSections(manifestSections);

        Manifest manifest = archiver.getManifest(session, project, config);

        Attributes section = manifest.getAttributes("SectionOne");
        assertThat(section)
                .as("The section is not present in the manifest as it should be.")
                .isNotNull();

        String attribute = section.getValue("key");
        assertThat(attribute)
                .as("The attribute we are looking for is not present in the section.")
                .isNotNull()
                .as("The value of the attribute is wrong.")
                .isEqualTo("value");
    }

    @Test
    void testDefaultClassPathValue() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest()
                .setCustomClasspathLayout(
                        "${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        final Manifest manifest = getJarFileManifest(jarFile);
        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("dummy1-1.0.jar", "dummy2-1.5.jar", "dummy3-2.0-classifier.jar");
    }

    private void deleteAndAssertNotPresent(File jarFile) {
        jarFile.delete();
        assertThat(jarFile).doesNotExist();
    }

    @Test
    void testDefaultClassPathValue_WithSnapshot() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProjectWithSnapshot();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest()
                .setCustomClasspathLayout(
                        "${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final Manifest manifest = getJarFileManifest(jarFile);
        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("dummy1-1.1-20081022.112233-1.jar", "dummy2-1.5.jar", "dummy3-2.0-classifier.jar");
    }

    @Test
    void testMavenRepoClassPathValue() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setUseUniqueVersions(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY);
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveWithSimpleClassPathLayoutWhileSettingSimpleLayoutExplicit() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-explicit-simple.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_SIMPLE);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);

        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomerLayoutSimple() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-custom-layout-simple.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.SIMPLE_LAYOUT);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);

        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutSimpleNonUnique() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-custom-layout-simple-non-unique.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.SIMPLE_LAYOUT_NONUNIQUE);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.1.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);

        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("lib/dummy1-1.0.1.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutRepository() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-custom-layout-repo.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.REPOSITORY_LAYOUT);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);

        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutRepositoryNonUnique() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-custom-layout-repo-non-unique.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.REPOSITORY_LAYOUT_NONUNIQUE);

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.1.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);

        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.1.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveWithSimpleClassPathLayoutUsingDefaults() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy-defaults.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");

        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void testMavenRepoClassPathValue_WithSnapshot() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProjectWithSnapshot();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY);
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void testCustomClassPathValue() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest()
                .setCustomClasspathLayout(
                        "${artifact.groupIdPath}/${artifact.artifactId}/${artifact.version}/TEST-${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0/TEST-dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");

        final Manifest manifest1 = getJarFileManifest(jarFile);
        String classPath = manifest1.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0/TEST-dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void testCustomClassPathValue_WithSnapshotResolvedVersion() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProjectWithSnapshot();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);
        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest()
                .setCustomClasspathLayout(
                        "${artifact.groupIdPath}/${artifact.artifactId}/${artifact.baseVersion}/TEST-${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void testCustomClassPathValue_WithSnapshotForcingBaseVersion() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProjectWithSnapshot();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest()
                .setCustomClasspathLayout(
                        "${artifact.groupIdPath}/${artifact.artifactId}/${artifact.baseVersion}/TEST-${artifact.artifactId}-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}");
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();
        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                new String(manifest.getMainAttributes().getValue("Class-Path").getBytes()).split(" ");
        assertThat(classPathEntries[0]).isEqualTo("org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-SNAPSHOT.jar");
        assertThat(classPathEntries[1]).isEqualTo("org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar");
        assertThat(classPathEntries[2]).isEqualTo("org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");

        String classPath = getJarFileManifest(jarFile).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-SNAPSHOT.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void testDefaultPomProperties() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();

        JarFile virtJarFile = new JarFile(jarFile);
        ZipEntry pomPropertiesEntry =
                virtJarFile.getEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
        assertThat(pomPropertiesEntry).isNotNull();

        try (InputStream is = virtJarFile.getInputStream(pomPropertiesEntry)) {
            Properties p = new Properties();
            p.load(is);

            assertThat(p.getProperty("groupId")).isEqualTo(groupId);
            assertThat(p.getProperty("artifactId")).isEqualTo(artifactId);
            assertThat(p.getProperty("version")).isEqualTo(version);
        }
        virtJarFile.close();
    }

    @Test
    void testCustomPomProperties() throws Exception {
        MavenSession session = getDummySession();
        MavenProject project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        File customPomPropertiesFile = new File("src/test/resources/custom-pom.properties");
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.setPomPropertiesFile(customPomPropertiesFile);
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();

        try (JarFile virtJarFile = new JarFile(jarFile)) {
            ZipEntry pomPropertiesEntry =
                    virtJarFile.getEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
            assertThat(pomPropertiesEntry).isNotNull();

            try (InputStream is = virtJarFile.getInputStream(pomPropertiesEntry)) {
                Properties p = new Properties();
                p.load(is);

                assertThat(p.getProperty("groupId")).isEqualTo(groupId);
                assertThat(p.getProperty("artifactId")).isEqualTo(artifactId);
                assertThat(p.getProperty("version")).isEqualTo(version);
                assertThat(p.getProperty("build.revision")).isEqualTo("1337");
                assertThat(p.getProperty("build.branch")).isEqualTo("tags/0.1.1");
            }
        }
    }

    private JarArchiver getCleanJarArchiver(File jarFile) {
        deleteAndAssertNotPresent(jarFile);
        JarArchiver jarArchiver = new JarArchiver();
        jarArchiver.setDestFile(jarFile);
        return jarArchiver;
    }

    // ----------------------------------------
    // common methods for testing
    // ----------------------------------------

    private MavenProject getDummyProject() throws Exception {
        MavenProject project = getMavenProject();

        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact.getArtifactId()).thenReturn("dummy");
        when(artifact.getVersion()).thenReturn("0.1.1");
        when(artifact.getBaseVersion()).thenReturn("0.1.2");
        when(artifact.getSelectedVersion()).thenReturn(new DefaultArtifactVersion("0.1.1"));
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getArtifactHandler()).thenReturn(new DefaultArtifactHandler("jar"));
        project.setArtifact(artifact);

        Set<Artifact> artifacts = getArtifacts(getMockArtifact1Release(), getMockArtifact2(), getMockArtifact3());
        project.setArtifacts(artifacts);

        return project;
    }

    private MavenProject getMavenProject() {
        Model model = new Model();
        model.setGroupId("org.apache.dummy");
        model.setArtifactId("dummy");
        model.setVersion("0.1.1");

        final MavenProject project = new MavenProject(model);
        project.setRemoteArtifactRepositories(Collections.emptyList());
        project.setPluginArtifactRepositories(Collections.emptyList());
        project.setName("archiver test");

        File pomFile = new File("src/test/resources/pom.xml");
        project.setFile(pomFile);

        Build build = new Build();
        build.setDirectory("target");
        build.setOutputDirectory("target");
        project.setBuild(build);

        Organization organization = new Organization();
        organization.setName("Apache");
        project.setOrganization(organization);
        return project;
    }

    private Artifact getMockArtifact3() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy.bar");
        when(artifact.getArtifactId()).thenReturn("dummy3");
        when(artifact.getVersion()).thenReturn("2.0");
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getScope()).thenReturn("runtime");
        when(artifact.getClassifier()).thenReturn("classifier");
        File file = getClasspathFile(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
        when(artifact.getFile()).thenReturn(file);
        ArtifactHandler artifactHandler = mock(ArtifactHandler.class);
        when(artifactHandler.isAddedToClasspath()).thenReturn(true);
        when(artifactHandler.getExtension()).thenReturn("jar");
        when(artifact.getArtifactHandler()).thenReturn(artifactHandler);
        return artifact;
    }

    private MavenProject getDummyProjectWithSnapshot() throws Exception {
        MavenProject project = getMavenProject();

        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact.getArtifactId()).thenReturn("dummy");
        when(artifact.getVersion()).thenReturn("0.1.1");
        when(artifact.getBaseVersion()).thenReturn("0.1.1");
        when(artifact.getSelectedVersion()).thenReturn(new DefaultArtifactVersion("0.1.1"));
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getScope()).thenReturn("compile");
        when(artifact.getArtifactHandler()).thenReturn(new DefaultArtifactHandler("jar"));
        project.setArtifact(artifact);

        Set<Artifact> artifacts = getArtifacts(getMockArtifact1(), getMockArtifact2(), getMockArtifact3());
        project.setArtifacts(artifacts);

        return project;
    }

    private Artifact getMockArtifact2() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy.foo");
        when(artifact.getArtifactId()).thenReturn("dummy2");
        when(artifact.getVersion()).thenReturn("1.5");
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getScope()).thenReturn("runtime");
        File file = getClasspathFile(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
        when(artifact.getFile()).thenReturn(file);
        ArtifactHandler artifactHandler = mock(ArtifactHandler.class);
        when(artifactHandler.isAddedToClasspath()).thenReturn(true);
        when(artifactHandler.getExtension()).thenReturn("jar");
        when(artifact.getArtifactHandler()).thenReturn(artifactHandler);
        return artifact;
    }

    private Artifact getArtifactWithDot() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy.foo");
        when(artifact.getArtifactId()).thenReturn("dummy.dot");
        when(artifact.getVersion()).thenReturn("1.5");
        when(artifact.getScope()).thenReturn("runtime");
        when(artifact.getArtifactHandler()).thenReturn(new DefaultArtifactHandler("jar"));
        return artifact;
    }

    private Artifact getMockArtifact1() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact.getArtifactId()).thenReturn("dummy1");
        when(artifact.getVersion()).thenReturn("1.1-20081022.112233-1");
        when(artifact.getBaseVersion()).thenReturn("1.1-SNAPSHOT");
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getScope()).thenReturn("runtime");
        File file = getClasspathFile(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
        when(artifact.getFile()).thenReturn(file);
        ArtifactHandler artifactHandler = mock(ArtifactHandler.class);
        when(artifactHandler.isAddedToClasspath()).thenReturn(true);
        when(artifactHandler.getExtension()).thenReturn("jar");
        when(artifact.getArtifactHandler()).thenReturn(artifactHandler);
        return artifact;
    }

    private Artifact getMockArtifact1Release() {
        Artifact artifact = mock(Artifact.class);
        when(artifact.getGroupId()).thenReturn("org.apache.dummy");
        when(artifact.getArtifactId()).thenReturn("dummy1");
        when(artifact.getVersion()).thenReturn("1.0");
        when(artifact.getBaseVersion()).thenReturn("1.0.1");
        when(artifact.getType()).thenReturn("jar");
        when(artifact.getScope()).thenReturn("runtime");
        File file = getClasspathFile(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
        when(artifact.getFile()).thenReturn(file);
        ArtifactHandler artifactHandler = mock(ArtifactHandler.class);
        when(artifactHandler.isAddedToClasspath()).thenReturn(true);
        when(artifactHandler.getExtension()).thenReturn("jar");
        when(artifact.getArtifactHandler()).thenReturn(artifactHandler);
        return artifact;
    }

    private File getClasspathFile(String file) {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(file);
        if (resource == null) {
            throw new IllegalStateException(
                    "Cannot retrieve java.net.URL for file: " + file + " on the current test classpath.");
        }

        URI uri = new File(resource.getPath()).toURI().normalize();

        return new File(uri.getPath().replaceAll("%20", " "));
    }

    private MavenSession getDummySession() {
        Properties systemProperties = new Properties();
        systemProperties.put("maven.version", "3.1.1");
        systemProperties.put(
                "maven.build.version",
                "Apache Maven 3.1.1 (0728685237757ffbf44136acec0402957f723d9a; 2013-09-17 17:22:22+0200)");

        return getDummySession(systemProperties);
    }

    private MavenSession getDummySessionWithoutMavenVersion() {
        return getDummySession(new Properties());
    }

    private MavenSession getDummySession(Properties systemProperties) {
        MavenSession session = mock(MavenSession.class);
        when(session.getSystemProperties()).thenReturn(systemProperties);
        return session;
    }

    private Set<Artifact> getArtifacts(Artifact... artifacts) {
        Set<Artifact> result = new TreeSet<>(new ArtifactComparator());
        result.addAll(Arrays.asList(artifacts));
        return result;
    }

    public Manifest getJarFileManifest(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getManifest();
        }
    }

    @Test
    void testParseOutputTimestamp() {
        assertThat(parseBuildOutputTimestamp(null)).isEmpty();
        assertThat(parseBuildOutputTimestamp("")).isEmpty();
        assertThat(parseBuildOutputTimestamp(".")).isEmpty();
        assertThat(parseBuildOutputTimestamp(" ")).isEmpty();
        assertThat(parseBuildOutputTimestamp("_")).isEmpty();
        assertThat(parseBuildOutputTimestamp("-")).isEmpty();
        assertThat(parseBuildOutputTimestamp("/")).isEmpty();
        assertThat(parseBuildOutputTimestamp("!")).isEmpty();
        assertThat(parseBuildOutputTimestamp("*")).isEmpty();

        assertThat(parseBuildOutputTimestamp("1570300662").get().getEpochSecond())
                .isEqualTo(1570300662L);
        assertThat(parseBuildOutputTimestamp("0").get().getEpochSecond()).isZero();
        assertThat(parseBuildOutputTimestamp("1").get().getEpochSecond()).isEqualTo(1L);

        assertThat(parseBuildOutputTimestamp("2019-10-05T18:37:42Z").get().getEpochSecond())
                .isEqualTo(1570300662L);
        assertThat(parseBuildOutputTimestamp("2019-10-05T20:37:42+02:00").get().getEpochSecond())
                .isEqualTo(1570300662L);
        assertThat(parseBuildOutputTimestamp("2019-10-05T16:37:42-02:00").get().getEpochSecond())
                .isEqualTo(1570300662L);

        // These must result in IAE because we expect extended ISO format only (ie with - separator for date and
        // : separator for timezone), hence the XXX SimpleDateFormat for tz offset
        // X SimpleDateFormat accepts timezone without separator while date has separator, which is a mix between
        // basic (no separators, both for date and timezone) and extended (separator for both)
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> parseBuildOutputTimestamp("2019-10-05T20:37:42+0200"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> parseBuildOutputTimestamp("2019-10-05T20:37:42-0200"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", " ", "_", "-", "T", "/", "!", "!", "*", "ñ"})
    void testEmptyParseOutputTimestampInstant(String value) {
        // Empty optional if null or 1 char
        assertThat(parseBuildOutputTimestamp(value)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "0,0",
        "1,1",
        "9,9",
        "1570300662,1570300662",
        "2147483648,2147483648",
        "2019-10-05T18:37:42Z,1570300662",
        "2019-10-05T20:37:42+02:00,1570300662",
        "2019-10-05T16:37:42-02:00,1570300662",
        "1988-02-22T15:23:47.76598Z,572541827",
        "2011-12-03T10:15:30+01:00,1322903730",
        "1980-01-01T00:00:02Z,315532802",
        "2099-12-31T23:59:59Z,4102444799"
    })
    void testParseOutputTimestampInstant(String value, long expected) {
        assertThat(parseBuildOutputTimestamp(value)).contains(Instant.ofEpochSecond(expected));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2019-10-05T20:37:42+0200",
                "2019-10-05T20:37:42-0200",
                "2019-10-05T25:00:00Z",
                "2019-10-05",
                "XYZ",
                "Tue, 3 Jun 2008 11:05:30 GMT",
                "2011-12-03T10:15:30+01:00[Europe/Paris]"
            })
    void testThrownParseOutputTimestampInstant(String outputTimestamp) {
        // Invalid parsing
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> parseBuildOutputTimestamp(outputTimestamp))
                .withCauseInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "1980-01-01T00:00:01Z",
                "2100-01-01T00:00Z",
                "2100-02-28T23:59:59Z",
                "2099-12-31T23:59:59-01:00",
                "1980-01-01T00:15:35+01:00",
                "1980-01-01T10:15:35+14:00"
            })
    void testThrownParseOutputTimestampInvalidRange(String outputTimestamp) {
        // date is not within the valid range 1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> parseBuildOutputTimestamp(outputTimestamp))
                .withMessageContaining("is not within the valid range 1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z");
    }

    @ParameterizedTest
    @CsvSource({
        "2011-12-03T10:15:30+01,1322903730",
        "2019-10-05T20:37:42+02,1570300662",
        "2011-12-03T10:15:30+06,1322885730",
        "1988-02-22T20:37:42+06,572539062"
    })
    @EnabledForJreRange(min = JRE.JAVA_9)
    void testShortOffset(String value, long expected) {
        assertThat(parseBuildOutputTimestamp(value)).contains(Instant.ofEpochSecond(expected));
    }
}
