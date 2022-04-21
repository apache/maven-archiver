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

/**
 * Capture common manifest configuration.
 *
 * @author <a href="mailto:brett@apache.org">Brett Porter</a>
 */
// TODO Is this general enough to be in Plexus Archiver?
public class ManifestConfiguration
{
    /**
     * The simple layout.
     */
    public static final String CLASSPATH_LAYOUT_TYPE_SIMPLE = "simple";

    /**
     * The layout type
     */
    public static final String CLASSPATH_LAYOUT_TYPE_REPOSITORY = "repository";

    /**
     * custom layout type.
     */
    public static final String CLASSPATH_LAYOUT_TYPE_CUSTOM = "custom";

    private String mainClass;

    private String packageName;

    private boolean addClasspath;

    private boolean addExtensions;

    /**
     * This gets prefixed to all classpath entries.
     */
    private String classpathPrefix = "";

    /**
     * Add default, reproducible entries {@code Created-By} and {@code Build-Jdk-Spec}.
     *
     * @since 3.4.0
     */
    private boolean addDefaultEntries = true;


    /**
     * Add build environment information about Maven, JDK, and OS.
     *
     * @since 3.4.0
     */
    private boolean addBuildEnvironmentEntries;

    /**
     * Add default implementation entries if this is an extension specification.
     *
     * @since 2.1
     */
    private boolean addDefaultSpecificationEntries;

    /**
     * Add default implementation entries if this is an extension.
     *
     * @since 2.1
     */
    private boolean addDefaultImplementationEntries;

    private String classpathLayoutType = CLASSPATH_LAYOUT_TYPE_SIMPLE;

    private String customClasspathLayout;

    private boolean useUniqueVersions = true;

    /**
     * <p>Getter for the field <code>mainClass</code>.</p>
     *
     * @return mainClass
     */
    public String getMainClass()
    {
        return mainClass;
    }

    /**
     * <p>Getter for the field <code>packageName</code>.</p>
     *
     * @return the package name.
     */
    public String getPackageName()
    {
        return packageName;
    }

    /**
     * <p>isAddClasspath.</p>
     *
     * @return if addClasspath true or false.
     */
    public boolean isAddClasspath()
    {
        return addClasspath;
    }

    /**
     * <p>isAddDefaultEntries.</p>
     *
     * @return {@link #addDefaultEntries}
     */
    public boolean isAddDefaultEntries()
    {
        return addDefaultEntries;
    }

    /**
     * <p>isAddBuildEnvironmentEntries.</p>
     *
     * @return {@link #addBuildEnvironmentEntries}
     */
    public boolean isAddBuildEnvironmentEntries()
    {
        return addBuildEnvironmentEntries;
    }

    /**
     * <p>isAddDefaultImplementationEntries.</p>
     *
     * @return {@link #addDefaultImplementationEntries}
     */
    public boolean isAddDefaultImplementationEntries()
    {
        return addDefaultImplementationEntries;
    }

    /**
     * <p>isAddDefaultSpecificationEntries.</p>
     *
     * @return {@link #addDefaultSpecificationEntries}
     */
    public boolean isAddDefaultSpecificationEntries()
    {
        return addDefaultSpecificationEntries;
    }

    /**
     * <p>isAddExtensions.</p>
     *
     * @return {@link #addExtensions}
     */
    public boolean isAddExtensions()
    {
        return addExtensions;
    }

    /**
     * <p>Setter for the field <code>addClasspath</code>.</p>
     *
     * @param addClasspath turn on addClasspath or off.
     */
    public void setAddClasspath( boolean addClasspath )
    {
        this.addClasspath = addClasspath;
    }

    /**
     * <p>Setter for the field <code>addDefaultEntries</code>.</p>
     *
     * @param addDefaultEntries add default entries true/false.
     */
    public void setAddDefaultEntries( boolean addDefaultEntries )
    {
        this.addDefaultEntries = addDefaultEntries;
    }

    /**
     * <p>Setter for the field <code>addBuildEnvironmentEntries</code>.</p>
     *
     * @param addBuildEnvironmentEntries add build environment information true/false.
     */
    public void setAddBuildEnvironmentEntries( boolean addBuildEnvironmentEntries )
    {
        this.addBuildEnvironmentEntries = addBuildEnvironmentEntries;
    }

    /**
     * <p>Setter for the field <code>addDefaultImplementationEntries</code>.</p>
     *
     * @param addDefaultImplementationEntries true to add default implementations false otherwise.
     */
    public void setAddDefaultImplementationEntries( boolean addDefaultImplementationEntries )
    {
        this.addDefaultImplementationEntries = addDefaultImplementationEntries;
    }

    /**
     * <p>Setter for the field <code>addDefaultSpecificationEntries</code>.</p>
     *
     * @param addDefaultSpecificationEntries add default specifications true/false.
     */
    public void setAddDefaultSpecificationEntries( boolean addDefaultSpecificationEntries )
    {
        this.addDefaultSpecificationEntries = addDefaultSpecificationEntries;
    }

    /**
     * <p>Setter for the field <code>addExtensions</code>.</p>
     *
     * @param addExtensions true to add extensions false otherwise.
     */
    public void setAddExtensions( boolean addExtensions )
    {
        this.addExtensions = addExtensions;
    }

    /**
     * <p>Setter for the field <code>classpathPrefix</code>.</p>
     *
     * @param classpathPrefix The prefix.
     */
    public void setClasspathPrefix( String classpathPrefix )
    {
        this.classpathPrefix = classpathPrefix;
    }

    /**
     * <p>Setter for the field <code>mainClass</code>.</p>
     *
     * @param mainClass The main class.
     */
    public void setMainClass( String mainClass )
    {
        this.mainClass = mainClass;
    }

