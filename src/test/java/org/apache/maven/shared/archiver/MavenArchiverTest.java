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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Type;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Organization;
import org.apache.maven.api.plugin.testing.stubs.ArtifactStub;
import org.apache.maven.api.plugin.testing.stubs.ProducedArtifactStub;
import org.apache.maven.api.plugin.testing.stubs.ProjectStub;
import org.apache.maven.api.plugin.testing.stubs.SessionMock;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MavenArchiverTest {

    Session session;
    DependencyResolver dependencyResolver;
    DependencyResolverResult dependencyResolverResult;
    Map<Dependency, Path> dependencies = new LinkedHashMap<>();

    @BeforeEach
    void setup() {
        session = getDummySession();
        dependencyResolver = mock(DependencyResolver.class);
        when(session.getService(DependencyResolver.class)).thenReturn(dependencyResolver);
        dependencyResolverResult = mock(DependencyResolverResult.class);
        when(dependencyResolver.resolve(eq(session), any(Project.class), eq(PathScope.MAIN_RUNTIME)))
                .thenReturn(dependencyResolverResult);
        when(dependencyResolverResult.getDependencies()).thenReturn(dependencies);
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
    void testMultiClassPath() throws Exception {
        final File tempFile = File.createTempFile("maven-archiver-test-", ".jar");

        try {
            MavenArchiver archiver = new MavenArchiver();

            dependencies.put(mock(Dependency.class), tempFile.getAbsoluteFile().toPath());

            ProjectStub project = new ProjectStub();
            project.setModel(Model.newBuilder().artifactId("dummy").build());

            // there should be a mock or a setter for this field.
            ManifestConfiguration manifestConfig = new ManifestConfiguration();
            manifestConfig.setAddClasspath(true);
            manifestConfig.setClasspathLayoutType(null);

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

        Project project = getDummyProject();

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

        Project project = getDummyProject();

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

        ProjectStub project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setModel(project.getModel().withDescription("foo " + ls + " bar "));
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getModel().getDescription());
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
    void testDashesInClassPathMSHARED134() {
        File jarFile = new File("target/test/dummyWithDashes.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        Project project = getDummyProject();

        useArtifacts(getMockArtifact1(), getArtifactWithDot(), getMockArtifact2(), getMockArtifact3());

        //        project.setArtifacts(artifacts);

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
    void testDashesInClassPathMSHARED182() throws IOException {
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);
        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        Project project = getDummyProject();

        useArtifacts(getMockArtifact1(), getArtifactWithDot(), getMockArtifact2(), getMockArtifact3());

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

        ProjectStub project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setModel(project.getModel().withDescription("foo " + ls + " bar "));
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getModel().getDescription());
        // config.addManifestEntry( "EntryWithTab", " foo tab " + ( '\u0009' ) + ( '\u0009' ) // + " bar tab" + ( //
        // '\u0009' // ) );
        archiver.createArchive(session, project, config);
        assertThat(jarFile).exists();

        final Manifest manifest = getJarFileManifest(jarFile);
        Attributes attributes = manifest.getMainAttributes();
        assertThat(project.getModel().getDescription().indexOf(ls)).isGreaterThan(0);
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

        Project project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);

        Session session = getDummySessionWithoutMavenVersion();
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

        Project project = getDummyProject();
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

        Project project = getDummyProject();
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

        Project project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();

        Map<String, String> manifestEntries = new HashMap<>();
        manifestEntries.put("Automatic-Module-Name", "123.in-valid.new.name");
        config.setManifestEntries(manifestEntries);

        try {
            archiver.createArchive(session, project, config);
        } catch (MavenArchiverException e) {
            assertThat(e.getMessage()).contains("Invalid automatic module name: '123.in-valid.new.name'");
        }
    }

    //
    // Test to make sure that manifest sections are present in the manifest prior to the archive has been created.
    //
    @Test
    void testManifestSections() throws Exception {
        MavenArchiver archiver = new MavenArchiver();

        Project project = getDummyProject();
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
        Project project = getDummyProject();
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

    @Test
    void testDefaultClassPathValueWithSnapshot() throws Exception {
        Project project = getDummyProjectWithSnapshot();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
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
    void testMavenRepoClassPathValueWithSnapshot() throws Exception {
        Project project = getDummyProjectWithSnapshot();
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
        Project project = getDummyProject();
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
    void testCustomClassPathValueWithSnapshotResolvedVersion() throws Exception {
        Project project = getDummyProjectWithSnapshot();
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
    void testCustomClassPathValueWithSnapshotForcingBaseVersion() throws Exception {
        Project project = getDummyProjectWithSnapshot();
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
        Project project = getDummyProject();
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
        Project project = getDummyProject();
        File jarFile = new File("target/test/dummy.jar");
        JarArchiver jarArchiver = getCleanJarArchiver(jarFile);

        MavenArchiver archiver = getMavenArchiver(jarArchiver);

        Path customPomPropertiesFile = Paths.get("src/test/resources/custom-pom.properties");
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

    private ProjectStub getDummyProject() {
        ProjectStub project = getProject();
        File pomFile = new File("src/test/resources/pom.xml");
        pomFile.setLastModified(System.currentTimeMillis() - 60000L);
        project.setPomPath(pomFile.toPath());
        Model model = Model.newBuilder()
                .groupId("org.apache.dummy")
                .artifactId("dummy")
                .version("0.1.1")
                .name("archiver test")
                .url("https://maven.apache.org")
                .organization(Organization.newBuilder().name("Apache").build())
                .build(Build.newBuilder()
                        .directory("target")
                        .outputDirectory("target")
                        .build())
                .build();
        project.setModel(model);
        ProducedArtifactStub artifact = new ProducedArtifactStub();
        artifact.setGroupId("org.apache.dummy");
        artifact.setArtifactId("dummy");
        artifact.setVersion("0.1.1");
        artifact.setExtension("jar");
        project.setMainArtifact(artifact);

        useArtifacts(getMockArtifact1Release(), getMockArtifact2(), getMockArtifact3());

        return project;
    }

    private ProjectStub getProject() {
        Model model = Model.newBuilder()
                .groupId("org.apache.dummy")
                .artifactId("dummy")
                .version("0.1.1")
                .build();

        ProjectStub project = new ProjectStub();
        project.setModel(model);
        return project;
    }

    private DependencyStub getMockArtifact3() {
        DependencyStub artifact3 = new DependencyStub();
        artifact3.setGroupId("org.apache.dummy.bar");
        artifact3.setArtifactId("dummy3");
        artifact3.setVersion("2.0");
        //        artifact3.setScope("runtime");
        artifact3.setExtension("jar");
        artifact3.setClassifier("classifier");
        artifact3.setPath(getClasspathFile(artifact3.getArtifactId() + "-" + artifact3.getVersion() + ".jar"));
        return artifact3;
    }

    private Project getDummyProjectWithSnapshot() {
        ProjectStub project = getProject();
        File pomFile = new File("src/test/resources/pom.xml");
        pomFile.setLastModified(System.currentTimeMillis() - 60000L);
        project.setPomPath(pomFile.toPath());
        project.setModel(Model.newBuilder()
                .groupId("org.apache.dummy")
                .artifactId("dummy")
                .version("0.1.1")
                .name("archiver test")
                .organization(Organization.newBuilder().name("Apache").build())
                .build(Build.newBuilder()
                        .directory("target")
                        .outputDirectory("target")
                        .build())
                .build());

        ProducedArtifactStub artifact = new ProducedArtifactStub();
        artifact.setGroupId("org.apache.dummy");
        artifact.setArtifactId("dummy");
        artifact.setVersion("0.1.1");
        artifact.setExtension("jar");
        project.setMainArtifact(artifact);

        useArtifacts(getMockArtifact1(), getMockArtifact2(), getMockArtifact3());

        return project;
    }

    private DependencyStub getMockArtifact2() {
        DependencyStub artifact2 = new DependencyStub();
        artifact2.setGroupId("org.apache.dummy.foo");
        artifact2.setArtifactId("dummy2");
        artifact2.setVersion("1.5");
        artifact2.setExtension("jar");
        //        artifact2.setScope("runtime");
        artifact2.setPath(getClasspathFile(artifact2.getArtifactId() + "-" + artifact2.getVersion() + ".jar"));
        return artifact2;
    }

    private DependencyStub getArtifactWithDot() {
        DependencyStub artifact2 = new DependencyStub();
        artifact2.setGroupId("org.apache.dummy.foo");
        artifact2.setArtifactId("dummy.dot");
        artifact2.setVersion("1.5");
        artifact2.setExtension("jar");
        //        artifact2.setScope("runtime");
        artifact2.setPath(getClasspathFile(artifact2.getArtifactId() + "-" + artifact2.getVersion() + ".jar"));
        return artifact2;
    }

    private DependencyStub getMockArtifact1() {
        DependencyStub artifact1 = new DependencyStub();
        artifact1.setGroupId("org.apache.dummy");
        artifact1.setArtifactId("dummy1");
        artifact1.setVersion("1.1-20081022.112233-1");
        artifact1.setBaseVersion("1.1-SNAPSHOT");
        artifact1.setExtension("jar");
        //        artifact1.setScope("runtime");
        artifact1.setPath(getClasspathFile(artifact1.getArtifactId() + "-" + artifact1.getVersion() + ".jar"));
        return artifact1;
    }

    private DependencyStub getMockArtifact1Release() {
        DependencyStub artifact1 = new DependencyStub();
        artifact1.setGroupId("org.apache.dummy");
        artifact1.setArtifactId("dummy1");
        artifact1.setVersion("1.0");
        artifact1.setBaseVersion("1.0.1");
        artifact1.setExtension("jar");
        //        artifact1.setScope("runtime");
        artifact1.setPath(getClasspathFile(artifact1.getArtifactId() + "-" + artifact1.getVersion() + ".jar"));
        return artifact1;
    }

    private Path getClasspathFile(String file) {
        URL resource = Thread.currentThread().getContextClassLoader().getResource(file);
        if (resource == null) {
            throw new IllegalStateException(
                    "Cannot retrieve java.net.URL for file: " + file + " on the current test classpath.");
        }

        URI uri = new File(resource.getPath()).toURI().normalize();

        return new File(uri.getPath().replaceAll("%20", " ")).toPath();
    }

    private Session getDummySession() {
        HashMap<String, String> systemProperties = new HashMap<>();
        systemProperties.put("maven.version", "3.1.1");
        systemProperties.put(
                "maven.build.version",
                "Apache Maven 3.1.1 (0728685237757ffbf44136acec0402957f723d9a; 2013-09-17 17:22:22+0200)");

        return getDummySession(systemProperties);
    }

    private Session getDummySessionWithoutMavenVersion() {
        return getDummySession(new HashMap<>());
    }

    private Session getDummySession(Map<String, String> systemProperties) {
        Session session = SessionMock.getMockSession("target/local-repo");
        when(session.getSystemProperties()).thenReturn(systemProperties);

        return session;
    }

    private void useArtifacts(DependencyStub... dependencies) {
        for (DependencyStub dependency : dependencies) {
            this.dependencies.put(dependency, dependency.getPath());
        }
    }

    private Manifest getJarFileManifest(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getManifest();
        }
    }

    @Test
    void testParseOutputTimestamp() {
        assertThat(MavenArchiver.parseBuildOutputTimestamp(null)).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp(".")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp(" ")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("_")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("-")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("/")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("!")).isEmpty();
        assertThat(MavenArchiver.parseBuildOutputTimestamp("*")).isEmpty();

        assertThat(MavenArchiver.parseBuildOutputTimestamp("1570300662").get().toEpochMilli())
                .isEqualTo(1570300662000L);

        assertThat(MavenArchiver.parseBuildOutputTimestamp("2019-10-05T18:37:42Z")
                        .get()
                        .toEpochMilli())
                .isEqualTo(1570300662000L);
        assertThat(MavenArchiver.parseBuildOutputTimestamp("2019-10-05T20:37:42+02:00")
                        .get()
                        .toEpochMilli())
                .isEqualTo(1570300662000L);
        assertThat(MavenArchiver.parseBuildOutputTimestamp("2019-10-05T16:37:42-02:00")
                        .get()
                        .toEpochMilli())
                .isEqualTo(1570300662000L);

        // These must result in IAE because we expect extended ISO format only (ie with - separator for date and
        // : separator for timezone), hence the XXX SimpleDateFormat for tz offset
        // X SimpleDateFormat accepts timezone without separator while date has separator, which is a mix between
        // basic (no separators, both for date and timezone) and extended (separator for both)
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp("2019-10-05T20:37:42+0200"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp("2019-10-05T20:37:42-0200"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", " ", "_", "-", "T", "/", "!", "!", "*", "ñ"})
    void testEmptyParseOutputTimestampInstant(String value) {
        // Empty optional if null or 1 char
        assertThat(MavenArchiver.parseBuildOutputTimestamp(value)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
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
        assertThat(MavenArchiver.parseBuildOutputTimestamp(value)).contains(Instant.ofEpochSecond(expected));
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
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp(outputTimestamp))
                .withCauseInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "0",
                "1",
                "9",
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
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp(outputTimestamp))
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
        assertThat(MavenArchiver.parseBuildOutputTimestamp(value)).contains(Instant.ofEpochSecond(expected));
    }

    private void deleteAndAssertNotPresent(File jarFile) {
        jarFile.delete();
        assertThat(jarFile).doesNotExist();
    }

    static class DependencyStub extends ArtifactStub implements Dependency {
        Type type;
        Map<String, String> properties;
        DependencyScope scope;
        boolean optional;
        Path path;

        @Override
        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        @Override
        public DependencyScope getScope() {
            return scope;
        }

        public void setScope(DependencyScope scope) {
            this.scope = scope;
        }

        @Override
        public boolean isOptional() {
            return optional;
        }

        public void setOptional(boolean optional) {
            this.optional = optional;
        }

        @Override
        public DependencyCoordinates toCoordinates() {
            return null;
        }

        public Path getPath() {
            return path;
        }

        public void setPath(Path path) {
            this.path = path;
        }
    }
}
