/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for ScriptCompiler to prevent code injection attacks.
 *
 * @author hal.hildebrand
 */
public class ScriptCompilerSecurityTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectFileIOImport() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;
            import java.io.*;

            public class Evil {
                public void exploit() throws Exception {
                    new File("/etc/passwd").delete();
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("java.io") || ex.getMessage().contains("Forbidden"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectNetworkImport() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;
            import java.net.*;

            public class Evil {
                public void exploit() throws Exception {
                    new Socket("evil.com", 1337);
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("java.net") || ex.getMessage().contains("Forbidden"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectReflectionImport() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;
            import java.lang.reflect.*;

            public class Evil {
                public void exploit() throws Exception {
                    Class.forName("java.lang.Runtime");
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("reflect") || ex.getMessage().contains("Forbidden"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectSystemExit() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;

            public class Evil {
                public void exploit() {
                    System.exit(1);
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("System.exit") || ex.getMessage().contains("dangerous"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectRuntimeExec() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;

            public class Evil {
                public void exploit() throws Exception {
                    Runtime.getRuntime().exec("rm -rf /");
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("Runtime") || ex.getMessage().contains("dangerous"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectProcessBuilder() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;

            public class Evil {
                public void exploit() throws Exception {
                    new ProcessBuilder("ls", "-la").start();
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("ProcessBuilder") || ex.getMessage().contains("dangerous"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectClassForName() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;

            public class Evil {
                public void exploit() throws Exception {
                    Class.forName("javax.script.ScriptEngineManager");
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("Class.forName") || ex.getMessage().contains("dangerous"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectNativeKeyword() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;

            public class Evil {
                public native void exploit();
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("native") || ex.getMessage().contains("dangerous"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectInvalidClassName() {
        var compiler = new ScriptCompiler();
        var maliciousCode = "public class Valid {}";

        assertThrows(SecurityException.class,
            () -> compiler.getClass("../../../Evil", maliciousCode, getClass().getClassLoader()));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testRejectScriptingEngine() {
        var compiler = new ScriptCompiler();
        var maliciousCode = """
            package test;
            import javax.script.*;

            public class Evil {
                public void exploit() throws Exception {
                    new ScriptEngineManager().getEngineByName("javascript");
                }
            }
            """;

        var ex = assertThrows(SecurityException.class,
            () -> compiler.getClass("test.Evil", maliciousCode, getClass().getClassLoader()));
        assertTrue(ex.getMessage().contains("javax") || ex.getMessage().contains("Forbidden"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    public void testAllowValidCode() throws Exception {
        var compiler = new ScriptCompiler();
        var validCode = """
            package test;
            import java.sql.*;
            import java.util.*;

            public class Valid {
                public List<String> validMethod(Connection conn) throws SQLException {
                    return new ArrayList<>();
                }
            }
            """;

        // Should not throw exception
        var clazz = compiler.getClass("test.Valid", validCode, getClass().getClassLoader());
        assertNotNull(clazz);
        assertEquals("test.Valid", clazz.getName());
    }
}
