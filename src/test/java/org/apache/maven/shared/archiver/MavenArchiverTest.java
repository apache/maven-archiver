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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

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
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.Interpolator;
import org.apache.maven.impl.model.DefaultInterpolator;
import org.apache.maven.testing.plugin.stubs.ArtifactStub;
import org.apache.maven.testing.plugin.stubs.ProducedArtifactStub;
import org.apache.maven.testing.plugin.stubs.ProjectStub;
import org.apache.maven.testing.plugin.stubs.SessionMock;
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
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(session.getService(Interpolator.class)).thenReturn(new DefaultInterpolator());
        dependencyResolverResult = mock(DependencyResolverResult.class);
        when(dependencyResolver.resolve(eq(session), any(Project.class), eq(PathScope.MAIN_RUNTIME)))
                .thenReturn(dependencyResolverResult);
        when(dependencyResolverResult.getDependencies()).thenReturn(dependencies);
    }

    @Test
    void malformedPomPropertiesFallBackToEmptyProperties() throws Exception {
        Method method =
                MavenArchiver.class.getDeclaredMethod("loadOptionalProperties", InputStream.class, String.class);
        method.setAccessible(true);

        String resourcePath = "/META-INF/maven/org.apache.maven.shared/maven-archiver/pom.properties";
        assertThat(method.invoke(
                        null,
                        new ByteArrayInputStream("version=\\uZZZZ".getBytes(StandardCharsets.ISO_8859_1)),
                        resourcePath))
                .isEqualTo(new Properties());
    }

    @Test
    void getManifestWithoutSessionWhenAddClasspath() {
        MavenArchiver archiver = new MavenArchiver();
        Project project = getDummyProject();
        ManifestConfiguration config = new ManifestConfiguration();
        config.setAddClasspath(true);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> archiver.getManifest(project, config))
                .withMessageContaining("Session");
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
    void invalidModuleNames(String value) {
        assertThat(MavenArchiver.isValidModuleName(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "a.b", "a_b", "trailing0.digits123.are456.ok789", "UTF8.chars.are.okay.äëïöüẍ", "ℤ€ℕ"})
    void validModuleNames(String value) {
        assertThat(MavenArchiver.isValidModuleName(value)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
        "dash-is-invalid,          dash.is.invalid",
        "integration-test,         integration.test",
        "org.apache.foo-bar,       org.apache.foo.bar",
        "a--b,                     a.b",
        "-leading,                 leading",
        "trailing-,                trailing",
        "a+b:c,                    a.b.c"
    })
    void cleanModuleNames(String input, String expected) {
        assertThat(MavenArchiver.cleanModuleName(input.trim())).isEqualTo(expected.trim());
    }

    @Test
    void multiClassPath() throws Exception {
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
    void notGenerateImplementationVersionForManifest() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
        Project project = getDummyProject();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(false);

        Manifest manifest = archiver.getManifest(session, project, config);
        assertThat(manifest.getMainAttributes()).doesNotContainKey(Attributes.Name.IMPLEMENTATION_VERSION);
    }

    @Test
    void generateImplementationVersionForManifest() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
        ProjectStub project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setModel(project.getModel().withDescription("foo " + ls + " bar "));
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getModel().getDescription());

        Manifest manifest = archiver.getManifest(session, project, config);
        assertThat(manifest.getMainAttributes())
                .containsKey(Attributes.Name.IMPLEMENTATION_VERSION)
                .containsEntry(Attributes.Name.IMPLEMENTATION_VERSION, "0.1.1");
    }

    @Test
    void newlinesInManifestEntryGetReplacedWithSpaces() throws Exception {
        ProjectStub project = getDummyProject();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.addManifestEntry("MultiLine", "line1\nline2\nline3");
        config.addManifestEntry("WithCRLF", "line1\r\nline2");
        config.addManifestEntry("WithTrailingNewline", "trailing\n");

        MavenArchiver archiver = new MavenArchiver();
        Manifest manifest = archiver.getManifest(session, project, config);
        Attributes attributes = manifest.getMainAttributes();

        assertThat(attributes.getValue("MultiLine")).isEqualTo("line1 line2 line3");
        assertThat(attributes.getValue("WithCRLF")).isEqualTo("line1 line2");
        assertThat(attributes.getValue("WithTrailingNewline")).doesNotContain("\n");
    }

    @Test
    void carriageReturnInManifestEntry() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
        ProjectStub project = getDummyProject();

        String ls = System.getProperty("line.separator");
        project.setModel(project.getModel().withDescription("foo " + ls + " bar "));
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.addManifestEntry("Description", project.getModel().getDescription());

        Manifest manifest = archiver.getManifest(session, project, config);
        Attributes attributes = manifest.getMainAttributes();
        assertThat(project.getModel().getDescription().indexOf(ls)).isGreaterThan(0);
        Attributes.Name description = new Attributes.Name("Description");
        String value = attributes.getValue(description);
        assertThat(value).isNotNull();
        assertThat(value.indexOf(ls)).isLessThanOrEqualTo(0);
    }

    @Test
    void deprecatedCreateArchiveAPI() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
        Project project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);

        Session sessionNoMaven = getDummySessionWithoutMavenVersion();
        when(sessionNoMaven.getService(DependencyResolver.class)).thenReturn(dependencyResolver);
        when(sessionNoMaven.getService(Interpolator.class)).thenReturn(new DefaultInterpolator());

        Manifest manifest = archiver.getManifest(sessionNoMaven, project, config);
        Attributes attrs = manifest.getMainAttributes();

        // no version number
        assertThat(attrs)
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
    void minimalManifestEntries() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
        Project project = getDummyProject();
        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultEntries(false);

        Manifest manifest = archiver.getManifest(session, project, config);
        Attributes attrs = manifest.getMainAttributes();

        assertThat(attrs).hasSize(1).containsOnlyKeys(new Attributes.Name("Manifest-Version"));
        assertThat(attrs.getValue("Manifest-Version")).isEqualTo("1.0");
    }

    @Test
    void manifestEntries() throws Exception {
        MavenArchiver archiver = new MavenArchiver();
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

        Manifest manifest = archiver.getManifest(session, project, config);
        Attributes attrs = manifest.getMainAttributes();

        // no version number
        assertThat(attrs)
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

        assertThat(attrs.getValue("Automatic-Module-Name")).isEqualTo("org.apache.maven.archiver");

        assertThat(attrs)
                .containsEntry(new Attributes.Name("Build-Jdk-Spec"), System.getProperty("java.specification.version"));

        assertThat(attrs.getValue(new Attributes.Name("keyWithEmptyValue"))).isEmpty();
        assertThat(attrs).containsKey(new Attributes.Name("keyWithEmptyValue"));

        Attributes section = manifest.getAttributes("UserSection");
        assertThat(section).containsEntry(new Attributes.Name("key"), "value");
    }

    //
    // Test to make sure that manifest sections are present in the manifest prior to the archive has been created.
    //
    @Test
    void manifestSections() throws Exception {
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
    void defaultClassPathValue() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

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

        Manifest manifest = archiver.getManifest(session, project, config);
        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("dummy1-1.0.jar", "dummy2-1.5.jar", "dummy3-2.0-classifier.jar");
    }

    @Test
    void defaultClassPathValueWithSnapshot() throws Exception {
        Project project = getDummyProjectWithSnapshot();
        MavenArchiver archiver = new MavenArchiver();

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

        Manifest manifest = archiver.getManifest(session, project, config);
        String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
        assertThat(classPath).isNotNull();
        assertThat(classPath.split(" "))
                .containsExactly("dummy1-1.1-20081022.112233-1.jar", "dummy2-1.5.jar", "dummy3-2.0-classifier.jar");
    }

    @Test
    void mavenRepoClassPathValue() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setUseUniqueVersions(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveWithSimpleClassPathLayoutWhileSettingSimpleLayoutExplicit() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_SIMPLE);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomerLayoutSimple() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.SIMPLE_LAYOUT);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutSimpleNonUnique() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.SIMPLE_LAYOUT_NONUNIQUE);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.1.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutRepository() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.REPOSITORY_LAYOUT);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveCustomLayoutRepositoryNonUnique() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM);
        config.getManifest().setCustomClasspathLayout(MavenArchiver.REPOSITORY_LAYOUT_NONUNIQUE);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "lib/org/apache/dummy/dummy1/1.0.1/dummy1-1.0.1.jar",
                        "lib/org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "lib/org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void shouldCreateArchiveWithSimpleClassPathLayoutUsingDefaults() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathPrefix("lib");

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly("lib/dummy1-1.0.jar", "lib/dummy2-1.5.jar", "lib/dummy3-2.0-classifier.jar");
    }

    @Test
    void mavenRepoClassPathValueWithSnapshot() throws Exception {
        Project project = getDummyProjectWithSnapshot();
        MavenArchiver archiver = new MavenArchiver();

        MavenArchiveConfiguration config = new MavenArchiveConfiguration();
        config.setForced(true);
        config.getManifest().setAddDefaultImplementationEntries(true);
        config.getManifest().setAddDefaultSpecificationEntries(true);
        config.getManifest().setMainClass("org.apache.maven.Foo");
        config.getManifest().setAddClasspath(true);
        config.getManifest().setClasspathLayoutType(ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY);

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/dummy3-2.0-classifier.jar");
    }

    @Test
    void customClassPathValue() throws Exception {
        Project project = getDummyProject();
        MavenArchiver archiver = new MavenArchiver();

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

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.0/TEST-dummy1-1.0.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void customClassPathValueWithSnapshotResolvedVersion() throws Exception {
        Project project = getDummyProjectWithSnapshot();
        MavenArchiver archiver = new MavenArchiver();

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

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries)
                .containsExactly(
                        "org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-20081022.112233-1.jar",
                        "org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar",
                        "org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void customClassPathValueWithSnapshotForcingBaseVersion() throws Exception {
        Project project = getDummyProjectWithSnapshot();
        MavenArchiver archiver = new MavenArchiver();

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

        Manifest manifest = archiver.getManifest(session, project, config);
        String[] classPathEntries =
                manifest.getMainAttributes().getValue("Class-Path").split(" ");
        assertThat(classPathEntries[0]).isEqualTo("org/apache/dummy/dummy1/1.1-SNAPSHOT/TEST-dummy1-1.1-SNAPSHOT.jar");
        assertThat(classPathEntries[1]).isEqualTo("org/apache/dummy/foo/dummy2/1.5/TEST-dummy2-1.5.jar");
        assertThat(classPathEntries[2]).isEqualTo("org/apache/dummy/bar/dummy3/2.0/TEST-dummy3-2.0-classifier.jar");
    }

    @Test
    void parseOutputTimestamp() {
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
    @CsvSource({"0", "1", "315532801", "1970-01-01T00:00:00Z", "1979-12-31T23:59:59Z"})
    void parseOutputTimestampClampsToDateMin(String value) {
        // Timestamps before DATE_MIN must be clamped rather than rejected (MJAR-595)
        Optional<Instant> result = MavenArchiver.parseBuildOutputTimestamp(value);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(MavenArchiver.DATE_MIN);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {".", " ", "_", "-", "T", "/", "!", "!", "*", "ñ"})
    void emptyParseOutputTimestampInstant(String value) {
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
        "2099-12-31T23:59:59Z,4102444799",
        // Negative epoch timestamps (seconds before 1970-01-01) must be accepted (numeric)
        "-1,-1",
        "-315532802,-315532802",
        // ISO 8601 pre-epoch dates must also pass through without clamping
        "1969-12-31T23:59:59Z,-1",
        "1960-01-01T00:00:00Z,-315619200"
    })
    void parseOutputTimestampInstant(String value, long expected) {
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
    void thrownParseOutputTimestampInstant(String outputTimestamp) {
        // Invalid parsing
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp(outputTimestamp))
                .withCauseInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Arabic-Indic digits (U+0660–U+0669)
                "٠١٢٣٤٥٦٧٨٩",
                // Bengali digits (U+09E6–U+09EF)
                "০১২৩৪",
                // Devanagari digits (U+0966–U+096F)
                "०१२३४",
                // Extended Arabic-Indic digits (U+06F0–U+06F9)
                "۰۱۲۳۴"
            })
    void unicodeDigitsAreRejectedAsTimestamp(String unicodeDigits) {
        // Character.isDigit() returns true for non-ASCII digits, but Long.parseLong() only accepts ASCII 0-9.
        // These must not throw NumberFormatException — they must yield IAE with DateTimeParseException cause.
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MavenArchiver.parseBuildOutputTimestamp(unicodeDigits))
                .withCauseInstanceOf(DateTimeParseException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "2011-12-03T10:15:30+01,1322903730",
        "2019-10-05T20:37:42+02,1570300662",
        "2011-12-03T10:15:30+06,1322885730",
        "1988-02-22T20:37:42+06,572539062"
    })
    @EnabledForJreRange(min = JRE.JAVA_9)
    void shortOffset(String value, long expected) {
        assertThat(MavenArchiver.parseBuildOutputTimestamp(value)).contains(Instant.ofEpochSecond(expected));
    }

    @Test
    void addExtensionsAloneDoesNotTriggerDependencyResolution() throws Exception {
        MavenArchiver archiver = new MavenArchiver();

        ProjectStub project = new ProjectStub();
        project.setModel(Model.newBuilder().artifactId("dummy").build());

        ManifestConfiguration manifestConfig = new ManifestConfiguration();
        manifestConfig.setAddExtensions(true);
        manifestConfig.setAddClasspath(false);

        MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();
        archiveConfiguration.setManifest(manifestConfig);

        archiver.getManifest(session, project, archiveConfiguration);

        verify(dependencyResolver, never()).resolve(any(), any(Project.class), any());
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
        artifact1.setPath(getClasspathFile(artifact1.getArtifactId() + "-" + artifact1.getVersion() + ".jar"));
        return artifact1;
    }

    private Path getClasspathFile(String file) {
        java.net.URL resource = Thread.currentThread().getContextClassLoader().getResource(file);
        if (resource == null) {
            throw new IllegalStateException(
                    "Cannot retrieve java.net.URL for file: " + file + " on the current test classpath.");
        }

        java.net.URI uri = new File(resource.getPath()).toURI().normalize();

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
