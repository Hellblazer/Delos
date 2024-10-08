package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.event.KeyEvent;

public abstract class KeyEventProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final KeyEvent    keyEvent;

    public KeyEventProcessingException(KeyEvent keyEvent, String message) {
        super(message);
        this.keyEvent = keyEvent;
    }

    public KeyEvent keyEvent() {
        return keyEvent;
    }

}
