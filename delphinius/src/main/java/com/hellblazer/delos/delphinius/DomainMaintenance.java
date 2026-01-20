/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.delphinius;

import org.h2.api.Trigger;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.sql.Connection;
import java.sql.SQLException;

import static com.hellblazer.delos.delphinius.schema.tables.Edge.EDGE;

/**
 * @author hal.hildebrand
 */
public class DomainMaintenance implements Trigger {

    private String type;

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        var dsl = DSL.using(conn, SQLDialect.H2);
        dsl.deleteFrom(EDGE)
           .where(EDGE.TYPE.eq(type))
           .and(EDGE.PARENT.eq((Long) oldRow[0]).or(EDGE.CHILD.eq((Long) oldRow[0])))
           .execute();
    }

    @Override
    public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type)
    throws SQLException {
        assert !before && type == DELETE : "this is an after delete trigger";
        this.type = switch (tableName.toLowerCase()) {
            case "object" -> Oracle.OBJECT_TYPE;
            case "relation" -> Oracle.RELATION_TYPE;
            case "subject" -> Oracle.SUBJECT_TYPE;
            default -> throw new IllegalArgumentException("Unexpected value: " + tableName.toLowerCase());
        };
    }

}
