package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.stereotomy.event.AttachmentEvent;

public class AttachmentEventProcessingException extends RuntimeException {

    private static final long     serialVersionUID = 1L;
    private final AttachmentEvent attachmentEvent;

    public AttachmentEventProcessingException(AttachmentEvent attachmentEvent) {
        this.attachmentEvent = attachmentEvent;
    }

    public AttachmentEvent attachmentEvent() {
        return this.attachmentEvent;
    }

}
