package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.event.KeyEvent;

public class UnmetSigningThresholdException extends KeyEventProcessingException {

    private static final long serialVersionUID = 1L;

    public UnmetSigningThresholdException(KeyEvent keyEvent) {
        super(keyEvent, String.format("Did not meet the signing threshold for: %s", keyEvent));
    }

}
