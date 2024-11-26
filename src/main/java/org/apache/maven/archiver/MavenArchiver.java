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

import javax.lang.model.SourceVersion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.jar.Manifest;
import org.codehaus.plexus.archiver.jar.ManifestException;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.Interpolator;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PrefixedPropertiesValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.codehaus.plexus.interpolation.ValueSource;

import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM;
import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY;
import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_SIMPLE;

/**
 * MavenArchiver class.
 */
public class MavenArchiver {

    private static final String CREATED_BY = "Maven Archiver";

    /**
     * The simple layout.
     */
    public static final String SIMPLE_LAYOUT =
            "${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}";

    /**
     * Repository layout.
     */
    public static final String REPOSITORY_LAYOUT =
            "${artifact.groupIdPath}/${artifact.artifactId}/" + "${artifact.baseVersion}/${artifact.artifactId}-"
                    + "${artifact.version}${dashClassifier?}.${artifact.extension}";

    /**
     * simple layout non unique.
     */
    public static final String SIMPLE_LAYOUT_NONUNIQUE =
            "${artifact.artifactId}-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";

    /**
     * Repository layout non unique.
     */
    public static final String REPOSITORY_LAYOUT_NONUNIQUE =
            "${artifact.groupIdPath}/${artifact.artifactId}/" + "${artifact.baseVersion}/${artifact.artifactId}-"
                    + "${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";

    private static final Instant DATE_MIN = Instant.parse("1980-01-01T00:00:02Z");

    private static final Instant DATE_MAX = Instant.parse("2099-12-31T23:59:59Z");

    private static final List<String> ARTIFACT_EXPRESSION_PREFIXES;

    static {
        List<String> artifactExpressionPrefixes = new ArrayList<>();
        artifactExpressionPrefixes.add("artifact.");

        ARTIFACT_EXPRESSION_PREFIXES = artifactExpressionPrefixes;
    }

    static boolean isValidModuleName(String name) {
        return SourceVersion.isName(name);
    }

    private JarArchiver archiver;

    private File archiveFile;

    private String createdBy;

    private boolean buildJdkSpecDefaultEntry = true;

    /**
     * <p>getManifest.</p>
     *
     * @param session the Maven Session
     * @param project the Maven Project
     * @param config  the MavenArchiveConfiguration
     * @return the {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws MavenArchiverException in case of a failure
     */
    public Manifest getManifest(Session session, Project project, MavenArchiveConfiguration config)
            throws MavenArchiverException {
        boolean hasManifestEntries = !config.isManifestEntriesEmpty();
        Map<String, String> entries = hasManifestEntries ? config.getManifestEntries() : Collections.emptyMap();

        Manifest manifest = getManifest(session, project, config.getManifest(), entries);

        try {
            // any custom manifest entries in the archive configuration manifest?
            if (hasManifestEntries) {

                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    Manifest.Attribute attr = manifest.getMainSection().getAttribute(key);
                    if (key.equals(Attributes.Name.CLASS_PATH.toString()) && attr != null) {
                        // Merge the user-supplied Class-Path value with the programmatically
                        // created Class-Path. Note that the user-supplied value goes first
                        // so that resources there will override any in the standard Class-Path.
                        attr.setValue(value + " " + attr.getValue());
                    } else {
                        addManifestAttribute(manifest, key, value);
                    }
                }
            }

            // any custom manifest sections in the archive configuration manifest?
            if (!config.isManifestSectionsEmpty()) {
                for (ManifestSection section : config.getManifestSections()) {
                    Manifest.Section theSection = new Manifest.Section();
                    theSection.setName(section.getName());

                    if (!section.isManifestEntriesEmpty()) {
                        Map<String, String> sectionEntries = section.getManifestEntries();

                        for (Map.Entry<String, String> entry : sectionEntries.entrySet()) {
                            String key = entry.getKey();
                            String value = entry.getValue();
                            Manifest.Attribute attr = new Manifest.Attribute(key, value);
                            theSection.addConfiguredAttribute(attr);
                        }
                    }

                    manifest.addConfiguredSection(theSection);
                }
            }
        } catch (ManifestException e) {
            throw new MavenArchiverException("Unable to create manifest", e);
        }

