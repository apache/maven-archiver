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

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.xml.XmlNode;
import org.apache.maven.api.xml.XmlService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildHelperTest {

    // -------------------------------------------------------------------------
    // isDeployable: no deploy-related plugins
    // -------------------------------------------------------------------------

    @Test
    void isDeployableWithNoPluginsReturnsTrue() {
        Model model = Model.newBuilder().build();
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    // -------------------------------------------------------------------------
    // isDeployable: maven-deploy-plugin
    // -------------------------------------------------------------------------

    @Test
    void isDeployableWithDeployPluginNoExecutionNotSkippedReturnsTrue() {
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .build();
        Model model = modelWithPlugin(deployPlugin);
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithDeployPluginNoExecutionSkippedViaPluginConfigReturnsFalse() {
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .configuration(xml("<configuration><skip>true</skip></configuration>"))
                .build();
        Model model = modelWithPlugin(deployPlugin);
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithDeployPluginNoExecutionSkippedViaPropertyReturnsFalse() {
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .build();
        Model model = modelWithPluginAndProperty(deployPlugin, "maven.deploy.skip", "true");
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithDeployPluginWithDeployExecutionNotSkippedReturnsTrue() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPlugin(deployPlugin);
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithDeployPluginWithDeployExecutionSkippedViaExecutionConfigReturnsFalse() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .configuration(xml("<configuration><skip>true</skip></configuration>"))
                .build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPlugin(deployPlugin);
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithDeployPluginWithDeployExecutionSkippedViaPropertyReturnsFalse() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPluginAndProperty(deployPlugin, "maven.deploy.skip", "true");
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithDeployPluginExecutionSkipOverridesPropertyReturnsTrue() {
        // Property says skip=true, but execution config explicitly says skip=false -> deployable
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .configuration(xml("<configuration><skip>false</skip></configuration>"))
                .build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPluginAndProperty(deployPlugin, "maven.deploy.skip", "true");
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithDeployPluginNonDeployGoalExecutionUsesPropertyDefaultReturnsFalse() {
        // Executions only contain a non-deploy goal; no deploy execution found -> falls back to property
        PluginExecution exec =
                PluginExecution.newBuilder().id("help").goals(List.of("help")).build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPluginAndProperty(deployPlugin, "maven.deploy.skip", "true");
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    // -------------------------------------------------------------------------
    // isDeployable: central-publishing-maven-plugin
    // -------------------------------------------------------------------------

    @Test
    void isDeployableWithCentralPublishingWithPublishExecutionNotSkippedReturnsTrue() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("injected-central-publishing")
                .goals(List.of("publish"))
                .build();
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPlugin(central);
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithCentralPublishingWithPublishExecutionSkippedViaConfigReturnsFalse() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("injected-central-publishing")
                .goals(List.of("publish"))
                .configuration(xml("<configuration><skipPublishing>true</skipPublishing></configuration>"))
                .build();
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPlugin(central);
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithCentralPublishingWithPublishExecutionSkippedViaPropertyReturnsFalse() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("injected-central-publishing")
                .goals(List.of("publish"))
                .build();
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPluginAndProperty(central, "skipPublishing", "true");
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    @Test
    void isDeployableWithCentralPublishingNoExecutionNotSkippedReturnsTrue() {
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .build();
        Model model = modelWithPlugin(central);
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithCentralPublishingTakesPrecedenceOverDeployPlugin() {
        // central-publishing present (not skipped) + deploy-plugin skipped -> deployable
        PluginExecution centralExec = PluginExecution.newBuilder()
                .id("injected-central-publishing")
                .goals(List.of("publish"))
                .build();
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .executions(List.of(centralExec))
                .build();
        Plugin deployPlugin = Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId("maven-deploy-plugin")
                .configuration(xml("<configuration><skip>true</skip></configuration>"))
                .build();
        Model model = modelWithPlugins(List.of(central, deployPlugin));
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    // -------------------------------------------------------------------------
    // isDeployable: nexus-staging-maven-plugin
    // -------------------------------------------------------------------------

    @Test
    void isDeployableWithNexusStagingWithDeployExecutionNotSkippedReturnsTrue() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .build();
        Plugin nexus = Plugin.newBuilder()
                .groupId("org.sonatype.plugins")
                .artifactId("nexus-staging-maven-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPlugin(nexus);
        assertThat(BuildHelper.isDeployable(model)).isTrue();
    }

    @Test
    void isDeployableWithNexusStagingSkippedViaPropertyReturnsFalse() {
        PluginExecution exec = PluginExecution.newBuilder()
                .id("default-deploy")
                .goals(List.of("deploy"))
                .build();
        Plugin nexus = Plugin.newBuilder()
                .groupId("org.sonatype.plugins")
                .artifactId("nexus-staging-maven-plugin")
                .executions(List.of(exec))
                .build();
        Model model = modelWithPluginAndProperty(nexus, "skipNexusStagingDeployMojo", "true");
        assertThat(BuildHelper.isDeployable(model)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static XmlNode xml(String xmlString) {
        try {
            return XmlService.read(new StringReader(xmlString));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Model modelWithPlugin(Plugin plugin) {
        return modelWithPlugins(List.of(plugin));
    }

    private static Model modelWithPluginAndProperty(Plugin plugin, String key, String value) {
        return Model.newBuilder()
                .build(Build.newBuilder().plugins(List.of(plugin)).build())
                .properties(Map.of(key, value))
                .build();
    }

    private static Model modelWithPlugins(List<Plugin> plugins) {
        return Model.newBuilder()
                .build(Build.newBuilder().plugins(plugins).build())
                .build();
    }
}
