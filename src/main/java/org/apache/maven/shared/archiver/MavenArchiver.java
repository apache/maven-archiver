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

import javax.lang.model.SourceVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.Interpolator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.maven.shared.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM;
import static org.apache.maven.shared.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY;
import static org.apache.maven.shared.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_SIMPLE;

/**
 * MavenArchiver class.
 */
public class MavenArchiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MavenArchiver.class);

    private static final String CREATED_BY = "Maven Archiver";

    /**
     * Minimum timestamp value for ZIP/JAR entries (1980-01-01T00:00:02Z).
     * Timestamps before this value are clamped to it with a warning.
     *
     * @since 4.0.0-beta-6
     */
    public static final Instant DATE_MIN = Instant.parse("1980-01-01T00:00:02Z");

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
     * Simple layout non unique.
     */
    public static final String SIMPLE_LAYOUT_NONUNIQUE =
            "${artifact.artifactId}-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";

    /**
     * Repository layout non unique.
     */
    public static final String REPOSITORY_LAYOUT_NONUNIQUE =
            "${artifact.groupIdPath}/${artifact.artifactId}/" + "${artifact.baseVersion}/${artifact.artifactId}-"
                    + "${artifact.baseVersion}${dashClassifier?}.${artifact.extension}";

    private static final Pattern SPECIFICATION_VERSION_PATTERN = Pattern.compile("([0-9]+\\.[0-9]+)(.*?)");

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Za-z0-9]");

    private static final Pattern REPEATING_DOTS = Pattern.compile("\\.{2,}");

    static boolean isValidModuleName(String name) {
        return SourceVersion.isName(name);
    }

    /**
     * Sanitizes a candidate module name by applying the same algorithm the JDK uses to derive
     * an automatic module name from a JAR file name (see {@code ModulePath.cleanModuleName()}):
     * replaces non-alphanumeric characters with {@code '.'}, collapses repeated dots, and strips
     * leading/trailing dots.
     *
     * @param name the raw module name
     * @return a sanitized module name, or an empty string if nothing remains after sanitization
     */
    static String cleanModuleName(String name) {
        // Replace non-alphanumeric (including hyphens) with dots — mirrors JDK ModulePath.cleanModuleName()
        name = NON_ALPHANUM.matcher(name).replaceAll(".");
        // Collapse repeated dots
        name = REPEATING_DOTS.matcher(name).replaceAll(".");
        // Strip leading/trailing dots
        name = name.replaceAll("^\\.+|\\.+$", "");
        return name;
    }

    private String createdBy;

    private boolean buildJdkSpecDefaultEntry = true;

    /**
     * <p>getManifest.</p>
     *
     * @param session the Maven Session
     * @param project the Maven Project
     * @param config  the MavenArchiveConfiguration
     * @return the {@link java.util.jar.Manifest}
     * @throws MavenArchiverException in case of a failure
     */
    public Manifest getManifest(Session session, Project project, MavenArchiveConfiguration config)
            throws MavenArchiverException {
        boolean hasManifestEntries = !config.isManifestEntriesEmpty();
        Map<String, String> entries = hasManifestEntries ? config.getManifestEntries() : Collections.emptyMap();

        Manifest manifest = getManifest(session, project, config.getManifest(), entries);

        // any custom manifest entries in the archive configuration manifest?
        if (hasManifestEntries) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                String existing = manifest.getMainAttributes().getValue(key);
                if (key.equals(Attributes.Name.CLASS_PATH.toString()) && existing != null) {
                    // Merge the user-supplied Class-Path value with the programmatically
                    // created Class-Path. Note that the user-supplied value goes first
                    // so that resources there will override any in the standard Class-Path.
                    manifest.getMainAttributes().putValue(key, value + " " + existing);
                } else {
                    addManifestAttribute(manifest, key, value);
                }
            }
        }

        // any custom manifest sections in the archive configuration manifest?
        if (!config.isManifestSectionsEmpty()) {
            for (ManifestSection section : config.getManifestSections()) {
                Attributes attrs = new Attributes();

                if (!section.isManifestEntriesEmpty()) {
                    Map<String, String> sectionEntries = section.getManifestEntries();

                    for (Map.Entry<String, String> entry : sectionEntries.entrySet()) {
                        String key = entry.getKey();
                        String value = sanitizeManifestValue(entry.getValue());
                        attrs.putValue(key, value);
                    }
                }

                manifest.getEntries().put(section.getName(), attrs);
            }
        }

        return manifest;
    }

    /**
     * Return a pre-configured manifest.
     *
     * @param project {@link org.apache.maven.api.Project}
     * @param config  {@link ManifestConfiguration}
     * @return {@link java.util.jar.Manifest}
     * @throws MavenArchiverException exception
     */
    // TODO Add user attributes list and user groups list
    public Manifest getManifest(Project project, ManifestConfiguration config) throws MavenArchiverException {
        return getManifest(null, project, config, Collections.emptyMap());
    }

    public Manifest getManifest(Session session, Project project, ManifestConfiguration config)
            throws MavenArchiverException {
        return getManifest(session, project, config, Collections.emptyMap());
    }

    private void addManifestAttribute(Manifest manifest, Map<String, String> map, String key, String value) {
        if (map.containsKey(key)) {
            return; // The map value will be added later
        }
        addManifestAttribute(manifest, key, value);
    }

    private void addManifestAttribute(Manifest manifest, String key, String value) {
        String sanitized = sanitizeManifestValue(value);
        manifest.getMainAttributes().putValue(key, sanitized);
    }

    /**
     * Sanitizes a manifest attribute value for safe inclusion in the manifest.
     *
     * <ul>
     *   <li>A {@code null} value is converted to an empty string. This is intentional and allows
     *       callers to produce empty manifest attributes explicitly.</li>
     *   <li>Embedded CR+LF, CR, or LF characters are replaced with spaces, as multi-line values
     *       are not valid in a manifest attribute.</li>
     * </ul>
     *
     * @param value the raw attribute value; may be {@code null}
     * @return the sanitized value, never {@code null}
     */
    private static String sanitizeManifestValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * <p>getManifest.</p>
     *
     * @param session {@link org.apache.maven.api.Session}
     * @param project {@link org.apache.maven.api.Project}
     * @param config  {@link ManifestConfiguration}
     * @param entries the entries
     * @return {@link java.util.jar.Manifest}
     * @throws MavenArchiverException exception
     */
    protected Manifest getManifest(
            Session session, Project project, ManifestConfiguration config, Map<String, String> entries)
            throws MavenArchiverException {
        return doGetManifest(session, project, config, entries);
    }

    protected Manifest doGetManifest(
            Session session, Project project, ManifestConfiguration config, Map<String, String> entries) {
        // TODO: Should we replace "map" with a copy? Note, that we modify it!
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        if (config.isAddDefaultEntries()) {
            handleDefaultEntries(project, m, entries);
        }

        if (config.isAddBuildEnvironmentEntries()) {
            handleBuildEnvironmentEntries(session, m, entries);
        }

        DependencyResolverResult result;
        if (config.isAddClasspath()) {
            if (session == null) {
                throw new NullPointerException("Session is required when addClasspath is enabled. "
                        + "Use getManifest(Session, Project, ManifestConfiguration) instead.");
            }
            result = session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);
        } else {
            result = null;
        }

        if (config.isAddClasspath()) {
            StringBuilder classpath = new StringBuilder();

            String classpathPrefix = config.getClasspathPrefix();
            String layoutType = config.getClasspathLayoutType();
            String layout = config.getCustomClasspathLayout();

            Interpolator interpolator = session.getService(Interpolator.class);

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
                        String selectedLayout;
                        switch (layoutType) {
                            case CLASSPATH_LAYOUT_TYPE_SIMPLE:
                                selectedLayout = config.isUseUniqueVersions() ? SIMPLE_LAYOUT : SIMPLE_LAYOUT_NONUNIQUE;
                                break;
                            case CLASSPATH_LAYOUT_TYPE_REPOSITORY:
                                selectedLayout =
                                        config.isUseUniqueVersions() ? REPOSITORY_LAYOUT : REPOSITORY_LAYOUT_NONUNIQUE;
                                break;
                            case CLASSPATH_LAYOUT_TYPE_CUSTOM:
                                if (layout == null) {
                                    throw new MavenArchiverException(CLASSPATH_LAYOUT_TYPE_CUSTOM
                                            + " layout type was declared, but custom layout expression was not"
                                            + " specified. Check your <archive><manifest><customLayout/>"
                                            + " element.");
                                }
                                selectedLayout = layout;
                                break;
                            default:
                                throw new MavenArchiverException("Unknown classpath layout type: '" + layoutType
                                        + "'. Check your <archive><manifest><layoutType/> element.");
                        }

                        String interpolated = interpolator.interpolate(
                                selectedLayout, key -> resolveArtifactExpression(dependency, key));
                        classpath.append(interpolated);
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

    /**
     * Resolves an artifact expression key to a value from the given dependency.
     * Keys may optionally be prefixed with {@code "artifact."}.
     *
     * @param dep the dependency to extract values from
     * @param key the expression key (e.g. "artifact.artifactId" or "artifactId")
     * @return the resolved value, or {@code null} if the key is unknown
     */
    private static String resolveArtifactExpression(Dependency dep, String key) {
        String k = key.startsWith("artifact.") ? key.substring(9) : key;
        return switch (k) {
            case "artifactId" -> dep.getArtifactId();
            case "groupId" -> dep.getGroupId();
            case "groupIdPath" -> dep.getGroupId().replace('.', '/');
            case "version" -> dep.getVersion().toString();
            case "baseVersion" -> dep.getBaseVersion().toString();
            case "extension" -> dep.getExtension();
            case "classifier" -> dep.getClassifier() != null ? dep.getClassifier() : "";
            case "dashClassifier", "dashClassifier?" -> {
                String c = dep.getClassifier();
                yield (c != null && !c.isEmpty()) ? "-" + c : "";
            }
            case "type" -> dep.getType() != null ? dep.getType().id() : dep.getExtension();
            default -> null;
        };
    }

    private void handleImplementationEntries(Project project, Map<String, String> entries, Manifest m) {
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

    private void handleSpecificationEntries(Project project, Map<String, String> entries, Manifest m) {
        addManifestAttribute(
                m, entries, "Specification-Title", project.getModel().getName());

        String version = project.getPomArtifact().getVersion().toString();
        Matcher matcher = SPECIFICATION_VERSION_PATTERN.matcher(version);
        if (matcher.matches()) {
            String specVersion = matcher.group(1);
            addManifestAttribute(m, entries, "Specification-Version", specVersion);
        }

        if (project.getModel().getOrganization() != null) {
            addManifestAttribute(
                    m,
                    entries,
                    "Specification-Vendor",
                    project.getModel().getOrganization().getName());
        }
    }

    private void addCustomEntries(Manifest m, Map<String, String> entries, ManifestConfiguration config) {
        if (config.getPackageName() != null) {
            addManifestAttribute(m, entries, "Package", config.getPackageName());
        }
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
     * <p>Since 3.6.4, if not configured or disabled, the {@code SOURCE_DATE_EPOCH} environment variable is used as
     * a fallback value, to ease forcing Reproducible Build externally when the build has not enabled it natively in POM.
     *
     * @param outputTimestamp the value of {@code ${project.build.outputTimestamp}} (may be {@code null})
     * @return the parsed timestamp as an {@code Optional<Instant>}, {@code empty} if input is {@code null} or input
     *         contains only 1 character (not a number)
     * @throws IllegalArgumentException if the outputTimestamp is neither ISO 8601 nor an integer
     * @see <a href="https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=74682318">Maven Wiki "Reproducible/Verifiable
     *      Builds"</a>
     * @since 3.6.0
     */
    public static Optional<Instant> parseBuildOutputTimestamp(String outputTimestamp) {
        // Fail fast on null and no timestamp configured (1 character configuration is useful to override
        // a full value during pom inheritance)
        if (outputTimestamp == null || (outputTimestamp.length() < 2 && !isNumeric(outputTimestamp))) {
            // Reproducible Builds not configured or disabled => fallback to SOURCE_DATE_EPOCH env
            outputTimestamp = System.getenv("SOURCE_DATE_EPOCH");
            if (outputTimestamp == null) {
                return Optional.empty();
            }
        }

        // Number representing seconds since the epoch
        if (isNumeric(outputTimestamp)) {
            Instant date = Instant.ofEpochSecond(Long.parseLong(outputTimestamp));
            return Optional.of(clampToDateMin(date, outputTimestamp));
        }

        try {
            // Parse the date in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'.
            Instant date = OffsetDateTime.parse(outputTimestamp)
                    .withOffsetSameInstant(ZoneOffset.UTC)
                    .truncatedTo(ChronoUnit.SECONDS)
                    .toInstant();
            return Optional.of(clampToDateMin(date, outputTimestamp));
        } catch (DateTimeParseException pe) {
            throw new IllegalArgumentException(
                    "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'", pe);
        }
    }

    private static boolean isNumeric(String str) {
        if (str.isEmpty()) {
            return false;
        }

        int start = 0;
        if (str.charAt(0) == '-') {
            if (str.length() == 1) {
                return false; // lone "-" is not numeric (used as disable sentinel in POM inheritance)
            }
            start = 1;
        }

        for (int i = start; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }

        return true;
    }

    private static Instant clampToDateMin(Instant date, String originalInput) {
        if (!date.isBefore(Instant.EPOCH) && date.isBefore(DATE_MIN)) {
            LOGGER.warn(
                    "Timestamp '{}' (parsed from '{}') is before the minimum date for ZIP/JAR entries."
                            + " Clamping to DATE_MIN ({}). See https://github.com/apache/maven-jar-plugin/issues/595",
                    date,
                    originalInput,
                    DATE_MIN);
            return DATE_MIN;
        }
        return date;
    }

    private void handleDefaultEntries(Project project, Manifest m, Map<String, String> entries) {
        String createdBy = this.createdBy;
        if (createdBy == null) {
            createdBy = createdBy(CREATED_BY, "org.apache.maven", "maven-archiver");
        }
        addManifestAttribute(m, entries, "Created-By", createdBy);
        String javaVersion = BuildHelper.discoverJavaRelease(project.getModel());
        if (javaVersion != null) {
            addManifestAttribute(m, entries, "Java-Version", javaVersion);
        }
        if (buildJdkSpecDefaultEntry) {
            addManifestAttribute(m, entries, "Build-Jdk-Spec", System.getProperty("java.specification.version"));
        }
    }

    private void handleBuildEnvironmentEntries(Session session, Manifest m, Map<String, String> entries) {
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
        String resourcePath = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        final Properties properties =
                loadOptionalProperties(MavenArchiver.class.getResourceAsStream(resourcePath), resourcePath);

        return properties.getProperty("version");
    }

    private static Properties loadOptionalProperties(final InputStream inputStream, String resourcePath) {
        Properties properties = new Properties();
        if (inputStream != null) {
            try (InputStream in = inputStream) {
                properties.load(in);
            } catch (IllegalArgumentException | IOException ex) {
                LOGGER.warn(
                        "Unable to read Maven Archiver properties from '{}': {}. "
                                + "The 'Created-By' manifest entry will not include the version. "
                                + "This may indicate a corrupt artifact in your local repository; "
                                + "try running 'mvn dependency:purge-local-repository' to re-download it.",
                        resourcePath,
                        ex.getMessage(),
                        ex);
            }
        }
        return properties;
    }
}
