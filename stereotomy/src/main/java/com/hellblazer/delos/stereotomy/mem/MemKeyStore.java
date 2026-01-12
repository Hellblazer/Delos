/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.mem;

import com.hellblazer.delos.stereotomy.KeyCoordinates;
import com.hellblazer.delos.stereotomy.StereotomyKeyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Destroyable;
import java.security.KeyPair;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author hal.hildebrand
 */
public class MemKeyStore implements StereotomyKeyStore {
    private static final Logger log = LoggerFactory.getLogger(MemKeyStore.class);

    private final Map<KeyCoordinates, KeyPair> keys        = new ConcurrentHashMap<>();
    private final Map<KeyCoordinates, KeyPair> nextKeys    = new ConcurrentHashMap<>();
    private final Map<String, KeyPair>         aliasedKeys = new ConcurrentHashMap<>();

    @Override
    public Optional<KeyPair> getKey(String alias) {
        return Optional.ofNullable(aliasedKeys.get(alias));
    }

    @Override
    public Optional<KeyPair> getKey(KeyCoordinates keyCoordinates) {
        return Optional.ofNullable(this.keys.get(keyCoordinates));
    }

    @Override
    public Optional<KeyPair> getNextKey(KeyCoordinates keyCoordinates) {
        return Optional.ofNullable(this.nextKeys.get(keyCoordinates));
    }

    @Override
    public void removeKey(KeyCoordinates keyCoordinates) {
        var removed = this.keys.remove(keyCoordinates);
        if (removed != null) {
            destroyKeyPair(removed);
        }
    }

    @Override
    public void removeKey(String alias) {
        var removed = aliasedKeys.remove(alias);
        if (removed != null) {
            destroyKeyPair(removed);
        }
    }

    @Override
    public void removeNextKey(KeyCoordinates keyCoordinates) {
        var removed = this.nextKeys.remove(keyCoordinates);
        if (removed != null) {
            destroyKeyPair(removed);
        }
    }

    /**
     * FIXED (CRIT-6): Destroy private key material when removing keys.
     *
     * If the private key implements Destroyable, explicitly destroy it to
     * prevent memory dumps from recovering the private key material.
     */
    private void destroyKeyPair(KeyPair keyPair) {
        if (keyPair == null) {
            return;
        }
        var privateKey = keyPair.getPrivate();
        if (privateKey instanceof Destroyable d) {
            try {
                // Check if already destroyed
                if (d.isDestroyed()) {
                    log.trace("Private key material already destroyed: {}", privateKey.getClass().getSimpleName());
                    return;
                }
                d.destroy();
                log.trace("Successfully destroyed private key material: {}", privateKey.getClass().getSimpleName());
            } catch (DestroyFailedException e) {
                // Some key implementations (e.g., BouncyCastle) don't support destruction
                // This is expected and not a security issue - the key will be GC'd normally
                log.debug("Key type {} does not support explicit destruction (expected for some implementations): {}",
                         privateKey.getClass().getSimpleName(), e.getMessage());
            } catch (Exception e) {
                // Unexpected exception during destroy
                log.warn("Unexpected error destroying private key material (type: {}): {}",
                        privateKey.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    @Override
    public void storeKey(String alias, KeyPair keyPair) {
        aliasedKeys.put(alias, keyPair);
    }

    @Override
    public void storeKey(KeyCoordinates coordinates, KeyPair keyPair) {
        this.keys.put(coordinates, keyPair);
    }

    @Override
    public void storeNextKey(KeyCoordinates coordinates, KeyPair keyPair) {
        this.nextKeys.put(coordinates, keyPair);
    }

}
