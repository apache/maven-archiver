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

import java.util.Arrays;
import java.util.Map;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginContainer;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Helper to detect info about build info in a MavenProject, as configured in plugins.
 * 
 * @since 3.6.5
 */
public class BuildHelper {
    /**
     * Tries to determine the target Java release from the following sources (until one is found)
     * <ol>
     * <li>use {@code release} configuration of {@code org.apache.maven.plugins:maven-compiler-plugin}</li>
     * <li>use {@code maven.compiler.release<} property</li>
     * <li>use {@code target} configuration of {@code org.apache.maven.plugins:maven-compiler-plugin}</li>
     * <li>use {@code maven.compiler.target} property</li>
     * </ol>
     *
     * @param project not null
     * @return the Java release version configured in the project, or null if not configured
     */
    public static String discoverJavaRelease(MavenProject project) {
        Plugin compiler = getCompilerPlugin(project);

        String jdk = getPluginParameter(project, compiler, "release", "maven.compiler.release");

        if (jdk == null) {
            jdk = getPluginParameter(project, compiler, "target", "maven.compiler.target");
        }

        if (jdk != null) {
            jdk = normalizeJavaVersion(jdk);
        }

        return jdk;
    }

    public static String normalizeJavaVersion(String jdk) {
        if (jdk.length() == 3 && Arrays.asList("1.5", "1.6", "1.7", "1.8").contains(jdk)) {
            jdk = jdk.substring(2);
        }
        return jdk;
    }

    public static Plugin getCompilerPlugin(MavenProject project) {
        return getPlugin(project, "org.apache.maven.plugins:maven-compiler-plugin");
    }

    public static Plugin getPlugin(MavenProject project, String pluginGa) {
        Plugin plugin = getPlugin(project.getBuild(), pluginGa);
        if (plugin == null) {
            plugin = getPlugin(project.getPluginManagement(), pluginGa);
        }
        return plugin;
    }

    public static String getPluginParameter(
            MavenProject project, Plugin plugin, String parameter, String defaultValueProperty) {
        String value = getPluginParameter(plugin, parameter);
        if (value == null) {
            value = project.getProperties().getProperty(defaultValueProperty);
        }
        return value;
    }

    private static Plugin getPlugin(PluginContainer container, String pluginGa) {
        if (container == null) {
            return null;
        }
        Map<String, Plugin> pluginsAsMap = container.getPluginsAsMap();
        return pluginsAsMap.get(pluginGa);
    }

    private static String getPluginParameter(Plugin plugin, String parameter) {
        if (plugin != null) {
            Xpp3Dom pluginConf = (Xpp3Dom) plugin.getConfiguration();

            if (pluginConf != null) {
                Xpp3Dom target = pluginConf.getChild(parameter);

                if (target != null) {
                    return target.getValue();
                }
            }
        }
        return null;
    }
}
