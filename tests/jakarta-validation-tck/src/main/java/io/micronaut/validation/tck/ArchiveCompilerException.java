package io.micronaut.validation.tck;

/**
 * The exception indicates internal compiler error.
 *
 * @author Denis Stepanov
 */
public class ArchiveCompilerException extends Exception {
    public ArchiveCompilerException(String message) {
        super(message);
    }

    public ArchiveCompilerException(String message, Throwable cause) {
        super(message, cause);
    }
}
