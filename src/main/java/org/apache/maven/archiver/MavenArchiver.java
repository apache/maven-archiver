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

import javax.lang.model.SourceVersion;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.OverConstrainedVersionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
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
import org.codehaus.plexus.util.StringUtils;

import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_CUSTOM;
import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_REPOSITORY;
import static org.apache.maven.archiver.ManifestConfiguration.CLASSPATH_LAYOUT_TYPE_SIMPLE;

/**
 * <p>MavenArchiver class.</p>
 *
 * @author <a href="evenisse@apache.org">Emmanuel Venisse</a>
 * @author kama
 * @version $Id: $Id
 */
public class MavenArchiver
{

    private static final String CREATED_BY = "Maven Archiver";

    /**
     * The simply layout.
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

    private static final Instant DATE_MIN = Instant.parse( "1980-01-01T00:00:02Z" );

    private static final Instant DATE_MAX = Instant.parse( "2099-12-31T23:59:59Z" );

    private static final List<String> ARTIFACT_EXPRESSION_PREFIXES;

    static
    {
        List<String> artifactExpressionPrefixes = new ArrayList<>();
        artifactExpressionPrefixes.add( "artifact." );

        ARTIFACT_EXPRESSION_PREFIXES = artifactExpressionPrefixes;
    }

    static boolean isValidModuleName( String name )
    {
        return SourceVersion.isName( name );
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
     * @param config the MavenArchiveConfiguration
     * @return the {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws org.codehaus.plexus.archiver.jar.ManifestException in case of a failure
     * @throws org.apache.maven.artifact.DependencyResolutionRequiredException resolution failure
     */
    public Manifest getManifest( MavenSession session, MavenProject project, MavenArchiveConfiguration config )
        throws ManifestException, DependencyResolutionRequiredException
    {
        boolean hasManifestEntries = !config.isManifestEntriesEmpty();
        Map<String, String> entries =
            hasManifestEntries ? config.getManifestEntries() : Collections.emptyMap();

        Manifest manifest = getManifest( session, project, config.getManifest(), entries );

        // any custom manifest entries in the archive configuration manifest?
        if ( hasManifestEntries )
        {

            for ( Map.Entry<String, String> entry : entries.entrySet() )
            {
                String key = entry.getKey();
                String value = entry.getValue();
                Manifest.Attribute attr = manifest.getMainSection().getAttribute( key );
                if ( key.equals( Attributes.Name.CLASS_PATH.toString() ) && attr != null )
                {
                    // Merge the user-supplied Class-Path value with the programmatically
                    // created Class-Path. Note that the user-supplied value goes first
                    // so that resources there will override any in the standard Class-Path.
                    attr.setValue( value + " " + attr.getValue() );
                }
                else
                {
                    addManifestAttribute( manifest, key, value );
                }
            }
        }

        // any custom manifest sections in the archive configuration manifest?
        if ( !config.isManifestSectionsEmpty() )
        {
            for ( ManifestSection section : config.getManifestSections() )
            {
                Manifest.Section theSection = new Manifest.Section();
                theSection.setName( section.getName() );

                if ( !section.isManifestEntriesEmpty() )
                {
                    Map<String, String> sectionEntries = section.getManifestEntries();

                    for ( Map.Entry<String, String> entry : sectionEntries.entrySet() )
                    {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        Manifest.Attribute attr = new Manifest.Attribute( key, value );
                        theSection.addConfiguredAttribute( attr );
                    }
                }

                manifest.addConfiguredSection( theSection );
            }
        }

        return manifest;
    }

    /**
     * Return a pre-configured manifest.
     *
     * @param project {@link org.apache.maven.project.MavenProject}
     * @param config {@link org.apache.maven.archiver.ManifestConfiguration}
     * @return {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws org.codehaus.plexus.archiver.jar.ManifestException Manifest exception.
     * @throws org.apache.maven.artifact.DependencyResolutionRequiredException Dependency resolution exception.
     */
    // TODO Add user attributes list and user groups list
    public Manifest getManifest( MavenProject project, ManifestConfiguration config )
        throws ManifestException, DependencyResolutionRequiredException
    {
        return getManifest( null, project, config, Collections.emptyMap() );
    }

