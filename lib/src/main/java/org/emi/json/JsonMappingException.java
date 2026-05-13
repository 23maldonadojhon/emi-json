package org.emi.json;

/**
 * Lanzada cuando el JSON es válido pero no puede ser mapeado al Java Record destino
 * (por ejemplo, tipos incompatibles o campos requeridos ausentes).
 */
public class JsonMappingException extends EmiJsonException {
    public JsonMappingException(String message) {
        super(message);
    }

    public JsonMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
