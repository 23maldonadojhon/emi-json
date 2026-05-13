package org.emi.json;

/**
 * Lanzada cuando el JSON de entrada tiene un formato inválido o mal formado.
 * Incluye información sobre la posición exacta del error en el flujo de bytes.
 * 
 * <p>Ejemplo:</p>
 * <pre>{@code
 * try {
 *     emi.parse("{\"incompleto\": ");
 * } catch (JsonParseException e) {
 *     System.err.println(e.getMessage()); // "Expected value at position 14"
 * }
 * }</pre>
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