        return manifest;
    }

    /**
     * Return a pre-configured manifest.
     *
     * @param project {@link org.apache.maven.api.Project}
     * @param config  {@link org.apache.maven.archiver.ManifestConfiguration}
     * @return {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws MavenArchiverException exception.
     */
    // TODO Add user attributes list and user groups list
    public Manifest getManifest(Project project, ManifestConfiguration config) throws MavenArchiverException {
        return getManifest(null, project, config, Collections.emptyMap());
    }

    public Manifest getManifest(Session session, Project project, ManifestConfiguration config)
            throws MavenArchiverException {
        return getManifest(session, project, config, Collections.emptyMap());
    }

    private void addManifestAttribute(Manifest manifest, Map<String, String> map, String key, String value)
            throws ManifestException {
        if (map.containsKey(key)) {
            return; // The map value will be added later
        }
        addManifestAttribute(manifest, key, value);
    }

    private void addManifestAttribute(Manifest manifest, String key, String value) throws ManifestException {
        if (!(value == null || value.isEmpty())) {
            Manifest.Attribute attr = new Manifest.Attribute(key, value);
            manifest.addConfiguredAttribute(attr);
        } else {
            // if the value is empty, create an entry with an empty string
            // to prevent null print in the manifest file
            Manifest.Attribute attr = new Manifest.Attribute(key, "");
            manifest.addConfiguredAttribute(attr);
        }
    }

    /**
     * <p>getManifest.</p>
     *
     * @param session {@link org.apache.maven.api.Session}
     * @param project {@link org.apache.maven.api.Project}
     * @param config  {@link org.apache.maven.archiver.ManifestConfiguration}
     * @param entries The entries.
     * @return {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws MavenArchiverException exception
     */
    protected Manifest getManifest(
            Session session, Project project, ManifestConfiguration config, Map<String, String> entries)
            throws MavenArchiverException {
        try {
            return doGetManifest(session, project, config, entries);
        } catch (ManifestException e) {
            throw new MavenArchiverException("Unable to create manifest", e);
        }
    }

    protected Manifest doGetManifest(
            Session session, Project project, ManifestConfiguration config, Map<String, String> entries)
            throws ManifestException {
        // TODO: Should we replace "map" with a copy? Note, that we modify it!
        Manifest m = new Manifest();

        if (config.isAddDefaultEntries()) {
            handleDefaultEntries(m, entries);
        }

        if (config.isAddBuildEnvironmentEntries()) {
            handleBuildEnvironmentEntries(session, m, entries);
        }

        DependencyResolverResult result;
        if (config.isAddClasspath() || config.isAddExtensions()) {
            result = session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);
        } else {
            result = null;
        }

        if (config.isAddClasspath()) {
            StringBuilder classpath = new StringBuilder();

            String classpathPrefix = config.getClasspathPrefix();
            String layoutType = config.getClasspathLayoutType();
            String layout = config.getCustomClasspathLayout();

            Interpolator interpolator = new StringSearchInterpolator();

            for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
                Path artifactFile = entry.getValue();
                Dependency dependency = entry.getKey();
                if (Files.isRegularFile(artifactFile.toAbsolutePath())) {
                    if (!classpath.isEmpty()) {
                        classpath.append(" ");
                    }
                    classpath.append(classpathPrefix);

                    // NOTE: If the artifact or layout type (from config) is null, give up and use the file name by
                    // itself.
                    if (dependency == null || layoutType == null) {
                        classpath.append(artifactFile.getFileName().toString());
                    } else {
                        List<ValueSource> valueSources = new ArrayList<>();

                        handleExtraExpression(dependency, valueSources);

                        for (ValueSource vs : valueSources) {
                            interpolator.addValueSource(vs);
                        }

                        RecursionInterceptor recursionInterceptor =
                                new PrefixAwareRecursionInterceptor(ARTIFACT_EXPRESSION_PREFIXES);

                        try {
                            switch (layoutType) {
                                case CLASSPATH_LAYOUT_TYPE_SIMPLE:
                                    if (config.isUseUniqueVersions()) {
                                        classpath.append(interpolator.interpolate(SIMPLE_LAYOUT, recursionInterceptor));
                                    } else {
                                        classpath.append(interpolator.interpolate(
                                                SIMPLE_LAYOUT_NONUNIQUE, recursionInterceptor));
                                    }
                                    break;
                                case CLASSPATH_LAYOUT_TYPE_REPOSITORY:
                                    // we use layout /$groupId[0]/../${groupId[n]/$artifactId/$version/{fileName}
                                    // here we must find the Artifact in the project Artifacts
                                    // to create the maven layout
                                    if (config.isUseUniqueVersions()) {
                                        classpath.append(
                                                interpolator.interpolate(REPOSITORY_LAYOUT, recursionInterceptor));
                                    } else {
                                        classpath.append(interpolator.interpolate(
                                                REPOSITORY_LAYOUT_NONUNIQUE, recursionInterceptor));
                                    }
                                    break;
                                case CLASSPATH_LAYOUT_TYPE_CUSTOM:
                                    if (layout == null) {
                                        throw new ManifestException(CLASSPATH_LAYOUT_TYPE_CUSTOM
                                                + " layout type was declared, but custom layout expression was not"
                                                + " specified. Check your <archive><manifest><customLayout/>"
                                                + " element.");
                                    }

                                    classpath.append(interpolator.interpolate(layout, recursionInterceptor));
                                    break;
                                default:
                                    throw new ManifestException("Unknown classpath layout type: '" + layoutType
                                            + "'. Check your <archive><manifest><layoutType/> element.");
                            }
                        } catch (InterpolationException e) {
                            ManifestException error = new ManifestException(
                                    "Error interpolating artifact path for classpath entry: " + e.getMessage());

                            error.initCause(e);
                            throw error;
                        } finally {
                            for (ValueSource vs : valueSources) {
                                interpolator.removeValuesSource(vs);
                            }
                        }
                    }
                }
            }

            if (!classpath.isEmpty()) {
                // Class-Path is special and should be added to manifest even if
                // it is specified in the manifestEntries section
                addManifestAttribute(m, "Class-Path", classpath.toString());
            }
        }

        if (config.isAddDefaultSpecificationEntries()) {
            handleSpecificationEntries(project, entries, m);
        }

        if (config.isAddDefaultImplementationEntries()) {
            handleImplementationEntries(project, entries, m);
        }

        String mainClass = config.getMainClass();
        if (mainClass != null && !mainClass.isEmpty()) {
            addManifestAttribute(m, entries, "Main-Class", mainClass);
        }

        addCustomEntries(m, entries, config);

        return m;
    }

    private void handleExtraExpression(Dependency dependency, List<ValueSource> valueSources) {
        valueSources.add(new PrefixedObjectValueSource(ARTIFACT_EXPRESSION_PREFIXES, dependency, true));
        valueSources.add(new PrefixedObjectValueSource(ARTIFACT_EXPRESSION_PREFIXES, dependency.getType(), true));

        Properties extraExpressions = new Properties();
        // FIXME: This query method SHOULD NOT affect the internal
        // state of the artifact version, but it does.
        if (!dependency.isSnapshot()) {
            extraExpressions.setProperty("baseVersion", dependency.getVersion().toString());
        }

        extraExpressions.setProperty("groupIdPath", dependency.getGroupId().replace('.', '/'));
        String classifier = dependency.getClassifier();
        if (classifier != null && !classifier.isEmpty()) {
            extraExpressions.setProperty("dashClassifier", "-" + classifier);
            extraExpressions.setProperty("dashClassifier?", "-" + classifier);
        } else {
            extraExpressions.setProperty("dashClassifier", "");
            extraExpressions.setProperty("dashClassifier?", "");
        }
        valueSources.add(new PrefixedPropertiesValueSource(ARTIFACT_EXPRESSION_PREFIXES, extraExpressions, true));
    }

    private void handleImplementationEntries(Project project, Map<String, String> entries, Manifest m)
            throws ManifestException {
        addManifestAttribute(
                m, entries, "Implementation-Title", project.getModel().getName());
        addManifestAttribute(m, entries, "Implementation-Version", project.getVersion());

        if (project.getModel().getOrganization() != null) {
            addManifestAttribute(
                    m,
                    entries,
                    "Implementation-Vendor",
                    project.getModel().getOrganization().getName());
        }
    }

    private void handleSpecificationEntries(Project project, Map<String, String> entries, Manifest m)
            throws ManifestException {
        addManifestAttribute(
                m, entries, "Specification-Title", project.getModel().getName());

        String version = project.getPomArtifact().getVersion().toString();
        Matcher matcher = Pattern.compile("([0-9]+\\.[0-9]+)(.*?)").matcher(version);
        if (matcher.matches()) {
            String specVersion = matcher.group(1);
            addManifestAttribute(m, entries, "Specification-Version", specVersion);
        }
        /*
        TODO: v4: overconstrained
        try {
            Version version = project.getArtifact().getVersion();
            String specVersion = String.format("%s.%s", version.getMajorVersion(), version.getMinorVersion());
            addManifestAttribute(m, entries, "Specification-Version", specVersion);
        } catch (OverConstrainedVersionException e) {
            throw new ManifestException("Failed to get selected artifact version to calculate"
                + " the specification version: " + e.getMessage());
        }
        */

        if (project.getModel().getOrganization() != null) {
            addManifestAttribute(
                    m,
                    entries,
                    "Specification-Vendor",
                    project.getModel().getOrganization().getName());
        }
    }

    private void addCustomEntries(Manifest m, Map<String, String> entries, ManifestConfiguration config)
            throws ManifestException {
        /*
         * TODO: rethink this, it wasn't working Artifact projectArtifact = project.getArtifact(); if (
         * projectArtifact.isSnapshot() ) { Manifest.Attribute buildNumberAttr = new Manifest.Attribute( "Build-Number",
         * "" + project.getSnapshotDeploymentBuildNumber() ); m.addConfiguredAttribute( buildNumberAttr ); }
         */
        if (config.getPackageName() != null) {
            addManifestAttribute(m, entries, "Package", config.getPackageName());
        }
    }

    /**
     * <p>Getter for the field <code>archiver</code>.</p>
     *
     * @return {@link org.codehaus.plexus.archiver.jar.JarArchiver}
     */
    public JarArchiver getArchiver() {
        return archiver;
    }

    /**
     * <p>Setter for the field <code>archiver</code>.</p>
     *
     * @param archiver {@link org.codehaus.plexus.archiver.jar.JarArchiver}
     */
    public void setArchiver(JarArchiver archiver) {
        this.archiver = archiver;
    }

    /**
     * <p>setOutputFile.</p>
     *
     * @param outputFile Set output file.
     */
    public void setOutputFile(File outputFile) {
        archiveFile = outputFile;
    }

    /**
     * <p>createArchive.</p>
     *
     * @param session              {@link org.apache.maven.api.Session}
     * @param project              {@link org.apache.maven.api.Project}
     * @param archiveConfiguration {@link org.apache.maven.archiver.MavenArchiveConfiguration}
     * @throws MavenArchiverException Archiver Exception.
     */
    public void createArchive(Session session, Project project, MavenArchiveConfiguration archiveConfiguration)
            throws MavenArchiverException {
        try {
            doCreateArchive(session, project, archiveConfiguration);
        } catch (ManifestException | IOException e) {
            throw new MavenArchiverException(e);
        }
    }

    public void doCreateArchive(Session session, Project project, MavenArchiveConfiguration archiveConfiguration)
            throws ManifestException, IOException {
        // we have to clone the project instance so we can write out the pom with the deployment version,
        // without impacting the main project instance...
        boolean forced = archiveConfiguration.isForced();
        if (archiveConfiguration.isAddMavenDescriptor()) {
            // ----------------------------------------------------------------------
            // We want to add the metadata for the project to the JAR in two forms:
            //
            // The first form is that of the POM itself. Applications that wish to
            // access the POM for an artifact using maven tools they can.
            //
            // The second form is that of a properties file containing the basic
            // top-level POM elements so that applications that wish to access
            // POM information without the use of maven tools can do so.
            // ----------------------------------------------------------------------

            String groupId = project.getGroupId();

            String artifactId = project.getArtifactId();

            String version;
            if (project.getPomArtifact().isSnapshot()) {
                version = project.getPomArtifact().getVersion().toString();
            } else {
                version = project.getVersion();
            }

            archiver.addFile(
                    project.getPomPath().toFile(), "META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml");

            // ----------------------------------------------------------------------
            // Create pom.properties file
            // ----------------------------------------------------------------------

            Path customPomPropertiesFile = archiveConfiguration.getPomPropertiesFile();
            Path dir = Paths.get(project.getBuild().getDirectory(), "maven-archiver");
            Path pomPropertiesFile = dir.resolve("pom.properties");

            new PomPropertiesUtil()
                    .createPomProperties(
                            session,
                            groupId,
                            artifactId,
                            version,
                            archiver,
                            customPomPropertiesFile,
                            pomPropertiesFile,
                            forced);
        }

        // ----------------------------------------------------------------------
        // Create the manifest
        // ----------------------------------------------------------------------

        archiver.setMinimalDefaultManifest(true);
        Path manifestFile = archiveConfiguration.getManifestFile();
        if (manifestFile != null) {
            archiver.setManifest(manifestFile.toFile());
        }
        Manifest manifest = getManifest(session, project, archiveConfiguration);
        // Configure the jar
        archiver.addConfiguredManifest(manifest);
        archiver.setCompress(archiveConfiguration.isCompress());
        archiver.setRecompressAddedZips(archiveConfiguration.isRecompressAddedZips());
        archiver.setDestFile(archiveFile);
        archiver.setForced(forced);
        if (!archiveConfiguration.isForced() && archiver.isSupportingForced()) {
            // TODO Should issue a warning here, but how do we get a logger?
            // TODO getLog().warn(
            // "Forced build is disabled, but disabling the forced mode isn't supported by the archiver." );
        }
        String automaticModuleName = manifest.getMainSection().getAttributeValue("Automatic-Module-Name");
        if (automaticModuleName != null) {
            if (!isValidModuleName(automaticModuleName)) {
                throw new ManifestException("Invalid automatic module name: '" + automaticModuleName + "'");
            }
        }

        // create archive
        archiver.createArchive();
    }

    private void handleDefaultEntries(Manifest m, Map<String, String> entries) throws ManifestException {
        String createdBy = this.createdBy;
        if (createdBy == null) {
            createdBy = createdBy(CREATED_BY, "org.apache.maven", "maven-archiver");
        }
        addManifestAttribute(m, entries, "Created-By", createdBy);
        if (buildJdkSpecDefaultEntry) {
            addManifestAttribute(m, entries, "Build-Jdk-Spec", System.getProperty("java.specification.version"));
        }
    }

    private void handleBuildEnvironmentEntries(Session session, Manifest m, Map<String, String> entries)
            throws ManifestException {
        addManifestAttribute(
                m,
                entries,
                "Build-Tool",
                session != null ? session.getSystemProperties().get("maven.build.version") : "Apache Maven");
        addManifestAttribute(
                m,
                entries,
                "Build-Jdk",
                String.format("%s (%s)", System.getProperty("java.version"), System.getProperty("java.vendor")));
        addManifestAttribute(
                m,
                entries,
                "Build-Os",
                String.format(
                        "%s (%s; %s)",
                        System.getProperty("os.name"),
                        System.getProperty("os.version"),
                        System.getProperty("os.arch")));
    }

    private static String getCreatedByVersion(String groupId, String artifactId) {
        final Properties properties = loadOptionalProperties(MavenArchiver.class.getResourceAsStream(
                "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties"));

        return properties.getProperty("version");
    }

    private static Properties loadOptionalProperties(final InputStream inputStream) {
        Properties properties = new Properties();
        if (inputStream != null) {
            try (InputStream in = inputStream) {
                properties.load(in);
            } catch (IllegalArgumentException | IOException ex) {
                // ignore and return empty properties
            }
        }
        return properties;
    }

    /**
     * Define a value for "Created By" entry.
     *
     * @param description description of the plugin, like "Maven Source Plugin"
     * @param groupId groupId where to get version in pom.properties
     * @param artifactId artifactId where to get version in pom.properties
     * @since 3.5.0
     */
    public void setCreatedBy(String description, String groupId, String artifactId) {
        createdBy = createdBy(description, groupId, artifactId);
    }

    private String createdBy(String description, String groupId, String artifactId) {
        String createdBy = description;
        String version = getCreatedByVersion(groupId, artifactId);
        if (version != null) {
            createdBy += " " + version;
        }
        return createdBy;
    }

    /**
     * Add "Build-Jdk-Spec" entry as part of default manifest entries (true by default).
     * For plugins whose output is not impacted by JDK release (like maven-source-plugin), adding
     * Jdk spec adds unnecessary requirement on JDK version used at build to get reproducible result.
     *
     * @param buildJdkSpecDefaultEntry the value for "Build-Jdk-Spec" entry
     * @since 3.5.0
     */
    public void setBuildJdkSpecDefaultEntry(boolean buildJdkSpecDefaultEntry) {
        this.buildJdkSpecDefaultEntry = buildJdkSpecDefaultEntry;
    }

    /**
     * Parse output timestamp configured for Reproducible Builds' archive entries.
     *
     * <p>Either as {@link java.time.format.DateTimeFormatter#ISO_OFFSET_DATE_TIME} or as a number representing seconds
     * since the epoch (like <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @param outputTimestamp the value of {@code ${project.build.outputTimestamp}} (may be {@code null})
     * @return the parsed timestamp as an {@code Optional<Instant>}, {@code empty} if input is {@code null} or input
     *         contains only 1 character (not a number)
     * @since 3.6.0
     * @throws IllegalArgumentException if the outputTimestamp is neither ISO 8601 nor an integer, or it's not within
     *             the valid range 1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z as defined by
     *             <a href="https://pkwaredownloads.blob.core.windows.net/pem/APPNOTE.txt">ZIP application note</a>,
     *             section 4.4.6.
     */
    public static Optional<Instant> parseBuildOutputTimestamp(String outputTimestamp) {
        // Fail-fast on nulls
        if (outputTimestamp == null) {
            return Optional.empty();
        }

        // Number representing seconds since the epoch
        if (isNumeric(outputTimestamp)) {
            final Instant date = Instant.ofEpochSecond(Long.parseLong(outputTimestamp));

            if (date.isBefore(DATE_MIN) || date.isAfter(DATE_MAX)) {
                throw new IllegalArgumentException(
                        "'" + date + "' is not within the valid range " + DATE_MIN + " to " + DATE_MAX);
            }
            return Optional.of(date);
        }

        // no timestamp configured (1 character configuration is useful to override a full value during pom
        // inheritance)
        if (outputTimestamp.length() < 2) {
            return Optional.empty();
        }

        try {
            // Parse the date in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'.
            final Instant date = OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toInstant();

            if (date.isBefore(DATE_MIN) || date.isAfter(DATE_MAX)) {
                throw new IllegalArgumentException(
                        "'" + date + "' is not within the valid range " + DATE_MIN + " to " + DATE_MAX);
            }
            return Optional.of(date);
        } catch (DateTimeParseException pe) {
            throw new IllegalArgumentException(
                    "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", pe);
        }
    }

    private static boolean isNumeric(String str) {

        if (str.isEmpty()) {
            return false;
        }

        for (char c : str.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Configure Reproducible Builds archive creation if a timestamp is provided.
     *
     * @param outputTimestamp the value of {@code project.build.outputTimestamp} (may be {@code null})
     * @since 3.6.0
     * @see #parseBuildOutputTimestamp(String)
     */
    public void configureReproducibleBuild(String outputTimestamp) {
        parseBuildOutputTimestamp(outputTimestamp).map(FileTime::from).ifPresent(modifiedTime -> getArchiver()
                .configureReproducibleBuild(modifiedTime));
    }
}
