package org.apache.maven.archiver;

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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.io.IOUtil;
import org.codehaus.plexus.archiver.Archiver;

/**
 * This class is responsible for creating the <code>pom.properties</code> file
 * in <code>META-INF/maven/${groupId}/${artifactId}</code>.
 */
public class PomPropertiesUtil
{
    private Properties loadPropertiesFile( File file )
        throws IOException
    {
        Properties fileProps = new Properties();
        InputStream istream = null;
        try
        {
            istream = new FileInputStream( file );
            fileProps.load( istream );
            istream.close();
            istream = null;
            return fileProps;
        }
        finally
        {
            IOUtil.close( istream );
        }
    }

    private boolean sameContents( Properties props, File file )
        throws IOException
    {
        if ( !file.isFile() )
        {
            return false;
        }

        Properties fileProps = loadPropertiesFile( file );
        return fileProps.equals( props );
    }

    private void createPropertiesFile( MavenSession session, Properties properties, File outputFile,
                                       boolean forceCreation )
        throws IOException
    {
        File outputDir = outputFile.getParentFile();
        if ( outputDir != null && !outputDir.isDirectory() && !outputDir.mkdirs() )
        {
            throw new IOException( "Failed to create directory: " + outputDir );
        }
        if ( !forceCreation && sameContents( properties, outputFile ) )
        {
            return;
        }
        PrintWriter pw = new PrintWriter( outputFile, "ISO-8859-1" );
        try
        {
            StringWriter sw = new StringWriter();
            properties.store( sw, null );

            BufferedReader r = new BufferedReader( new StringReader( sw.toString() ) );

            List<String> lines = new ArrayList<String>();
            String line;
            while ( ( line = r.readLine() ) != null )
            {
                if ( !line.startsWith( "#" ) )
                {
                    lines.add( line );
                }
            }

            r.close();
            r = null;
            sw.close();
            sw = null;

            Collections.sort( lines );
            for ( String l : lines )
            {
                pw.println( l );
            }

            pw.close();
            pw = null;
        }
        finally
        {
            IOUtil.close( pw );
        }
    }

    /**
     * Creates the pom.properties file.
     * @param session {@link MavenSession}
     * @param project {@link MavenProject}
     * @param archiver {@link Archiver}
     * @param customPomPropertiesFile optional custom pom properties file
     * @param pomPropertiesFile The pom properties file.
     * @param forceCreation force creation true/false
     * @throws org.codehaus.plexus.archiver.ArchiverException archiver exception.
     * @throws IOException IO exception.
     */
    public void createPomProperties( MavenSession session, MavenProject project, Archiver archiver,
                                     File customPomPropertiesFile, File pomPropertiesFile, boolean forceCreation )
        throws IOException
    {
        final String groupId = project.getGroupId();
        final String artifactId = project.getArtifactId();
        final String version = project.getVersion();

        Properties p;

        if ( customPomPropertiesFile != null )
        {
            p = loadPropertiesFile( customPomPropertiesFile );
        }
        else
        {
            p = new Properties();
        }

        p.setProperty( "groupId", groupId );

        p.setProperty( "artifactId", artifactId );

        p.setProperty( "version", version );

        createPropertiesFile( session, p, pomPropertiesFile, forceCreation );

        archiver.addFile( pomPropertiesFile, "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties" );
    }
}
