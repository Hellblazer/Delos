/*
 * Copyright 2004-2019 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.state;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import deterministic.org.h2.api.ErrorCode;
import deterministic.org.h2.message.DbException;
import deterministic.org.h2.util.StringUtils;
import deterministic.org.h2.util.Utils;

/**
 * @author hal.hildebrand
 *
 */
public class ScriptCompiler {

    /**
     * Validates source code for security vulnerabilities before compilation.
     * Implements defense-in-depth against code injection attacks.
     */
    private static class SourceValidator {
        private static final Pattern VALID_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
        private static final int MAX_SOURCE_LENGTH = 100_000; // 100KB

        // Forbidden imports - packages that allow dangerous operations
        private static final Set<String> FORBIDDEN_IMPORTS = Set.of(
            "java.io", "java.nio", "java.net", "java.lang.reflect",
            "javax.script", "sun", "jdk", "com.sun"
        );

        // Dangerous patterns that could allow system compromise
        private static final Pattern DANGEROUS_PATTERNS = Pattern.compile(
            "\\b(Runtime\\.|ProcessBuilder|System\\.exit|System\\.setSecurityManager|" +
            "Class\\.forName|Method\\.invoke|Field\\.set|Constructor\\.newInstance|" +
            "native\\s+|ClassLoader|URLClassLoader|ScriptEngine)\\b"
        );

        /**
         * Validate identifier format (class or package name)
         */
        void validateIdentifier(String identifier, String type) {
            if (identifier == null || identifier.isEmpty()) {
                throw new SecurityException(type + " cannot be null or empty");
            }
            if (!VALID_IDENTIFIER.matcher(identifier).matches()) {
                throw new SecurityException("Invalid " + type + " format: " + identifier);
            }
        }

        /**
         * Validate source code for security issues
         */
        void validateSourceCode(String source) {
            if (source == null || source.isEmpty()) {
                throw new SecurityException("Source code cannot be null or empty");
            }
            if (source.length() > MAX_SOURCE_LENGTH) {
                throw new SecurityException("Source code exceeds maximum length of " + MAX_SOURCE_LENGTH + " characters");
            }

            // Check for forbidden imports
            var lines = source.lines().map(String::trim).toList();
            for (var line : lines) {
                if (line.startsWith("import ")) {
                    var importStmt = line.substring(7).replace(";", "").trim();
                    // Remove static keyword if present
                    if (importStmt.startsWith("static ")) {
                        importStmt = importStmt.substring(7).trim();
                    }
                    // Remove wildcard if present
                    if (importStmt.endsWith(".*")) {
                        importStmt = importStmt.substring(0, importStmt.length() - 2);
                    }

                    // Check against forbidden list
                    for (var forbidden : FORBIDDEN_IMPORTS) {
                        if (importStmt.equals(forbidden) || importStmt.startsWith(forbidden + ".")) {
                            throw new SecurityException("Forbidden import detected: " + importStmt +
                                " (forbidden: " + forbidden + ")");
                        }
                    }

                    // Special check for javax (except allowed ones)
                    if (importStmt.startsWith("javax.") && !importStmt.startsWith("javax.sql")) {
                        throw new SecurityException("Forbidden import detected: " + importStmt);
                    }
                }
            }

            // Check for dangerous patterns in the source
            var matcher = DANGEROUS_PATTERNS.matcher(source);
            if (matcher.find()) {
                throw new SecurityException("Dangerous pattern detected in source code: " + matcher.group());
            }
        }

        /**
         * Comprehensive validation of all inputs
         */
        void validate(String packageName, String className, String source) {
            if (packageName != null && !packageName.isEmpty()) {
                validateIdentifier(packageName, "package name");
            }
            validateIdentifier(className, "class name");
            validateSourceCode(source);
        }
    }

    /**
     * An in-memory class file manager.
     */
    static class ClassFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        /**
         * The class (only one class is kept).
         */
        JavaClassObject classObject;

        public ClassFileManager(StandardJavaFileManager standardManager) {
            super(standardManager);
        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return new SecureClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classObject.getBytes();
                    return super.defineClass(name, bytes, 0, bytes.length);
                }

                @Override
                protected URL findResource(String name) {
                    try {
                        return classObject.toUri().toURL();
                    } catch (MalformedURLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, Kind kind,
                                                   FileObject sibling) throws IOException {
            classObject = new JavaClassObject(className, kind);
            return classObject;
        }
    }

    /**
     * An in-memory java class object.
     */
    static class JavaClassObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public JavaClassObject(String name, Kind kind) {
            super(URI.create("string:///" + name.replace('.', '/') + kind.extension), kind);
        }

