/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.demesnes.isolate;

import io.netty.channel.socket.nio.NioDomainSocketChannel;
import io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM Feature to register NIO domain socket channels for reflection.
 * This is required because the community metadata repository provides outdated Netty metadata
 * (4.1.80) that predates NioDomainSocketChannel (added in 4.1.91).
 *
 * @author hal.hildebrand
 */
public class NioDomainSocketFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        try {
            // Register NioDomainSocketChannel for reflection
            Class<?> nioDomainSocketChannelClass = NioDomainSocketChannel.class;
            RuntimeReflection.register(nioDomainSocketChannelClass);

            // Explicitly register the no-arg constructor that Netty's ReflectiveChannelFactory uses
            RuntimeReflection.register(nioDomainSocketChannelClass.getDeclaredConstructor());

            // Also register all other constructors for completeness
            RuntimeReflection.register(nioDomainSocketChannelClass.getDeclaredConstructors());

            System.out.println("[NioDomainSocketFeature] Registered " +
                             nioDomainSocketChannelClass.getDeclaredConstructors().length +
                             " constructors for NioDomainSocketChannel");

            // Register NioServerDomainSocketChannel for reflection
            Class<?> nioServerDomainSocketChannelClass = NioServerDomainSocketChannel.class;
            RuntimeReflection.register(nioServerDomainSocketChannelClass);

            // Explicitly register the no-arg constructor
            RuntimeReflection.register(nioServerDomainSocketChannelClass.getDeclaredConstructor());

            // Also register all other constructors for completeness
            RuntimeReflection.register(nioServerDomainSocketChannelClass.getDeclaredConstructors());

            System.out.println("[NioDomainSocketFeature] Registered " +
                             nioServerDomainSocketChannelClass.getDeclaredConstructors().length +
                             " constructors for NioServerDomainSocketChannel");

            System.out.println("[NioDomainSocketFeature] Successfully registered NIO domain socket channels for reflection");
        } catch (Exception e) {
            System.err.println("[NioDomainSocketFeature] Failed to register reflection: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to register NIO domain socket channels", e);
        }
    }

    @Override
    public String getDescription() {
        return "Registers NIO domain socket channels (JEP 380) for reflection in isolates";
    }
}
