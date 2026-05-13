package org.emi.json;

/**
 * Excepción base para todos los errores de la librería EmiJson.
 */
public class EmiJsonException extends RuntimeException {
    public EmiJsonException(String message) {
        super(message);
    }

    public EmiJsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