        public byte[] getBytes() {
            return out.toByteArray();
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return out;
        }
    }

    /**
     * An in-memory java source file object.
     */
    static class StringJavaFileObject extends SimpleJavaFileObject {

        private final String sourceCode;

        public StringJavaFileObject(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }

    }

    /**
     * The "com.sun.tools.javac.Main" (if available).
     */
    static final JavaCompiler JAVA_COMPILER;

    /**
     * Lock for thread-safe compilation (replaces synchronized keyword per coding standards)
     */
    private static final ReentrantLock COMPILER_LOCK = new ReentrantLock();

    private static final String COMPILE_DIR = Utils.getProperty("java.io.tmpdir", ".");
    private static final SourceValidator VALIDATOR = new SourceValidator();

    static {
        JavaCompiler c;
        try {
            c = ToolProvider.getSystemJavaCompiler();
        } catch (Exception e) {
            // ignore
            c = null;
        }
        JAVA_COMPILER = c;
    }

    /**
     * Get the complete source code (including package name, imports, and so on).
     *
     * @param packageName the package name
     * @param className   the class name
     * @param source      the (possibly shortened) source code
     * @return the full source code
     */
    static String getCompleteSourceCode(String packageName, String className, String source) {
        if (source.startsWith("package ")) {
            return source;
        }
        StringBuilder buff = new StringBuilder();
        if (packageName != null) {
            buff.append("package ").append(packageName).append(";\n");
        }
        int endImport = source.indexOf("@CODE");
        String importCode = "import java.util.*;\n" + "import java.math.*;\n" + "import java.sql.*;\n" + "import com.hellblazer.h2.*;\n";;
        if (endImport >= 0) {
            importCode = source.substring(0, endImport);
            source = source.substring("@CODE".length() + endImport);
        }
        buff.append(importCode);
        buff.append("public class ")
            .append(className)
            .append(" {\n" + "    public static ")
            .append(source)
            .append("\n" + "}\n");
        return buff.toString();
    }

    private static void handleSyntaxError(String output, int exitStatus) {
        if (0 == exitStatus) {
            return;
        }
        boolean syntaxError = false;
        final BufferedReader reader = new BufferedReader(new StringReader(output));
        try {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.endsWith("warning") || line.endsWith("warnings")) {
                    // ignore summary line
                } else if (line.startsWith("Note:") || line.startsWith("warning:")) {
                    // just a warning (e.g. unchecked or unsafe operations)
                } else {
                    syntaxError = true;
                    break;
                }
            }
        } catch (IOException ignored) {
            // exception ignored
        }

        if (syntaxError) {
            output = StringUtils.replaceAll(output, COMPILE_DIR, "");
            throw DbException.get(ErrorCode.SYNTAX_ERROR_1, output);
        }
    }

    /**
     * Get the class object for the given source.
     * 
     * @param packageAndClassName
     * @param source              - the source of the class
     *
     * @return the class
     */
    public Class<?> getClass(String packageAndClassName, String source,
                             ClassLoader parent) throws ClassNotFoundException {

        ClassLoader classLoader = new ClassLoader(parent) {

            @Override
            public Class<?> findClass(String name) throws ClassNotFoundException {
                String packageName = null;
                int idx = name.lastIndexOf('.');
                String className;
                if (idx >= 0) {
                    packageName = name.substring(0, idx);
                    className = name.substring(idx + 1);
                } else {
                    className = name;
                }
                String s = getCompleteSourceCode(packageName, className, source);
                s = source;
                return javaxToolsJavac(packageName, className, s);
            }
        };
        return classLoader.loadClass(packageAndClassName);
    }

    /**
     * Get the first public static method of the given class.
     *
     * @param className the class name
     * @return the method name
     */
    public Method getMethod(String className, String source, ClassLoader parent) throws ClassNotFoundException {
        var clazz = getClass(className, source, parent);
        var methods = clazz.getDeclaredMethods();
        // Sort methods deterministically by name to ensure consistent ordering across all replicas
        var sortedMethods = Arrays.stream(methods)
            .sorted(java.util.Comparator.comparing(Method::getName))
            .toList();
        for (var m : sortedMethods) {
            var modifiers = m.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) {
                var name = m.getName();
                if (!name.startsWith("_") && !m.getName().equals("main")) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Compile using the standard java compiler with security validation.
     *
     * @param packageName the package name
     * @param className   the class name
     * @param source      the source code
     * @return the class
     * @throws SecurityException if source code contains security violations
     */
    Class<?> javaxToolsJavac(String packageName, String className, String source) {
        // Validate inputs for security
        VALIDATOR.validate(packageName, className, source);

        String fullClassName = packageName == null ? className : packageName + "." + className;
        StringWriter writer = new StringWriter();
        try (JavaFileManager fileManager = new ClassFileManager(JAVA_COMPILER.getStandardFileManager(null, null,
                                                                                                     null))) {
            ArrayList<JavaFileObject> compilationUnits = new ArrayList<>();
            compilationUnits.add(new StringJavaFileObject(fullClassName, source));

            // Thread-safe compilation using ReentrantLock instead of synchronized
            final boolean ok;
            COMPILER_LOCK.lock();
            try {
                ok = JAVA_COMPILER.getTask(writer, fileManager, null, Arrays.asList("-target", "1.8", "-source", "1.8"),
                                           null, compilationUnits)
                                  .call();
            } finally {
                COMPILER_LOCK.unlock();
            }

            String output = writer.toString();
            handleSyntaxError(output, (ok ? 0 : 1));
            return fileManager.getClassLoader(null).loadClass(fullClassName);
        } catch (ClassNotFoundException | IOException e) {
            throw DbException.convert(e);
        }
    }

}
