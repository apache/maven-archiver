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

import java.util.Map;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.PluginManagement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildHelperTest {

    // Mocking is necessary here because the standard Maven model implementation
    // always returns a non-null map from getPluginsAsMap(). These tests exercise
    // the defensive null check against non-standard PluginContainer implementations.

    @Test
    void getPluginHandlesNullPluginMap() {
        Model model = mock(Model.class);
        Build build = mock(Build.class);
        when(model.getBuild()).thenReturn(build);
        when(build.getPluginsAsMap()).thenReturn(null);

        assertThat(BuildHelper.getPlugin(model, "org.example:example-plugin")).isNull();
    }

    @Test
    void getPluginHandlesNullPluginMapInPluginManagement() {
        Model model = mock(Model.class);
        Build build = mock(Build.class);
        PluginManagement mgmt = mock(PluginManagement.class);
        when(model.getBuild()).thenReturn(build);
        when(build.getPluginsAsMap()).thenReturn(Map.of());
        when(build.getPluginManagement()).thenReturn(mgmt);
        when(mgmt.getPluginsAsMap()).thenReturn(null);

        assertThat(BuildHelper.getPlugin(model, "org.example:example-plugin")).isNull();
    }
}
