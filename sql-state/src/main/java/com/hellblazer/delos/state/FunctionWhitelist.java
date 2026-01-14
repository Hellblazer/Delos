/*
 * Copyright (c) 2026 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.state;

import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces function whitelist for deterministic SQL execution in Byzantine fault-tolerant systems.
 * <p>
 * <strong>CRITICAL REQUIREMENT:</strong> All replicas must execute identical SQL with identical
 * results. Non-deterministic functions (RAND, CURRENT_TIMESTAMP, FILE_READ, etc.) cause consensus
 * divergence and must be rejected at parse time.
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * FunctionWhitelist.validate(sql);  // Throws SQLException if forbidden functions found
 * </pre>
 * <p>
 * Related: Delos-a5g5 (Function whitelist enforcement), Delos-21jp (H2 function audit)
 *
 * @author hal.hildebrand
 */
public class FunctionWhitelist {

    /**
     * Pattern to extract function calls from SQL.
     * Matches: FUNCTION_NAME(
     * Excludes: Table names, column names in FROM/JOIN clauses
     */
    private static final Pattern FUNCTION_PATTERN = Pattern.compile(
        "\\b([A-Z_][A-Z0-9_]*)\\s*\\(",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * FORBIDDEN FUNCTIONS - Non-deterministic operations.
     * <p>
     * These functions produce different results across replicas and MUST NOT be used:
     * <ul>
     *   <li><strong>Random/UUID:</strong> RAND, RANDOM, RANDOM_UUID, SECURE_RAND</li>
     *   <li><strong>Time:</strong> CURRENT_TIMESTAMP, NOW, SYSDATE, SYSTIMESTAMP, LOCALTIME, LOCALTIMESTAMP</li>
     *   <li><strong>System:</strong> MEMORY, MEMORY_FREE, MEMORY_USED, SESSION_ID</li>
     *   <li><strong>File I/O:</strong> FILE_READ, FILE_WRITE, CSVREAD, CSVWRITE, LINK_SCHEMA</li>
     *   <li><strong>Identity:</strong> IDENTITY, SCOPE_IDENTITY (use application-generated IDs)</li>
     *   <li><strong>Hash:</strong> HASH (uses Object.hashCode() which varies by JVM)</li>
     * </ul>
     * <p>
     * <strong>Use deterministic alternatives instead:</strong>
     * <ul>
     *   <li>Time: Use BlockClock via application layer (height + tx index)</li>
     *   <li>Random: Use SecureRandom with deterministic seed (via application layer)</li>
     *   <li>Identity: Generate IDs in application layer before SQL execution</li>
     * </ul>
     */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
        // Random/UUID
        "RAND", "RANDOM", "RANDOM_UUID", "SECURE_RAND",

        // Time (all wall-clock time functions)
        "CURRENT_TIMESTAMP", "NOW", "SYSDATE", "SYSTIMESTAMP",
        "CURRENT_TIME", "CURRENT_DATE", "LOCALTIME", "LOCALTIMESTAMP",
        "DATEADD", "DATEDIFF", "DAYNAME", "MONTHNAME",

        // System introspection
        "MEMORY", "MEMORY_FREE", "MEMORY_USED", "LOCK_TIMEOUT",
        "SESSION_ID", "TRANSACTION_ID", "LOCK_MODE",

        // File I/O
        "FILE_READ", "FILE_WRITE", "CSVREAD", "CSVWRITE",
        "LINK_SCHEMA", "COPY",

        // Identity/Sequence (non-deterministic across replicas)
        "IDENTITY", "SCOPE_IDENTITY", "CURRVAL", "NEXTVAL",

        // Hash (Object.hashCode varies)
        "HASH", "ORA_HASH",

        // Database metadata (varies per replica)
        "DATABASE", "DATABASE_PATH", "H2VERSION",

        // User/Session context (varies per connection)
        "USER", "CURRENT_USER", "SESSION_USER"
    );

