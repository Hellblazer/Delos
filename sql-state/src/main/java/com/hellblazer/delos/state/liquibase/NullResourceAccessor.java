/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state.liquibase;

import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;

import liquibase.resource.AbstractResourceAccessor;
import liquibase.resource.InputStreamList;

/**
 * @author hal.hildebrand
 *
 */
public class NullResourceAccessor extends AbstractResourceAccessor implements AutoCloseable {

    @Override
    public void close() {
    }

    @Override
    public SortedSet<String> describeLocations() {
        return new TreeSet<String>();
    }

    @Override
    public SortedSet<String> list(String relativeTo, String path, boolean recursive, boolean includeFiles,
                                  boolean includeDirectories) throws IOException {
        return new TreeSet<String>();
    }

    @Override
    public InputStreamList openStreams(String relativeTo, String streamPath) throws IOException {
        return new InputStreamList();
    }
}
