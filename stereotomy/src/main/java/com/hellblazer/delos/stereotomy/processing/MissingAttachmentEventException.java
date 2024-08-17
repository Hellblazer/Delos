package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;

public class MissingAttachmentEventException extends RuntimeException {

    private static final long      serialVersionUID = 1L;
    private final EventCoordinates missingEvent;
    private final AttachmentEvent  attachment;

    public MissingAttachmentEventException(AttachmentEvent attachment, EventCoordinates missingEvent) {
        this.attachment = attachment;
        this.missingEvent = missingEvent;
    }

    public EventCoordinates missingEvent() {
        return this.missingEvent;
    }

    public AttachmentEvent attachement() {
        return attachment;
    }

}