    /**
     * <p>getManifest.</p>
     *
     * @param mavenSession {@link org.apache.maven.execution.MavenSession}
     * @param project      {@link org.apache.maven.project.MavenProject}
     * @param config       {@link org.apache.maven.archiver.ManifestConfiguration}
     * @return {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws org.codehaus.plexus.archiver.jar.ManifestException              the manifest exception
     * @throws org.apache.maven.artifact.DependencyResolutionRequiredException the dependency resolution required
     *                                                                         exception
     */
    public Manifest getManifest( MavenSession mavenSession, MavenProject project, ManifestConfiguration config )
        throws ManifestException, DependencyResolutionRequiredException
    {
        return getManifest( mavenSession, project, config, Collections.emptyMap() );
    }

    private void addManifestAttribute( Manifest manifest, Map<String, String> map, String key, String value )
        throws ManifestException
    {
        if ( map.containsKey( key ) )
        {
            return; // The map value will be added later
        }
        addManifestAttribute( manifest, key, value );
    }

    private void addManifestAttribute( Manifest manifest, String key, String value )
        throws ManifestException
    {
        if ( !StringUtils.isEmpty( value ) )
        {
            Manifest.Attribute attr = new Manifest.Attribute( key, value );
            manifest.addConfiguredAttribute( attr );
        }
        else
        {
            // if the value is empty, create an entry with an empty string
            // to prevent null print in the manifest file
            Manifest.Attribute attr = new Manifest.Attribute( key, "" );
            manifest.addConfiguredAttribute( attr );
        }
    }

    /**
     * <p>getManifest.</p>
     *
     * @param session {@link org.apache.maven.execution.MavenSession}
     * @param project {@link org.apache.maven.project.MavenProject}
     * @param config  {@link org.apache.maven.archiver.ManifestConfiguration}
     * @param entries The entries.
     * @return {@link org.codehaus.plexus.archiver.jar.Manifest}
     * @throws org.codehaus.plexus.archiver.jar.ManifestException              the manifest exception
     * @throws org.apache.maven.artifact.DependencyResolutionRequiredException the dependency resolution required
     *                                                                         exception
     */
    protected Manifest getManifest( MavenSession session, MavenProject project, ManifestConfiguration config,
                                    Map<String, String> entries )
                                        throws ManifestException, DependencyResolutionRequiredException
    {
        // TODO: Should we replace "map" with a copy? Note, that we modify it!

        Manifest m = new Manifest();

        if ( config.isAddDefaultEntries() )
        {
            handleDefaultEntries( m, entries );
        }


        if ( config.isAddBuildEnvironmentEntries() )
        {
            handleBuildEnvironmentEntries( session, m, entries );
        }

        if ( config.isAddClasspath() )
        {
            StringBuilder classpath = new StringBuilder();

            List<String> artifacts = project.getRuntimeClasspathElements();
            String classpathPrefix = config.getClasspathPrefix();
            String layoutType = config.getClasspathLayoutType();
            String layout = config.getCustomClasspathLayout();

            Interpolator interpolator = new StringSearchInterpolator();

            for ( String artifactFile : artifacts )
            {
                File f = new File( artifactFile );
                if ( f.getAbsoluteFile().isFile() )
                {
                    Artifact artifact = findArtifactWithFile( project.getArtifacts(), f );

                    if ( classpath.length() > 0 )
                    {
                        classpath.append( " " );
                    }
                    classpath.append( classpathPrefix );

                    // NOTE: If the artifact or layout type (from config) is null, give up and use the file name by
                    // itself.
                    if ( artifact == null || layoutType == null )
                    {
                        classpath.append( f.getName() );
                    }
                    else
                    {
                        List<ValueSource> valueSources = new ArrayList<>();

                        handleExtraExpression( artifact, valueSources );

                        for ( ValueSource vs : valueSources )
                        {
                            interpolator.addValueSource( vs );
                        }

                        RecursionInterceptor recursionInterceptor =
                            new PrefixAwareRecursionInterceptor( ARTIFACT_EXPRESSION_PREFIXES );

                        try
                        {
                            switch ( layoutType )
                            {
                                case CLASSPATH_LAYOUT_TYPE_SIMPLE:
                                    if ( config.isUseUniqueVersions() )
                                    {
                                        classpath.append( interpolator.interpolate( SIMPLE_LAYOUT,
                                                recursionInterceptor ) );
                                    }
                                    else
                                    {
                                        classpath.append( interpolator.interpolate( SIMPLE_LAYOUT_NONUNIQUE,
                                                recursionInterceptor ) );
                                    }
                                    break;
                                case CLASSPATH_LAYOUT_TYPE_REPOSITORY:
                                    // we use layout /$groupId[0]/../${groupId[n]/$artifactId/$version/{fileName}
                                    // here we must find the Artifact in the project Artifacts
                                    // to create the maven layout
                                    if ( config.isUseUniqueVersions() )
                                    {
                                        classpath.append( interpolator.interpolate( REPOSITORY_LAYOUT,
                                                recursionInterceptor ) );
                                    }
                                    else
                                    {
                                        classpath.append( interpolator.interpolate( REPOSITORY_LAYOUT_NONUNIQUE,
                                                recursionInterceptor ) );
                                    }
                                    break;
                                case CLASSPATH_LAYOUT_TYPE_CUSTOM:
                                    if ( layout == null )
                                    {
                                        throw new ManifestException( CLASSPATH_LAYOUT_TYPE_CUSTOM
                                                + " layout type was declared, but custom layout expression was not"
                                                + " specified. Check your <archive><manifest><customLayout/>"
                                                + " element." );
                                    }

                                    classpath.append( interpolator.interpolate( layout, recursionInterceptor ) );
                                    break;
                                default:
                                    throw new ManifestException( "Unknown classpath layout type: '" + layoutType
                                            + "'. Check your <archive><manifest><layoutType/> element." );
                            }
                        }
                        catch ( InterpolationException e )
                        {
                            ManifestException error =
                                new ManifestException( "Error interpolating artifact path for classpath entry: "
                                    + e.getMessage() );

                            error.initCause( e );
                            throw error;
                        }
                        finally
                        {
                            for ( ValueSource vs : valueSources )
                            {
                                interpolator.removeValuesSource( vs );
                            }
                        }
                    }
                }
            }

            if ( classpath.length() > 0 )
            {
                // Class-Path is special and should be added to manifest even if
                // it is specified in the manifestEntries section
                addManifestAttribute( m, "Class-Path", classpath.toString() );
            }
        }

        if ( config.isAddDefaultSpecificationEntries() )
        {
            handleSpecificationEntries( project, entries, m );
        }

        if ( config.isAddDefaultImplementationEntries() )
        {
            handleImplementationEntries( project, entries, m );
        }

        String mainClass = config.getMainClass();
        if ( mainClass != null && !"".equals( mainClass ) )
        {
            addManifestAttribute( m, entries, "Main-Class", mainClass );
        }

        if ( config.isAddExtensions() )
        {
            handleExtensions( project, entries, m );
        }

        addCustomEntries( m, entries, config );

        return m;
    }

