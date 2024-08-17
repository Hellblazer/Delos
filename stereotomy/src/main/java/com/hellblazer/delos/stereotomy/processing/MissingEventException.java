package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.KeyEvent;

public class MissingEventException extends KeyEventProcessingException {

    private static final long      serialVersionUID = 1L;
    private final EventCoordinates missingEvent;

    public MissingEventException(KeyEvent dependingEvent, EventCoordinates missingEvent) {
        super(dependingEvent, String.format("Missing event: %s for: %s", missingEvent, dependingEvent));
        this.missingEvent = missingEvent;
    }

    public EventCoordinates missingEvent() {
        return this.missingEvent;
    }

}
