package com.hellblazer.delos.stereotomy.processing;

public class InvalidKeyEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidKeyEventException() {
        super();
    }

    public InvalidKeyEventException(String message) {
        super(message);
    }

    public InvalidKeyEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidKeyEventException(Throwable cause) {
        super(cause);
    }

}
