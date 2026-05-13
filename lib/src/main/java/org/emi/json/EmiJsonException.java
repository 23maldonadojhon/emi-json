package org.emi.json;

/**
 * Excepción base para todos los errores de la librería EmiJson.
 * Proporciona una raíz común para capturar cualquier falla de la librería.
 */
public class EmiJsonException extends RuntimeException {
    public EmiJsonException(String message) {
        super(message);
    }

    public EmiJsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
