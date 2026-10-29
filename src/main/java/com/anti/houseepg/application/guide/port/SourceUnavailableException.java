package com.anti.houseepg.application.guide.port;

/** Raised by an adapter when a source cannot be read: network failure, bad status, unparseable body. */
public class SourceUnavailableException extends RuntimeException {

    public SourceUnavailableException(String message) {
        super(message);
    }

    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
