/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state.liquibase;

import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.ScopeManager;
import liquibase.database.core.MockDatabase;
import liquibase.exception.LiquibaseException;

/**
 * @author hal.hildebrand
 *
 */
public class ThreadLocalScopeManager extends ScopeManager {

    private ThreadLocalScopeManager() {
    }

    private static final ThreadLocal<Scope> CURRENT_SCOPE = new ThreadLocal<>() {
        @Override
        protected Scope initialValue() {
            return defaultScope;
        }
    };

    private static Scope defaultScope;

    public static void initialize() {
        try {
            new Liquibase((String) null, new NullResourceAccessor(), new MockDatabase()).close();
        } catch (LiquibaseException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        defaultScope = Scope.getCurrentScope();
        Scope.setScopeManager(new ThreadLocalScopeManager());
    }

    @Override
    public Scope getCurrentScope() {
        return CURRENT_SCOPE.get();
    }

    @Override
    protected Scope init(Scope scope) throws Exception {
        return scope;
    }

    @Override
    protected void setCurrentScope(Scope scope) {
        CURRENT_SCOPE.set(scope);
    }
}
