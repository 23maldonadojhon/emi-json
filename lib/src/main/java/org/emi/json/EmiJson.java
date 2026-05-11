package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * Punto de entrada público para serialización y deserialización JSON de Java Records.
 * Delega toda la lógica en {@link JsonParser} y {@link JsonSerializer}, ambos
 * compartiendo la misma instancia de {@link RecordMetadata} para evitar inicializar
 * los MethodHandles dos veces.
 *
 * <p>Las instancias son completamente thread-safe: todos los campos son finales
 * y los colaboradores internos no mantienen estado mutable entre llamadas.
 *
 * <h3>Uso rápido (una sola operación):</h3>
 * <pre>{@code
 * MyRecord r = EmiJson.of(MyRecord.class).parse(jsonString);
 * }</pre>
 *
 * <h3>Uso reutilizable (múltiples operaciones, recomendado):</h3>
 * <pre>{@code
 * EmiJson<MyRecord> emi = EmiJson.of(MyRecord.class).strict().build();
 * MyRecord r  = emi.parse(jsonString);
 * String   s  = emi.toJson(r);
 * byte[]   bs = emi.toBytes(r);   // sin String intermedio
 * }</pre>
 */
public class EmiJson<T> {

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Punto de entrada del Builder. Solo acepta clases que sean Java Records;
     * lanza IllegalArgumentException en caso contrario para fallar rápido en
     * tiempo de configuración, no durante el primer parse.
     */
    public static <T extends Record> Builder<T> of(Class<T> type) {
        return new Builder<>(type);
    }

    /**
     * Constructor fluido de EmiJson. Permite configurar el comportamiento antes
     * de construir la instancia que será reutilizada en el camino caliente.
     */
    public static final class Builder<T extends Record> {
        private final Class<T> type;
        private boolean strict     = false;
        private boolean prettyPrint = false;

        private Builder(Class<T> type) {
            Objects.requireNonNull(type, "type");
            if (!type.isRecord())
                throw new IllegalArgumentException("Target must be a Java Record: " + type.getName());
            this.type = type;
        }

        /**
         * Activa el modo estricto: lanza excepción si algún campo del Record
         * está ausente en el JSON. Por defecto los campos ausentes quedan en su
         * valor cero (null, 0, false, etc.).
         */
        public Builder<T> strict() {
            this.strict = true;
            return this;
        }

        /**
         * Activa el formato pretty-print en toJson(): añade saltos de línea e
         * indentación de dos espacios. Sin efecto sobre el parsing.
         */
        public Builder<T> prettyPrint() {
            this.prettyPrint = true;
            return this;
        }

        /**
         * Construye la instancia de EmiJson con los metadatos ya inicializados.
         * Preferir {@code build()} cuando la misma instancia se use más de una vez
         * para reutilizar los MethodHandles sin recalcularlos.
         */
        public EmiJson<T> build() {
            return new EmiJson<>(type, strict, prettyPrint);
        }

        /** Atajo para uso de un solo disparo; equivale a {@code build().parse(json)}. */
        public T parse(String json) {
            return build().parse(json);
        }

        /** Atajo para uso de un solo disparo; equivale a {@code build().toJson(record)}. */
        public String toJson(T record) {
            return build().toJson(record);
        }
    }

    // -------------------------------------------------------------------------
    // Facade
    // -------------------------------------------------------------------------
    private final JsonParser<T>     parser;
    private final JsonSerializer<T> serializer;

    /** Constructor de compatibilidad con modo compacto, no-estricto. */
    public EmiJson(Class<T> targetClass) {
        this(targetClass, false, false);
    }

    private EmiJson(Class<T> targetClass, boolean strict, boolean prettyPrint) {
        if (!targetClass.isRecord())
            throw new IllegalArgumentException("Target must be a Java Record");
        // RecordMetadata se construye una sola vez y ambos colaboradores lo comparten.
        var meta        = new RecordMetadata<>(targetClass);
        this.parser     = new JsonParser<>(meta, strict);
        this.serializer = new JsonSerializer<>(meta, prettyPrint);
    }

    /** Parsea un objeto JSON desde String. */
    public T parse(String json) {
        return parser.parse(json);
    }

    /**
     * Parsea desde un MemorySegment ya disponible (p. ej. buffer de red o archivo mapeado).
     * Evita la copia a byte[] que hace el overload String.
     */
    public T parse(MemorySegment segment) {
        return parser.parse(segment);
    }

    /** Serializa {@code record} a un String JSON. */
    public String toJson(T record) {
        return serializer.toJson(record);
    }

    /**
     * Serializa {@code record} directamente a {@code byte[]} UTF-8.
     * Más eficiente que {@code toJson(record).getBytes(UTF_8)} porque omite
     * la creación del String intermedio.
     */
    public byte[] toBytes(T record) {
        return serializer.toBytes(record);
    }
}
