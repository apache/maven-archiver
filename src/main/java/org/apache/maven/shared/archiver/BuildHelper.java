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

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginContainer;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.xml.XmlNode;

/**
 * Helper to detect info about build info in a Maven model, as configured in plugins.
 *
 * @since 4.0.0-beta-5
 */
public class BuildHelper {

    private static final String MAVEN_DEPLOY_PLUGIN = "org.apache.maven.plugins:maven-deploy-plugin";
    private static final String NEXUS_STAGING_PLUGIN = "org.sonatype.plugins:nexus-staging-maven-plugin";
    private static final String CENTRAL_PUBLISHING_PLUGIN = "org.sonatype.central:central-publishing-maven-plugin";

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

    private static final Set<String> LEGACY_JDK_VERSIONS = Set.of("1.5", "1.6", "1.7", "1.8");

    /**
     * Normalize Java version, for versions 5 to 8 where there is a 1.x alias.
     *
     * @param jdk can be null
     * @return normalized version if a known alias is used
     */
    public static String normalizeJavaVersion(String jdk) {
        if (jdk != null && LEGACY_JDK_VERSIONS.contains(jdk)) {
            return jdk.substring(2); // alternatively, just return the exact string you want, e.g., "5"
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
        Build build = model.getBuild();
        Plugin plugin = getPlugin(build, pluginGa);
        if (build != null && plugin == null) {
            plugin = getPlugin(build.getPluginManagement(), pluginGa);
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

    /**
     * Determines whether a Maven module will be deployed to a remote repository.
     * <p>
     * This method analyses the model's plugin configuration to detect if deployment will be skipped,
     * taking into account the three known deployment mechanisms:
     * <ol>
     * <li>{@code central-publishing-maven-plugin} — the new Sonatype Central Portal publisher; when
     *     present it replaces {@code maven-deploy-plugin}. The module is considered deployable if the
     *     plugin has at least one execution of the {@code publish} goal that is not skipped (via the
     *     {@code skipPublishing} parameter or property).</li>
     * <li>{@code nexus-staging-maven-plugin} — the legacy Sonatype OSS publisher; when present it
     *     replaces standard deploy. The module is considered deployable if it has at least one execution
     *     of the {@code deploy} goal that is not skipped (via the {@code skipNexusStagingDeployMojo}
     *     parameter or property).</li>
     * <li>{@code maven-deploy-plugin} — the standard deploy plugin. The module is deployable if it has
     *     at least one execution of the {@code deploy} goal that is not skipped (via the {@code skip}
     *     parameter or the {@code maven.deploy.skip} property).</li>
     * </ol>
     * The extension plugins are checked first. If neither is present, the standard deploy plugin is
     * checked. A module with none of these plugins configured is considered deployable by default.
     *
     * @param model not null
     * @return {@code true} if the module will be deployed to a remote repository, {@code false} otherwise
     * @since 4.0.0-beta-6
     */
    public static boolean isDeployable(Model model) {
        Plugin centralPublishing = getPlugin(model, CENTRAL_PUBLISHING_PLUGIN);
        if (centralPublishing != null) {
            return isDeployable(model, centralPublishing, "skipPublishing", "skipPublishing", "publish");
        }
        Plugin nexusStaging = getPlugin(model, NEXUS_STAGING_PLUGIN);
        if (nexusStaging != null) {
            return isDeployable(
                    model, nexusStaging, "skipNexusStagingDeployMojo", "skipNexusStagingDeployMojo", "deploy");
        }
        Plugin deployPlugin = getPlugin(model, MAVEN_DEPLOY_PLUGIN);
        // No deploy plugin configured at all: deployable by default
        if (deployPlugin == null) {
            return true;
        }
        return isDeployable(model, deployPlugin, "skip", "maven.deploy.skip", "deploy");
    }

    /**
     * Checks whether a specific deployment plugin has at least one active (non-skipped) execution
     * of the given goal.
     *
     * @param model the project model (used for property fallback)
     * @param plugin the deployment plugin to inspect
     * @param parameter the plugin configuration parameter name controlling skip behaviour
     * @param propertyName the model property name used as the default value for the skip parameter
     * @param goal the deployment goal to look for in executions
     * @return {@code true} if at least one execution of the goal is not skipped
     */
    private static boolean isDeployable(
            Model model, Plugin plugin, String parameter, String propertyName, String goal) {
        // Compute the default skip value from model properties (execution-level config overrides it)
        String defaultSkipStr = model.getProperties().get(propertyName);
        boolean defaultSkip = defaultSkipStr != null && Boolean.parseBoolean(defaultSkipStr);

        List<PluginExecution> executions = plugin.getExecutions();
        if (executions.isEmpty()) {
            // Plugin declared but no explicit executions: check plugin-level config, then property default
            String pluginLevelSkip = getPluginParameter(plugin, parameter);
            boolean skip = pluginLevelSkip != null ? Boolean.parseBoolean(pluginLevelSkip) : defaultSkip;
            return !skip;
        }

        // If any execution of the target goal is not skipped, the module is deployable
        for (PluginExecution execution : executions) {
            if (execution.getGoals().contains(goal)) {
                XmlNode executionConf = execution.getConfiguration();
                XmlNode target = executionConf != null ? executionConf.child(parameter) : null;
                boolean skip = target != null ? Boolean.parseBoolean(target.value()) : defaultSkip;
                if (!skip) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Plugin getPlugin(PluginContainer container, String pluginGa) {
        if (container == null) {
            return null;
        }
        Map<String, Plugin> pluginsAsMap = container.getPluginsAsMap();
        return pluginsAsMap == null ? null : pluginsAsMap.get(pluginGa);
    }

    private static String getPluginParameter(Plugin plugin, String parameter) {
        if (plugin != null) {
            XmlNode pluginConf = plugin.getConfiguration();

            if (pluginConf != null) {
                XmlNode target = pluginConf.child(parameter);

                if (target != null) {
                    return target.value();
                }
            }
        }
        return null;
    }
}
