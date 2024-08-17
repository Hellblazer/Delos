package com.hellblazer.delos.archipelago.client;

import com.macasaet.fernet.Token;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface AsyncTokenProvider {
    CompletableFuture<Token> get();
}