    /**
     * ALLOWED FUNCTIONS - Deterministic operations safe for Byzantine consensus.
     * <p>
     * These functions produce identical results given identical inputs:
     * <ul>
     *   <li><strong>String:</strong> CONCAT, SUBSTRING, UPPER, LOWER, TRIM, LENGTH, REPLACE, etc.</li>
     *   <li><strong>Math:</strong> ABS, CEIL, FLOOR, ROUND, MOD, POWER, SQRT, etc.</li>
     *   <li><strong>Aggregates:</strong> SUM, COUNT, AVG, MIN, MAX, STDDEV, VARIANCE, etc.</li>
     *   <li><strong>Type conversion:</strong> CAST, CONVERT, TO_CHAR, TO_NUMBER, TO_DATE, etc.</li>
     *   <li><strong>Conditional:</strong> CASE, COALESCE, NULLIF, NVL, DECODE, etc.</li>
     * </ul>
     */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
        // String functions
        "CONCAT", "CONCAT_WS", "SUBSTRING", "SUBSTR", "LEFT", "RIGHT",
        "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM", "LENGTH", "CHAR_LENGTH",
        "REPLACE", "TRANSLATE", "INSERT", "REPEAT", "SPACE", "REVERSE",
        "POSITION", "LOCATE", "INSTR", "ASCII", "CHR", "CHAR",
        "LPAD", "RPAD", "SOUNDEX", "DIFFERENCE",

        // Math functions
        "ABS", "CEIL", "CEILING", "FLOOR", "ROUND", "TRUNCATE", "TRUNC",
        "MOD", "POWER", "SQRT", "EXP", "LN", "LOG", "LOG10",
        "DEGREES", "RADIANS", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN",
        "ATAN2", "SINH", "COSH", "TANH", "SIGN", "PI",

        // Aggregate functions
        "SUM", "COUNT", "AVG", "MIN", "MAX", "STDDEV_POP", "STDDEV_SAMP",
        "VAR_POP", "VAR_SAMP", "VARIANCE", "EVERY", "SOME", "ANY",
        "BIT_AND", "BIT_OR", "BIT_XOR", "BOOL_AND", "BOOL_OR",
        "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG", "MEDIAN",

        // Type conversion
        "CAST", "CONVERT", "TO_CHAR", "TO_NUMBER", "TO_DATE", "TO_TIMESTAMP",
        "HEXTORAW", "RAWTOHEX", "STRINGTOUTF8", "UTF8TOSTRING",

        // Conditional
        "CASE", "COALESCE", "NULLIF", "NVL", "NVL2", "DECODE",
        "IFNULL", "ISNULL", "GREATEST", "LEAST",

        // Date/Time (deterministic parsing, not current time)
        "DATE", "TIME", "TIMESTAMP", "PARSEDATETIME", "FORMATDATETIME",
        "EXTRACT", "YEAR", "QUARTER", "MONTH", "WEEK", "DAY",
        "HOUR", "MINUTE", "SECOND", "DAYOFWEEK", "DAYOFMONTH", "DAYOFYEAR",

        // Comparison
        "EQUALS", "GREATER", "LESS",

        // Bitwise
        "BITAND", "BITOR", "BITXOR", "BITNOT", "BITGET", "BITSET",

        // Array (deterministic operations)
        "ARRAY", "CARDINALITY",

        // Compression (deterministic)
        "COMPRESS", "EXPAND",

        // Encoding (deterministic)
        "ENCRYPT", "DECRYPT",

