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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.maven.api.Project;
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
    private Properties loadPropertiesFile(Path file) throws IOException {
        Properties fileProps = new Properties();
        try (InputStream istream = Files.newInputStream(file)) {
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
        String output = baos.toString(StandardCharsets.ISO_8859_1)
                .lines()
                .filter(line -> !line.startsWith("#"))
                .sorted()
                .collect(Collectors.joining("\n", "", "\n")); // system independent new line
        try (Writer writer = new CachingWriter(outputFile, StandardCharsets.ISO_8859_1)) {
            writer.write(output);
        }
    }

    /**
     * Creates the pom.properties file.
     *
     * @param project {@link org.apache.maven.api.Project}
     * @param archiver {@link org.codehaus.plexus.archiver.Archiver}
     * @param customPomPropertiesFile optional custom pom properties file
     * @param pomPropertiesFile the pom properties file
     * @throws java.io.IOException IO exception
     * @throws org.codehaus.plexus.archiver.ArchiverException archiver exception
     */
    public void createPomProperties(
            Project project, Archiver archiver, Path customPomPropertiesFile, Path pomPropertiesFile)
            throws IOException {
        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();
        createPomProperties(groupId, artifactId, version, archiver, customPomPropertiesFile, pomPropertiesFile);
    }

    public void createPomProperties(
            String groupId,
            String artifactId,
            String version,
            Archiver archiver,
            Path customPomPropertiesFile,
            Path pomPropertiesFile)
            throws IOException {
        Properties p;

        if (customPomPropertiesFile != null) {
            p = loadPropertiesFile(customPomPropertiesFile);
        } else {
            p = new Properties();
        }

        p.setProperty("groupId", groupId);

        p.setProperty("artifactId", artifactId);

        p.setProperty("version", version);

        createPropertiesFile(p, pomPropertiesFile);

        archiver.addFile(
                pomPropertiesFile.toFile(), "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
    }
}