    private void handleExtraExpression( Artifact artifact, List<ValueSource> valueSources )
    {
        valueSources.add( new PrefixedObjectValueSource( ARTIFACT_EXPRESSION_PREFIXES, artifact,
                                                         true ) );
        valueSources.add( new PrefixedObjectValueSource( ARTIFACT_EXPRESSION_PREFIXES,
                                                         artifact.getArtifactHandler(), true ) );

        Properties extraExpressions = new Properties();
        // FIXME: This query method SHOULD NOT affect the internal
        // state of the artifact version, but it does.
        if ( !artifact.isSnapshot() )
        {
            extraExpressions.setProperty( "baseVersion", artifact.getVersion() );
        }

        extraExpressions.setProperty( "groupIdPath", artifact.getGroupId().replace( '.', '/' ) );
        if ( StringUtils.isNotEmpty( artifact.getClassifier() ) )
        {
            extraExpressions.setProperty( "dashClassifier", "-" + artifact.getClassifier() );
            extraExpressions.setProperty( "dashClassifier?", "-" + artifact.getClassifier() );
        }
        else
        {
            extraExpressions.setProperty( "dashClassifier", "" );
            extraExpressions.setProperty( "dashClassifier?", "" );
        }
        valueSources.add( new PrefixedPropertiesValueSource( ARTIFACT_EXPRESSION_PREFIXES,
                                                             extraExpressions, true ) );
    }