    /**
     * <p>Setter for the field <code>packageName</code>.</p>
     *
     * @param packageName The package name.
     */
    public void setPackageName( String packageName )
    {
        this.packageName = packageName;
    }

    /**
     * <p>Getter for the field <code>classpathPrefix</code>.</p>
     *
     * @return The classpath prefix.
     */
    public String getClasspathPrefix()
    {
        String cpp = classpathPrefix.replaceAll( "\\\\", "/" );

        if ( cpp.length() != 0 && !cpp.endsWith( "/" ) )
        {
            cpp += "/";
        }

        return cpp;
    }

    /**
     * Return the type of layout to use when formatting classpath entries. Default is taken from the constant
     * CLASSPATH_LAYOUT_TYPE_SIMPLE, declared in this class, which has a value of 'simple'. Other values are:
     * 'repository' (CLASSPATH_LAYOUT_TYPE_REPOSITORY, or the same as a maven classpath layout), and 'custom'
     * (CLASSPATH_LAYOUT_TYPE_CUSTOM). <br>
     * <b>NOTE:</b> If you specify a type of 'custom' you MUST set
     * {@link org.apache.maven.archiver.ManifestConfiguration#setCustomClasspathLayout(String)}.
     *
     * @return The classpath layout type.
     */
    public String getClasspathLayoutType()
    {
        return classpathLayoutType;
    }

    /**
     * Set the type of layout to use when formatting classpath entries. Should be one of: 'simple'
     * (CLASSPATH_LAYOUT_TYPE_SIMPLE), 'repository' (CLASSPATH_LAYOUT_TYPE_REPOSITORY, or the same as a maven classpath
     * layout), and 'custom' (CLASSPATH_LAYOUT_TYPE_CUSTOM). The constant names noted here are defined in the
     * {@link org.apache.maven.archiver.ManifestConfiguration} class. <br>
     * <b>NOTE:</b> If you specify a type of 'custom' you MUST set
     * {@link org.apache.maven.archiver.ManifestConfiguration#setCustomClasspathLayout(String)}.
     *
     * @param classpathLayoutType The classpath layout type.
     */
    public void setClasspathLayoutType( String classpathLayoutType )
    {
        this.classpathLayoutType = classpathLayoutType;
    }

    /**
     * Retrieve the layout expression for use when the layout type set in
     * {@link org.apache.maven.archiver.ManifestConfiguration#setClasspathLayoutType(String)} has the value 'custom'.
     * <b>The default value is
     * null.</b> Expressions will be evaluated against the following ordered list of classpath-related objects:
     * <ol>
     * <li>The current {@code Artifact} instance, if one exists.</li>
     * <li>The current {@code ArtifactHandler} instance from the artifact above.</li>
     * </ol>
     * <br>
     * <b>NOTE:</b> If you specify a layout type of 'custom' you MUST set this layout expression.
     *
     * @return The custom classpath layout.
     */
    public String getCustomClasspathLayout()
    {
        return customClasspathLayout;
    }

    /**
     * Set the layout expression for use when the layout type set in
     * {@link org.apache.maven.archiver.ManifestConfiguration#setClasspathLayoutType(String)} has the value 'custom'.
     * Expressions will be
     * evaluated against the following ordered list of classpath-related objects:
     * <ol>
     * <li>The current {@code Artifact} instance, if one exists.</li>
     * <li>The current {@code ArtifactHandler} instance from the artifact above.</li>
     * </ol>
     * <br>
     * <b>NOTE:</b> If you specify a layout type of 'custom' you MUST set this layout expression.
     * You can take a look at
     * <ol>
     * <li>{@link org.apache.maven.archiver.MavenArchiver#SIMPLE_LAYOUT}</li>
     * <li>{@link org.apache.maven.archiver.MavenArchiver#SIMPLE_LAYOUT_NONUNIQUE}</li>
     * <li>{@link org.apache.maven.archiver.MavenArchiver#REPOSITORY_LAYOUT}</li>
     * <li>{@link org.apache.maven.archiver.MavenArchiver#REPOSITORY_LAYOUT_NONUNIQUE}</li>
     * </ol>
     * how such an expression looks like.
     *
     * @param customClasspathLayout The custom classpath layout.
     */
    public void setCustomClasspathLayout( String customClasspathLayout )
    {
        this.customClasspathLayout = customClasspathLayout;
    }

    /**
     * Retrieve the flag for whether snapshot artifacts should be added to the classpath using the
     * timestamp/buildnumber version (the default, when this flag is true), or using the generic
     * -SNAPSHOT version (when the flag is false). <br>
     * <b>NOTE:</b> If the snapshot was installed locally, this flag will not have an effect on
     * that artifact's inclusion, since it will have the same version either way (i.e. -SNAPSHOT naming).
     *
     * @return The state of {@link #useUniqueVersions}
     */
    public boolean isUseUniqueVersions()
    {
        return useUniqueVersions;
    }

    /**
     * Set the flag for whether snapshot artifacts should be added to the classpath using the timestamp/buildnumber
     * version (the default, when this flag is true), or using the generic -SNAPSHOT version (when the flag is false).
     * <br>
     * <b>NOTE:</b> If the snapshot was installed locally, this flag will not have an effect on that artifact's
     * inclusion, since it will have the same version either way (i.e. -SNAPSHOT naming).
     *
     * @param useUniqueVersions true to use unique versions or not.
     */
    public void setUseUniqueVersions( boolean useUniqueVersions )
    {
        this.useUniqueVersions = useUniqueVersions;
    }
}
