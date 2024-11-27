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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.util.io.CachingWriter;

/**
 * This class is responsible for creating the <code>pom.properties</code> file
 * in <code>META-INF/maven/${groupId}/${artifactId}</code>.
 *
 * @author slachiewicz
 * @version $Id: $Id
 */
public class PomPropertiesUtil {
    private Properties loadPropertiesFile(File file) throws IOException {
        Properties fileProps = new Properties();
        try (InputStream istream = Files.newInputStream(file.toPath())) {
            fileProps.load(istream);
            return fileProps;
        }
    }

    private void createPropertiesFile(Properties properties, Path outputFile) throws IOException {
        Path outputDir = outputFile.getParent();
        if (outputDir != null) {
            Files.createDirectories(outputDir);
        }
        // For reproducible builds, sort the properties and drop comments.
        // The java.util.Properties class doesn't guarantee order so we have
        // to write the file using a Writer.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        properties.store(baos, null);
        // The encoding can be either UTF-8 or ISO-8859-1, as any non ascii character
        // is transformed into a \\uxxxx sequence anyway
        String output = Arrays.stream(
                        baos.toString(StandardCharsets.ISO_8859_1.name()).split("\\r?\\n"))
                .filter(line -> !line.startsWith("#"))
                .sorted()
                .collect(Collectors.joining("\n", "", "\n"));
        try (Writer writer = new CachingWriter(outputFile, StandardCharsets.ISO_8859_1)) {
            writer.write(output);
        }
    }

    /**
     * Creates the pom.properties file.
     *
     * @param session {@link org.apache.maven.execution.MavenSession}
     * @param project {@link org.apache.maven.project.MavenProject}
     * @param archiver {@link org.codehaus.plexus.archiver.Archiver}
     * @param customPomPropertiesFile optional custom pom properties file
     * @param pomPropertiesFile The pom properties file.
     * @throws org.codehaus.plexus.archiver.ArchiverException archiver exception.
     * @throws java.io.IOException IO exception.
     * @deprecated please use {@link #createPomProperties(MavenProject, Archiver, File, File, boolean)}
     */
    @Deprecated
    public void createPomProperties(
            MavenSession session,
            MavenProject project,
            Archiver archiver,
            File customPomPropertiesFile,
            File pomPropertiesFile,
            boolean forceCreation)
            throws IOException {
        createPomProperties(project, archiver, customPomPropertiesFile, pomPropertiesFile, forceCreation);
    }

    /**
     * Creates the pom.properties file.
     *
     * @param project                 {@link org.apache.maven.project.MavenProject}
     * @param archiver                {@link org.codehaus.plexus.archiver.Archiver}
     * @param customPomPropertiesFile optional custom pom properties file
     * @param pomPropertiesFile       The pom properties file.
     * @param forceCreation           force creation true/false
     * @throws org.codehaus.plexus.archiver.ArchiverException archiver exception.
     * @throws java.io.IOException                            IO exception.
     */
    public void createPomProperties(
            MavenProject project,
            Archiver archiver,
            File customPomPropertiesFile,
            File pomPropertiesFile,
            boolean forceCreation)
            throws IOException {
        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();

        Properties p;

        if (customPomPropertiesFile != null) {
            p = loadPropertiesFile(customPomPropertiesFile);
        } else {
            p = new Properties();
        }

        p.setProperty("groupId", groupId);

        p.setProperty("artifactId", artifactId);

        p.setProperty("version", version);

        createPropertiesFile(p, pomPropertiesFile.toPath());

        archiver.addFile(pomPropertiesFile, "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
    }
}
