package org.emi.json;

/**
 * Lanzada cuando el JSON de entrada tiene un formato inválido o mal formado.
 */
public class JsonParseException extends EmiJsonException {
    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, long position) {
        super(String.format("%s at position %d", message, position));
    }

    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
