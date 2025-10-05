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

import java.util.Arrays;
import java.util.Map;

import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.xml.XmlNode;

/**
 * Helper to detect info about build info in a Maven model, as configured in plugins.
 *
 * @since 4.0.0-beta-5
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
     * @param model not null
     * @return the Java release version configured in the model, or null if not configured
     */
    public static String discoverJavaRelease(Model model) {
        Plugin compiler = getCompilerPlugin(model);

        String jdk = getPluginParameter(model, compiler, "release", "maven.compiler.release");

        if (jdk == null) {
            jdk = getPluginParameter(model, compiler, "target", "maven.compiler.target");
        }

        return normalizeJavaVersion(jdk);
    }

    /**
     * Normalize Java version, for versions 5 to 8 where there is a 1.x alias.
     *
     * @param jdk can be null
     * @return normalized version if an alias was used
     */
    public static String normalizeJavaVersion(String jdk) {
        if (jdk != null
                && jdk.length() == 3
                && Arrays.asList("1.5", "1.6", "1.7", "1.8").contains(jdk)) {
            jdk = jdk.substring(2);
        }
        return jdk;
    }

    public static Plugin getCompilerPlugin(Model model) {
        return getPlugin(model, "org.apache.maven.plugins:maven-compiler-plugin");
    }

    /**
     * Get plugin from model based on coordinates {@code groupId:artifactId}.
     *
     * @param model not null
     * @param pluginGa {@code groupId:artifactId}
     * @return the plugin from build or pluginManagement, if available in project
     */
    public static Plugin getPlugin(Model model, String pluginGa) {
        Plugin plugin = getPlugin(model.getBuild(), pluginGa);
        if (model.getBuild() != null && plugin == null) {
            plugin = getPlugin(model.getBuild().getPluginManagement(), pluginGa);
        }
        return plugin;
    }

    /**
     * Get plugin parameter value if configured in current model.
     *
     * @param model not null
     * @param plugin can be null
     * @param parameter the parameter name when configured in plugin's configuration
     * @param defaultValueProperty the property name when default value is used for the plugin parameter
     * @return the value, or null if not configured at all, but using internal default from plugin
     */
    public static String getPluginParameter(Model model, Plugin plugin, String parameter, String defaultValueProperty) {
        String value = getPluginParameter(plugin, parameter);
        if (value == null) {
            value = model.getProperties().get(defaultValueProperty);
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
            XmlNode pluginConf = plugin.getConfiguration();

            if (pluginConf != null) {
                XmlNode target = pluginConf.getChild(parameter);

                if (target != null) {
                    return target.getValue();
                }
            }
        }
        return null;
    }
}
