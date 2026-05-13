package org.emi.json;

/**
 * Interfaz funcional para la serialización de Records de forma optimizada.
 * El código generado por Class-File API implementará esta interfaz para
 * realizar llamadas directas a los accessors del Record.
 */
@FunctionalInterface
public interface RecordSerializer {
    /**
     * Serializa el record escribiendo directamente en el StringBuilder.
     */
    void serialize(StringBuilder sb, Object record);
}
