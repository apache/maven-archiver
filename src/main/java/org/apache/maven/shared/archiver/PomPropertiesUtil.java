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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.codehaus.plexus.archiver.Archiver;

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

    private boolean sameContents(Properties props, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }

        Properties fileProps = loadPropertiesFile(file);
        return fileProps.equals(props);
    }

    private void createPropertiesFile(Properties properties, Path outputFile, boolean forceCreation)
            throws IOException {
        Path outputDir = outputFile.getParent();
        if (outputDir != null && !Files.isDirectory(outputDir)) {
            Files.createDirectories(outputDir);
        }
        if (!forceCreation && sameContents(properties, outputFile)) {
            return;
        }

        try (PrintWriter pw = new PrintWriter(outputFile.toFile(), StandardCharsets.ISO_8859_1.name());
                StringWriter sw = new StringWriter()) {

            properties.store(sw, null);

            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new StringReader(sw.toString()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("#")) {
                        lines.add(line);
                    }
                }
            }

            Collections.sort(lines);
            for (String l : lines) {
                pw.println(l);
            }
        }
    }

    /**
     * Creates the pom.properties file.
     *
     * @param session {@link org.apache.maven.api.Session}
     * @param project {@link org.apache.maven.api.Project}
     * @param archiver {@link org.codehaus.plexus.archiver.Archiver}
     * @param customPomPropertiesFile optional custom pom properties file
     * @param pomPropertiesFile The pom properties file.
     * @param forceCreation force creation true/false
     * @throws org.codehaus.plexus.archiver.ArchiverException archiver exception.
     * @throws java.io.IOException IO exception.
     */
    public void createPomProperties(
            Session session,
            Project project,
            Archiver archiver,
            Path customPomPropertiesFile,
            Path pomPropertiesFile,
            boolean forceCreation)
            throws IOException {
        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();
        createPomProperties(
                session,
                groupId,
                artifactId,
                version,
                archiver,
                customPomPropertiesFile,
                pomPropertiesFile,
                forceCreation);
    }

    // CHECKSTYLE_OFF: ParameterNumber
    public void createPomProperties(
            Session session,
            String groupId,
            String artifactId,
            String version,
            Archiver archiver,
            Path customPomPropertiesFile,
            Path pomPropertiesFile,
            boolean forceCreation)
            // CHECKSTYLE_ON
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

        createPropertiesFile(p, pomPropertiesFile, forceCreation);

        archiver.addFile(
                pomPropertiesFile.toFile(), "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
    }
}
