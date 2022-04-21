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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>ManifestSection class.</p>
 */
public class ManifestSection
{

    private String name = null;

    private final Map<String, String> manifestEntries = new LinkedHashMap<>();

    /**
     * <p>addManifestEntry.</p>
     *
     * @param key The key of the manifest entry.
     * @param value The appropriate value.
     */
    public void addManifestEntry( String key, String value )
    {
        manifestEntries.put( key, value );
    }

    /**
     * <p>Getter for the field <code>manifestEntries</code>.</p>
     *
     * @return The entries.
     */
    public Map<String, String> getManifestEntries()
    {
        return manifestEntries;
    }

    /**
     * <p>Getter for the field <code>name</code>.</p>
     *
     * @return The name.
     */
    public String getName()
    {
        return name;
    }

    /**
     * <p>Setter for the field <code>name</code>.</p>
     *
     * @param name the name.
     */
    public void setName( String name )
    {
        this.name = name;
    }

    /**
     * <p>addManifestEntries.</p>
     *
     * @param map The map to add.
     */
    public void addManifestEntries( Map<String, String> map )
    {
        manifestEntries.putAll( map );
    }

    /**
     * <p>isManifestEntriesEmpty.</p>
     *
     * @return true if empty false otherwise.
     */
    public boolean isManifestEntriesEmpty()
    {
        return manifestEntries.isEmpty();
    }
}