    private void handleExtensions( MavenProject project, Map<String, String> entries, Manifest m )
        throws ManifestException
    {
        // TODO: this is only for applets - should we distinguish them as a packaging?
        StringBuilder extensionsList = new StringBuilder();
        Set<Artifact> artifacts = project.getArtifacts();

        for ( Artifact artifact : artifacts )
        {
            if ( !Artifact.SCOPE_TEST.equals( artifact.getScope() ) )
            {
                if ( "jar".equals( artifact.getType() ) )
                {
                    if ( extensionsList.length() > 0 )
                    {
                        extensionsList.append( " " );
                    }
                    extensionsList.append( artifact.getArtifactId() );
                }
            }
        }

        if ( extensionsList.length() > 0 )
        {
            addManifestAttribute( m, entries, "Extension-List", extensionsList.toString() );
        }

        for ( Artifact artifact : artifacts )
        {
            // TODO: the correct solution here would be to have an extension type, and to read
            // the real extension values either from the artifact's manifest or some part of the POM
            if ( "jar".equals( artifact.getType() ) )
            {
                String artifactId = artifact.getArtifactId().replace( '.', '_' );
                String ename = artifactId + "-Extension-Name";
                addManifestAttribute( m, entries, ename, artifact.getArtifactId() );
                String iname = artifactId + "-Implementation-Version";
                addManifestAttribute( m, entries, iname, artifact.getVersion() );

                if ( artifact.getRepository() != null )
                {
                    iname = artifactId + "-Implementation-URL";
                    String url = artifact.getRepository().getUrl() + "/" + artifact;
                    addManifestAttribute( m, entries, iname, url );
                }
            }
        }
    }

    private void handleImplementationEntries( MavenProject project, Map<String, String> entries, Manifest m )
        throws ManifestException
    {
        addManifestAttribute( m, entries, "Implementation-Title", project.getName() );
        addManifestAttribute( m, entries, "Implementation-Version", project.getVersion() );

        if ( project.getOrganization() != null )
        {
            addManifestAttribute( m, entries, "Implementation-Vendor", project.getOrganization().getName() );
        }
    }

    private void handleSpecificationEntries( MavenProject project, Map<String, String> entries, Manifest m )
        throws ManifestException
    {
        addManifestAttribute( m, entries, "Specification-Title", project.getName() );

        try
        {
            ArtifactVersion version = project.getArtifact().getSelectedVersion();
            String specVersion = String.format( "%s.%s", version.getMajorVersion(), version.getMinorVersion() );
            addManifestAttribute( m, entries, "Specification-Version", specVersion );
        }
        catch ( OverConstrainedVersionException e )
        {
            throw new ManifestException( "Failed to get selected artifact version to calculate"
                + " the specification version: " + e.getMessage() );
        }

        if ( project.getOrganization() != null )
        {
            addManifestAttribute( m, entries, "Specification-Vendor", project.getOrganization().getName() );
        }
    }

    private void addCustomEntries( Manifest m, Map<String, String> entries, ManifestConfiguration config )
        throws ManifestException
    {
        /*
         * TODO: rethink this, it wasn't working Artifact projectArtifact = project.getArtifact(); if (
         * projectArtifact.isSnapshot() ) { Manifest.Attribute buildNumberAttr = new Manifest.Attribute( "Build-Number",
         * "" + project.getSnapshotDeploymentBuildNumber() ); m.addConfiguredAttribute( buildNumberAttr ); }
         */
        if ( config.getPackageName() != null )
        {
            addManifestAttribute( m, entries, "Package", config.getPackageName() );
        }
    }

    /**
     * <p>Getter for the field <code>archiver</code>.</p>
     *
     * @return {@link org.codehaus.plexus.archiver.jar.JarArchiver}
     */
    public JarArchiver getArchiver()
    {
        return archiver;
    }

    /**
     * <p>Setter for the field <code>archiver</code>.</p>
     *
     * @param archiver {@link org.codehaus.plexus.archiver.jar.JarArchiver}
     */
    public void setArchiver( JarArchiver archiver )
    {
        this.archiver = archiver;
    }

    /**
     * <p>setOutputFile.</p>
     *
     * @param outputFile Set output file.
     */
    public void setOutputFile( File outputFile )
    {
        archiveFile = outputFile;
    }

