---
title: Set Up The Classpath
author: 
  - Dennis Lundberg
date: 2008-01-01
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Set Up The Classpath

## <a id="Contents">Contents</a>

- [Add A Class-Path Entry To The Manifest](#Add)
- [Make The Jar Executable](#Make)
- [Altering The Classpath: Defining a Classpath Directory Prefix](#Prefix)
- [Altering The Classpath: Using a Maven Repository-Style Classpath](#Repository)
- [Altering The Classpath: Using a Custom Classpath Format](#Custom)
- [Handling Snapshot Versions](#Snapshot)

## <a id="Add">Add A Class-Path Entry To The Manifest</a>

[[Top](#Contents)]

Maven Archiver can add the classpath of your project to the manifest. This is done with the `<addClasspath>` configuration element.

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        ...
        <configuration>
          <archive>
            <manifest>
              <addClasspath>true</addClasspath>
            </manifest>
          </archive>
        </configuration>
        ...
      </plugin>
    </plugins>
  </build>
  ...
  <dependencies>
    <dependency>
      <groupId>commons-lang</groupId>
      <artifactId>commons-lang</artifactId>
      <version>2.1</version>
    </dependency>
    <dependency>
      <groupId>org.codehaus.plexus</groupId>
      <artifactId>plexus-utils</artifactId>
      <version>1.1</version>
    </dependency>
  </dependencies>
  ...
</project>
```

The manifest produced using the above configuration would look like this:

```
Manifest-Version: 1.0
Created-By: Apache Maven ${maven.version}
Build-Jdk: ${java.version}
Class-Path: plexus-utils-1.1.jar commons-lang-2.1.jar
```

## <a id="Make">Make The Jar Executable</a>

[[Top](#Contents)]

If you want to create an executable jar file, you need to configure Maven Archiver accordingly. You need to tell it which main class to use. This is done with the `<mainClass>` configuration element. Here is a sample `pom.xml` configured to add the classpath and use the class `fully.qualified.MainClass` as the main class:

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-jar-plugin</artifactId>
        ...
        <configuration>
          <archive>
            <manifest>
              <addClasspath>true</addClasspath>
              <mainClass>fully.qualified.MainClass</mainClass>
            </manifest>
          </archive>
        </configuration>
        ...
      </plugin>
    </plugins>
  </build>
  ...
  <dependencies>
    <dependency>
      <groupId>commons-lang</groupId>
      <artifactId>commons-lang</artifactId>
      <version>2.1</version>
    </dependency>
    <dependency>
      <groupId>org.codehaus.plexus</groupId>
      <artifactId>plexus-utils</artifactId>
      <version>1.1</version>
    </dependency>
  </dependencies>
  ...
</project>
```

The manifest produced using the above configuration would look like this:

```
Manifest-Version: 1.0
Created-By: Apache Maven ${maven.version}
Build-Jdk: ${java.version}
Main-Class: fully.qualified.MainClass
Class-Path: plexus-utils-1.1.jar commons-lang-2.1.jar
```

## <a id="Prefix">Altering The Classpath: Defining a Classpath Directory Prefix</a>

[[Top](#Contents)]

Sometimes it is useful to be able to alter the classpath, for example when [creating skinny war-files](/plugins/maven-war-plugin/examples/skinny-wars.html). This can be achieved with the `<classpathPrefix>` configuration element.

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
         <artifactId>maven-war-plugin</artifactId>
         <configuration>
           <archive>
             <manifest>
               <addClasspath>true</addClasspath>
               <classpathPrefix>lib/</classpathPrefix>
             </manifest>
           </archive>
         </configuration>
      </plugin>
    </plugins>
  </build>
  ...
  <dependencies>
    <dependency>
      <groupId>commons-lang</groupId>
      <artifactId>commons-lang</artifactId>
      <version>2.1</version>
    </dependency>
    <dependency>
      <groupId>org.codehaus.plexus</groupId>
      <artifactId>plexus-utils</artifactId>
      <version>1.1</version>
    </dependency>
  </dependencies>
  ...
</project>
```

The manifest classpath produced using the above configuration would look like this:

```
Class-Path: lib/plexus-utils-1.1.jar lib/commons-lang-2.1.jar
```

## <a id="Repository">Altering The Classpath: Using a Maven Repository-Style Classpath</a>

[[Top](#Contents)]

_(Since: 2.3, see below)_

Occasionally, you may want to include a Maven repository-style directory structure in your archive. If you wish to reference the dependency archives within those directories in your manifest classpath, try using the `<classpathLayoutType>` element with a value of `'repository'`, like this:

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
        <artifactId>maven-jar-plugin</artifactId>
        <version>2.3</version>
        <configuration>
          <archive>
            <manifest>
              <addClasspath>true</addClasspath>
              <classpathPrefix>lib/</classpathPrefix>
              <classpathLayoutType>repository</classpathLayoutType>
            </manifest>
          </archive>
        </configuration>
      </plugin>
    </plugins>
  </build>
  ...
  <dependencies>
    <dependency>
      <groupId>commons-lang</groupId>
      <artifactId>commons-lang</artifactId>
      <version>2.1</version>
    </dependency>
    <dependency>
      <groupId>org.codehaus.plexus</groupId>
      <artifactId>plexus-utils</artifactId>
      <version>1.1</version>
    </dependency>
  </dependencies>
  ...
</project>
```

The manifest classpath produced using the above configuration would look like this:

```
Class-Path: lib/org/codehaus/plexus/plexus-utils/1.1/plexus-utils-1.1.jar lib/commons-lang/commons-lang/2.1/commons-lang-2.1.jar
```

## <a id="Custom">Altering The Classpath: Using a Custom Classpath Format</a>

[[Top](#Contents)]

_(Since: 2.4)_

At times, you may have dependency archives in a custom format within your own archive, one that doesn't conform to any of the above classpath layouts. If you wish to define a custom layout for dependency archives within your archive's manifest classpath, try using the `<classpathLayoutType>` element with a value of `'custom'`, along with the `<customClasspathLayout>` element, like this:

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
         <artifactId>maven-war-plugin</artifactId>
         <configuration>
           <archive>
             <manifest>
               <addClasspath>true</addClasspath>
               <classpathLayoutType>custom</classpathLayoutType>
               <customClasspathLayout>WEB-INF/lib/$${artifact.groupIdPath}/$${artifact.artifactId}-$${artifact.version}$${dashClassifier?}.$${artifact.extension}</customClasspathLayout>
             </manifest>
           </archive>
         </configuration>
      </plugin>
    </plugins>
  </build>
  ...
  <dependencies>
    <dependency>
      <groupId>commons-lang</groupId>
      <artifactId>commons-lang</artifactId>
      <version>2.1</version>
    </dependency>
    <dependency>
      <groupId>org.codehaus.plexus</groupId>
      <artifactId>plexus-utils</artifactId>
      <version>1.1</version>
    </dependency>
  </dependencies>
  ...
</project>
```

This classpath layout is a little more involved than the previous examples. To understand how the value of the `<customClasspathLayout>` configuration is interpreted, it's useful to understand the rules applied when resolving expressions within the value:

1. If present, trim off the prefix 'artifact.' from the expression.
1. Attempt to resolve the expression as a reference to the Artifact using reflection (eg. `'artifactId'` becomes a reference to the method `'getArtifactId()'`).
1. Attempt to resolve the expression as a reference to the ArtifactHandler of the current Artifact, again using reflection (eg. `'extension'` becomes a reference to the method `'getExtension()'`).
1. Attempt to resolve the expression as a key in the special-case Properties instance, which contains the following mappings:
    - `'dashClassifier'`: If the Artifact has a classifier, this will be `'-$artifact.classifier'`, otherwise this is an empty string.
    - `'dashClassifier?'`: This is a synonym of `'dashClassifier'`.
    - `'groupIdPath'`: This is the equivalent of `'$artifact.groupId'`, with all `'.'` characters replaced by `'/'`.

The manifest classpath produced using the above configuration would look like this:

```
Class-Path: WEB-INF/lib/org/codehaus/plexus/plexus-utils-1.1.jar WEB-INF/lib/commons-lang/commons-lang-2.1.jar
```

## <a id="Snapshot">Handling Snapshot Versions</a>

[[Top](#Contents)]

_(Since 2.4)_

Depending on how you construct your archive, you may have the ability to specify whether snapshot dependency archives are included with the version suffix `'-SNAPSHOT'`, or whether the unique timestamp and build-number for that archive is used. For instance, the [Assembly Plugin](/plugins/maven-assembly-plugin) allows you to make this decision in the `<outputFileNameMapping>` element of its `<dependencySet`&gt; descriptor section.

### Forcing the use of -SNAPSHOT versions when using the simple (default) or repository classpath layout

To force the use of `'-SNAPSHOT'` version naming, simply disable the `<useUniqueVersions>` configuration element, like this:

```xml
<useUniqueVersions>false</useUniqueVersions>
```

### Forcing the use of -SNAPSHOT versions with custom layouts

To force the use of `'-SNAPSHOT'` version naming, simply replace `'$artifact.version'` with `'$artifact.baseVersion'` in the custom layout example above, so it looks like this:

```xml
<customClasspathLayout>WEB-INF/lib/${artifact.groupIdPath}/${artifact.artifactId}-${artifact.baseVersion}${dashClassifier?}.${artifact.extension}</customClasspathLayout>
```

The full example configuration would look like this:

```xml
<project>
  ...
  <build>
    <plugins>
      <plugin>
         <artifactId>maven-war-plugin</artifactId>
         <configuration>
           <archive>
             <manifest>
               <addClasspath>true</addClasspath>
               <classpathLayoutType>custom</classpathLayoutType>
               <customClasspathLayout>WEB-INF/lib/${artifact.groupIdPath}/${artifact.artifactId}-${artifact.version}${dashClassifier?}.${artifact.extension}</customClasspathLayout>
             </manifest>
           </archive>
         </configuration>
      </plugin>
    </plugins>
  </build>
  ...
</project>
```
