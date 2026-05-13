package org.emi.json;

/**
 * Interfaz funcional para la serialización optimizada de Records.
 * Implementada dinámicamente vía bytecode para llamadas directas.
 * 
 * <p>Ejemplo:</p>
 * <pre>{@code
 * StringBuilder sb = new StringBuilder();
 * serializer.serialize(sb, myRecord);
 * }</pre>
 */
@FunctionalInterface
public interface RecordSerializer {
    /**
     * Serializa el record escribiendo directamente en el StringBuilder.
     * @param sb Destino de la serialización.
     * @param record Instancia del Record a serializar.
     */
    void serialize(StringBuilder sb, Object record);
}