    /**
     * <p>createArchive.</p>
     *
     * @param session {@link org.apache.maven.execution.MavenSession}
     * @param project {@link org.apache.maven.project.MavenProject}
     * @param archiveConfiguration {@link org.apache.maven.archiver.MavenArchiveConfiguration}
     * @throws org.codehaus.plexus.archiver.ArchiverException Archiver Exception.
     * @throws org.codehaus.plexus.archiver.jar.ManifestException Manifest Exception.
     * @throws java.io.IOException IO Exception.
     * @throws org.apache.maven.artifact.DependencyResolutionRequiredException Dependency resolution exception.
     */
    public void createArchive( MavenSession session, MavenProject project,
                               MavenArchiveConfiguration archiveConfiguration )
                                   throws ManifestException, IOException,
                                   DependencyResolutionRequiredException
    {
        // we have to clone the project instance so we can write out the pom with the deployment version,
        // without impacting the main project instance...
        MavenProject workingProject = project.clone();

        boolean forced = archiveConfiguration.isForced();
        if ( archiveConfiguration.isAddMavenDescriptor() )
        {
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

            if ( workingProject.getArtifact().isSnapshot() )
            {
                workingProject.setVersion( workingProject.getArtifact().getVersion() );
            }

            String groupId = workingProject.getGroupId();

            String artifactId = workingProject.getArtifactId();

            archiver.addFile( project.getFile(), "META-INF/maven/" + groupId + "/" + artifactId + "/pom.xml" );

            // ----------------------------------------------------------------------
            // Create pom.properties file
            // ----------------------------------------------------------------------

            File customPomPropertiesFile = archiveConfiguration.getPomPropertiesFile();
            File dir = new File( workingProject.getBuild().getDirectory(), "maven-archiver" );
            File pomPropertiesFile = new File( dir, "pom.properties" );

            new PomPropertiesUtil().createPomProperties( session, workingProject, archiver,
                customPomPropertiesFile, pomPropertiesFile, forced );
        }

        // ----------------------------------------------------------------------
        // Create the manifest
        // ----------------------------------------------------------------------

        archiver.setMinimalDefaultManifest( true );

        File manifestFile = archiveConfiguration.getManifestFile();

        if ( manifestFile != null )
        {
            archiver.setManifest( manifestFile );
        }

        Manifest manifest = getManifest( session, workingProject, archiveConfiguration );

        // Configure the jar
        archiver.addConfiguredManifest( manifest );

        archiver.setCompress( archiveConfiguration.isCompress() );

        archiver.setRecompressAddedZips( archiveConfiguration.isRecompressAddedZips() );

        archiver.setIndex( archiveConfiguration.isIndex() );

        archiver.setDestFile( archiveFile );

        // make the archiver index the jars on the classpath, if we are adding that to the manifest
        if ( archiveConfiguration.getManifest().isAddClasspath() )
        {
            List<String> artifacts = project.getRuntimeClasspathElements();
            for ( String artifact : artifacts )
            {
                File f = new File( artifact );
                archiver.addConfiguredIndexJars( f );
            }
        }

        archiver.setForced( forced );
        if ( !archiveConfiguration.isForced() && archiver.isSupportingForced() )
        {
            // TODO Should issue a warning here, but how do we get a logger?
            // TODO getLog().warn(
            // "Forced build is disabled, but disabling the forced mode isn't supported by the archiver." );
        }

        String automaticModuleName = manifest.getMainSection().getAttributeValue( "Automatic-Module-Name" );
        if ( automaticModuleName != null )
        {
            if ( !isValidModuleName( automaticModuleName ) )
            {
                throw new ManifestException( "Invalid automatic module name: '" + automaticModuleName + "'" );
            }
        }

        // create archive
        archiver.createArchive();
    }

    private void handleDefaultEntries( Manifest m, Map<String, String> entries )
        throws ManifestException
    {
         String createdBy = this.createdBy;
         if ( createdBy == null )
         {
             createdBy = createdBy( CREATED_BY, "org.apache.maven", "maven-archiver" );
         }
         addManifestAttribute( m, entries, "Created-By", createdBy );
         if ( buildJdkSpecDefaultEntry )
         {
             addManifestAttribute( m, entries, "Build-Jdk-Spec", System.getProperty( "java.specification.version" ) );
         }
    }

    private void handleBuildEnvironmentEntries( MavenSession session, Manifest m, Map<String, String> entries )
        throws ManifestException
    {
        addManifestAttribute( m, entries, "Build-Tool",
            session != null ? session.getSystemProperties().getProperty( "maven.build.version" ) : "Apache Maven" );
        addManifestAttribute( m, entries, "Build-Jdk", String.format( "%s (%s)", System.getProperty( "java.version" ),
            System.getProperty( "java.vendor" ) ) );
        addManifestAttribute( m, entries, "Build-Os", String.format( "%s (%s; %s)", System.getProperty( "os.name" ),
            System.getProperty( "os.version" ), System.getProperty( "os.arch" ) ) );
    }

    private Artifact findArtifactWithFile( Set<Artifact> artifacts, File file )
    {
        for ( Artifact artifact : artifacts )
        {
            // normally not null but we can check
            if ( artifact.getFile() != null )
            {
                if ( artifact.getFile().equals( file ) )
                {
                    return artifact;
                }
            }
        }
        return null;
    }

