package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.event.KeyEvent;

public class UnmetWitnessThresholdException extends KeyEventProcessingException {

    private static final long serialVersionUID = 1L;

    public UnmetWitnessThresholdException(KeyEvent keyEvent) {
        super(keyEvent, String.format("Unmet witness threshold for: %s", keyEvent));
    }

}