        // Table functions (some are deterministic)
        "TABLE", "UNNEST"
    );

    /**
     * Validates SQL statement against function whitelist.
     * <p>
     * Throws SQLException if any forbidden function is detected.
     *
     * @param sql SQL statement to validate
     * @throws SQLException if forbidden functions found or if sql is null
     */
    public static void validate(String sql) throws SQLException {
        if (sql == null || sql.isBlank()) {
            throw new SQLException("SQL statement cannot be null or empty");
        }

        String sqlUpper = sql.toUpperCase();

        // Check for forbidden functions that may not have parentheses
        for (String forbiddenFunc : FORBIDDEN_FUNCTIONS) {
            // Match word boundaries to avoid false positives
            Pattern pattern = Pattern.compile("\\b" + forbiddenFunc + "\\b");
            Matcher matcher = pattern.matcher(sqlUpper);
            if (matcher.find()) {
                // Additional context check: is it after FROM or JOIN? (table name, not function)
                int position = matcher.start();
                String before = sqlUpper.substring(Math.max(0, position - 10), position);
                if (before.matches(".*\\b(FROM|JOIN)\\s*$")) {
                    continue; // It's a table name, not a function
                }

                throw new SQLException(String.format(
                    "Forbidden non-deterministic function: %s\n" +
                    "This function produces different results across replicas, causing consensus divergence.\n" +
                    "\n" +
                    "Alternatives:\n" +
                    "  - For time: Use BlockClock via application layer (height + tx index)\n" +
                    "  - For random: Use SecureRandom with deterministic seed (via application layer)\n" +
                    "  - For identity: Generate IDs in application layer before SQL\n" +
                    "\n" +
                    "See h2-deterministic/RISKS.md for details.\n" +
                    "Related: Delos-a5g5 (Function whitelist enforcement)",
                    forbiddenFunc
                ));
            }
        }

        // Extract all function calls (with parentheses) from SQL
        Matcher matcher = FUNCTION_PATTERN.matcher(sql);
        while (matcher.find()) {
            String functionName = matcher.group(1).toUpperCase();
            int position = matcher.start();

            // Skip SQL keywords that look like functions but aren't
            if (isKeyword(functionName)) {
                continue;
            }

            // Get context before function name
            String before = sqlUpper.substring(Math.max(0, position - 10), position);

            // Check if function is explicitly forbidden (check BEFORE context checks)
            // Forbidden functions are never allowed, even in FROM/JOIN clauses
            if (FORBIDDEN_FUNCTIONS.contains(functionName)) {
                throw new SQLException(String.format(
                    "Forbidden non-deterministic function: %s()\n" +
                    "This function produces different results across replicas, causing consensus divergence.\n" +
                    "\n" +
                    "Alternatives:\n" +
                    "  - For time: Use BlockClock via application layer (height + tx index)\n" +
                    "  - For random: Use SecureRandom with deterministic seed (via application layer)\n" +
                    "  - For identity: Generate IDs in application layer before SQL\n" +
                    "\n" +
                    "See h2-deterministic/RISKS.md for details.\n" +
                    "Related: Delos-a5g5 (Function whitelist enforcement)",
                    functionName
                ));
            }

            // Skip if it's a table name (after FROM, JOIN, or INTO)
            if (before.matches(".*\\b(FROM|JOIN|INTO)\\s*$")) {
                continue;
            }

            // Skip if it's a type name in CAST/CONVERT
            if (isTypeName(functionName) && before.matches(".*\\b(AS|TO)\\s*$")) {
                continue;
            }

            // Check if function is explicitly allowed
            if (!ALLOWED_FUNCTIONS.contains(functionName)) {
                // Unknown function - conservative rejection
                throw new SQLException(String.format(
                    "Unknown function: %s()\n" +
                    "This function is not in the approved whitelist for deterministic execution.\n" +
                    "\n" +
                    "If this function is deterministic, add it to FunctionWhitelist.ALLOWED_FUNCTIONS.\n" +
                    "If non-deterministic, use application-layer alternative.\n" +
                    "\n" +
                    "Related: Delos-a5g5 (Function whitelist enforcement)",
                    functionName
                ));
            }
        }
    }

    /**
     * Checks if a token is a SQL type name (not a function).
     */
    private static boolean isTypeName(String token) {
        return switch (token) {
            case "INTEGER", "INT", "BIGINT", "SMALLINT", "TINYINT",
                 "DECIMAL", "NUMERIC", "FLOAT", "REAL", "DOUBLE",
                 "VARCHAR", "CHAR", "CLOB", "BLOB", "BINARY", "VARBINARY",
                 "DATE", "TIME", "TIMESTAMP", "BOOLEAN", "BIT",
                 "ARRAY", "UUID", "JSON", "GEOMETRY" -> true;
            default -> false;
        };
    }

    /**
     * Checks if a token is a SQL keyword (not a function).
     * <p>
     * Keywords that may be followed by '(' but are not functions:
     * SELECT, INSERT, UPDATE, DELETE, FROM, WHERE, JOIN, etc.
     */
    private static boolean isKeyword(String token) {
        return switch (token) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE",
                 "JOIN", "INNER", "OUTER", "LEFT", "RIGHT", "FULL", "CROSS",
                 "ON", "USING", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET",
                 "UNION", "INTERSECT", "EXCEPT", "WITH", "AS", "DISTINCT",
                 "ALL", "EXISTS", "IN", "NOT", "AND", "OR", "BETWEEN",
                 "VALUES", "SET", "CREATE", "ALTER", "DROP", "TRUNCATE",
                 "GRANT", "REVOKE", "BEGIN", "COMMIT", "ROLLBACK",
                 "IF", "THEN", "ELSE", "END", "LOOP", "WHILE", "FOR" -> true;
            default -> false;
        };
    }

    /**
     * Returns immutable set of forbidden functions for testing/documentation.
     */
    public static Set<String> getForbiddenFunctions() {
        return Set.copyOf(FORBIDDEN_FUNCTIONS);
    }

    /**
     * Returns immutable set of allowed functions for testing/documentation.
     */
    public static Set<String> getAllowedFunctions() {
        return Set.copyOf(ALLOWED_FUNCTIONS);
    }
}
