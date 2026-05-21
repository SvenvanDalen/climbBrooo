package nl.paree.climbpro.domain.route;

public class GpxParseException extends Exception {

    public GpxParseException(String message) {
        super(message);
    }

    public GpxParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
