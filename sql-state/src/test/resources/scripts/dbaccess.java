/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package test;

import java.sql.*;
import com.hellblazer.delos.h2.*;

public class DbAccess {
    
    public ResultSet call(Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        return statement.executeQuery("select * from s.books");
    }

    public ResultSet callWitServices(Connection connection, SessionServices services) throws Exception {
        Statement statement = connection.createStatement();
        services.call("foo", "hello world");
        return statement.executeQuery("select * from s.books");
    }
}
