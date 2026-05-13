package org.emi.json;

/**
 * Lanzada cuando el JSON es válido pero no puede ser mapeado al Java Record destino.
 * Esto ocurre por tipos incompatibles, campos requeridos faltantes o errores en
 * la generación de bytecode.
 * 
 * <p>Ejemplo:</p>
 * <pre>{@code
 * try {
 *     emi.parse("{\"edad\": \"no_soy_un_numero\"}");
 * } catch (JsonMappingException e) {
 *     System.err.println("Error de mapeo: " + e.getMessage());
 * }
 * }</pre>
 */
public class JsonMappingException extends EmiJsonException {
    public JsonMappingException(String message) {
        super(message);
    }

    public JsonMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
