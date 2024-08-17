package com.hellblazer.delos.archipelago.client;

import com.macasaet.fernet.Token;

@FunctionalInterface
public interface SynchronousTokenProvider {
    Token get();
}