    private static String getCreatedByVersion( String groupId, String artifactId )
    {
        final Properties properties = loadOptionalProperties( MavenArchiver.class.getResourceAsStream(
            "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties" ) );

        return properties.getProperty( "version" );
    }

    private static Properties loadOptionalProperties( final InputStream inputStream )
    {
        Properties properties = new Properties();
        if ( inputStream != null )
        {
            try ( InputStream in = inputStream )
            {
                properties.load( in );
            }
            catch ( IllegalArgumentException | IOException ex )
            {
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
    public void setCreatedBy( String description, String groupId, String artifactId )
    {
        createdBy = createdBy( description, groupId, artifactId );
    }

    private String createdBy( String description, String groupId, String artifactId )
    {
        String createdBy = description;
        String version = getCreatedByVersion( groupId, artifactId );
        if ( version != null )
        {
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
    public void setBuildJdkSpecDefaultEntry( boolean buildJdkSpecDefaultEntry )
    {
        this.buildJdkSpecDefaultEntry = buildJdkSpecDefaultEntry;
    }

    /**
     * Parse output timestamp configured for Reproducible Builds' archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>.
     *
     * @param outputTimestamp the value of <code>${project.build.outputTimestamp}</code> (may be <code>null</code>)
     * @return the parsed timestamp, may be <code>null</code> if <code>null</code> input or input contains only 1
     *         character
     * @since 3.5.0
     * @throws IllegalArgumentException if the outputTimestamp is neither ISO 8601 nor an integer, or it's not within
     *             the valid range 1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z
     * @deprecated Use {@link #parseBuildOutputTimestamp(String)} instead.
     */
    @Deprecated
    public Date parseOutputTimestamp( String outputTimestamp )
    {
        return parseBuildOutputTimestamp( outputTimestamp ).map( Date::from ).orElse( null );
    }

    /**
     * Configure Reproducible Builds archive creation if a timestamp is provided.
     *
     * @param outputTimestamp the value of {@code ${project.build.outputTimestamp}} (may be {@code null})
     * @return the parsed timestamp as {@link java.util.Date}
     * @since 3.5.0
     * @see #parseOutputTimestamp
     * @deprecated Use {@link #configureReproducibleBuild(String)} instead.
     */
    @Deprecated
    public Date configureReproducible( String outputTimestamp )
    {
        configureReproducibleBuild( outputTimestamp );
        return parseOutputTimestamp( outputTimestamp );
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
     *             the valid range 1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z
     */
    public static Optional<Instant> parseBuildOutputTimestamp( String outputTimestamp )
    {
        // Fail-fast on nulls
        if ( outputTimestamp == null )
        {
            return Optional.empty();
        }

        // Number representing seconds since the epoch
        if ( StringUtils.isNotEmpty( outputTimestamp ) && StringUtils.isNumeric( outputTimestamp ) )
        {
            return Optional.of( Instant.ofEpochSecond( Long.parseLong( outputTimestamp ) ) );
        }

        // no timestamp configured (1 character configuration is useful to override a full value during pom
        // inheritance)
        if ( outputTimestamp.length() < 2 )
        {
            return Optional.empty();
        }

        try
        {
            // Parse the date in UTC such as '2011-12-03T10:15:30Z' or with an offset '2019-10-05T20:37:42+06:00'.
            final Instant date = OffsetDateTime.parse( outputTimestamp )
                .withOffsetSameInstant( ZoneOffset.UTC ).truncatedTo( ChronoUnit.SECONDS ).toInstant();

            if ( date.isBefore( DATE_MIN ) || date.isAfter( DATE_MAX ) )
            {
                throw new IllegalArgumentException( "'" + date + "' is not within the valid range "
                    + DATE_MIN + " to " + DATE_MAX );
            }
            return Optional.of( date );
        }
        catch ( DateTimeParseException pe )
        {
            throw new IllegalArgumentException( "Invalid project.build.outputTimestamp value '" + outputTimestamp + "'",
                                                pe );
        }
    }

    /**
     * Configure Reproducible Builds archive creation if a timestamp is provided.
     *
     * @param outputTimestamp the value of {@code project.build.outputTimestamp} (may be {@code null})
     * @since 3.6.0
     * @see #parseBuildOutputTimestamp(String)
     */
    public void configureReproducibleBuild( String outputTimestamp )
    {
        parseBuildOutputTimestamp( outputTimestamp )
            .map( FileTime::from )
            .ifPresent( modifiedTime -> getArchiver().configureReproducibleBuild( modifiedTime ) );
    }
}
