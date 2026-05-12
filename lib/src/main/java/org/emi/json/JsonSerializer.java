package org.emi.json;

import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Serializa una instancia de Java Record a JSON en formato compacto o pretty-print.
 * El modo (compacto vs. pretty) se resuelve una sola vez en el constructor, de forma
 * que {@link #build} no evalúa ningún booleano en cada llamada.
 *
 * <p>Thread-safe: todos los campos son finales; el estado mutable (StringBuilder)
 * es local al stack de cada invocación.
 */
final class JsonSerializer<T> {

    private static final String[] CTRL_ESCAPES = new String[32];
    static {
        for (int i = 0; i < 32; i++) {
            CTRL_ESCAPES[i] = String.format("\\u%04x", i);
        }
    }

    private static final ConcurrentHashMap<String, JsonSerializer<?>> CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<StringBuilder> SB_POOL = ThreadLocal.withInitial(() -> new StringBuilder(4096));

    /**
     * Obtiene o crea un serializador cacheado para el tipo y modo especificados.
     */
    @SuppressWarnings("unchecked")
    static <T> JsonSerializer<T> of(RecordMetadata<T> meta, boolean prettyPrint) {
        String key = meta.targetClass.getName() + (prettyPrint ? "_P" : "_C");
        return (JsonSerializer<T>) CACHE.computeIfAbsent(key, k -> new JsonSerializer<>(meta, prettyPrint));
    }


    private final RecordMetadata<T> meta;

    // Apuntan a compactKeys o prettyKeys de RecordMetadata según el modo elegido.
    // Asignar aquí evita la bifurcación if(prettyPrint) dentro del loop caliente.
    private final String[] keys;
    private final String   sep;    // "," o ",\n"
    private final String   open;   // "{" o "{\n"
    private final String   close;  // "}" o "\n}"

    JsonSerializer(RecordMetadata<T> meta, boolean prettyPrint) {
        this.meta  = meta;
        this.keys  = prettyPrint ? meta.prettyKeys : meta.compactKeys;
        this.sep   = prettyPrint ? ",\n"           : ",";
        this.open  = prettyPrint ? "{\n"           : "{";
        this.close = prettyPrint ? "\n}"           : "}";
    }

    /**
     * Serializa {@code record} a un String JSON.
     * Usa la capacidad inicial pre-calculada de RecordMetadata para evitar
     * el primer resize del StringBuilder en la mayoría de los casos.
     */
    String toJson(T record) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);
        build(sb, record);
        return sb.toString();
    }


    /**
     * Serializa {@code record} directamente a {@code byte[]} UTF-8 sin crear un
     * String intermedio. Útil cuando el destino es un socket o buffer de red.
     * {@code CharsetEncoder.encode(CharBuffer)} opera sobre el CharBuffer del
     * StringBuilder sin copiar a String, reduciendo una allocation respecto a
     * {@code toJson(record).getBytes(UTF_8)}.
     */
    byte[] toBytes(T record) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);
        build(sb, record);
        
        // Optimización: Usamos Charset.encode directamente sobre el CharBuffer del StringBuilder.
        // Aunque aún hay un ByteBuffer temporal interno en encode(), evitamos el String intermedio.
        ByteBuffer buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(sb));
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }


    /**
     * Núcleo de la serialización: recorre los componentes del Record en orden
     * de declaración, añadiendo la key pre-computada y el valor de cada campo.
     */
    void build(StringBuilder sb, T record) {
        int n = keys.length;
        sb.append(open);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(sep);
            sb.append(keys[i]);                        // "\"name\":" ya preparado
            appendJsonValue(sb, invokeAccessor(i, record));
        }
        sb.append(close);
    }

    /**
     * Invoca el accessor del Record para el campo {@code index} vía MethodHandle.
     * El MethodHandle fue obtenido con unreflect() en RecordMetadata, por lo que el JIT
     * puede inlinearlo igual que una llamada directa al método.
     */
    private Object invokeAccessor(int index, T record) {
        try {
            return meta.accessorHandles.get(index).invoke(record);
        } catch (Throwable t) {
            throw new RuntimeException("Serialization failed at: " + meta.components.get(index).getName(), t);
        }
    }

    /**
     * Escribe el valor JSON correcto según el tipo:
     * - String recibe comillas envolventes.
     * - Boolean y números se escriben tal cual (toString implícito de StringBuilder).
     * - null se escribe literalmente como "null".
     * El patrón switch sobre el tipo en runtime es más legible y seguro que instanceof encadenado.
     */
    private void appendJsonValue(StringBuilder sb, Object value) {
        switch (value) {
            case null          -> sb.append("null");
            case String s      -> appendEscapedString(sb, s);
            case Boolean b     -> sb.append(b);
            case Character c   -> appendEscapedString(sb, String.valueOf(c));
            case List<?> lst   -> appendList(sb, lst);
            case Enum<?> e     -> appendEscapedString(sb, e.name());
            case Record r      -> appendNestedRecord(sb, r);
            // Tipos fecha/hora: Optimizados para evitar .toString() y la creación de Strings intermedios.
            case LocalDate ld      -> appendLocalDate(sb, ld);
            case LocalDateTime ldt -> appendLocalDateTime(sb, ldt);
            case Instant inst      -> appendEscapedString(sb, inst.toString());

            default -> {
                // Arrays nativos (int[], String[], etc.) necesitan serialización propia;
                // los demás tipos (Number y subclases) se escriben con toString() sin comillas.
                if (value.getClass().isArray()) appendNativeArray(sb, value);
                else sb.append(value);
            }
        }
    }

    /**
     * Serializa una List como array JSON {@code [elem1,elem2,...]}.
     * Cada elemento se delega recursivamente a {@link #appendJsonValue} para
     * manejar correctamente strings, booleanos, números y listas anidadas.
     */
    private void appendList(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            appendJsonValue(sb, list.get(i));
        }
        sb.append(']');
    }

    /**
     * Escribe un String como valor JSON con las secuencias de escape obligatorias.
     * RFC 8259 §7: U+0022 ({@code "}), U+005C ({@code \}) y U+0000–U+001F deben escaparse;
     * el resto se escribe tal cual (incluye UTF-16 surrogate pairs que Java maneja en charAt).
     */
    /**
     * Serializa un array nativo (int[], String[], etc.) como array JSON.
     * Array.get autoboxea primitivos, por lo que cada elemento pasa por appendJsonValue
     * y recibe el tratamiento correcto (String con comillas, número sin ellas, etc.).
     */
    private void appendNativeArray(StringBuilder sb, Object arr) {
        sb.append('[');
        int len = Array.getLength(arr);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(',');
            appendJsonValue(sb, Array.get(arr, i));
        }
        sb.append(']');
    }

    /**
     * Serializa un Record anidado creando un JsonSerializer compacto para su tipo.
     * Delega en {@link #build} (package-private) para escribir directo en el StringBuilder
     * padre sin crear un String intermedio.
     */
    @SuppressWarnings("unchecked")
    private void appendNestedRecord(StringBuilder sb, Record record) {
        // Doble cast (Class<?>) → (Class<Object>) necesario: getClass() retorna Class<? extends Record>,
        // que Java no permite asignar directamente a Class<Object> sin pasar por el tipo wildcard.
        var type = (Class<Object>) (Class<?>) record.getClass();
        JsonSerializer.of(RecordMetadata.of(type), false).build(sb, (Object) record);
    }

    private void appendLocalDate(StringBuilder sb, LocalDate ld) {
        sb.append('"');
        appendPadded(sb, ld.getYear(), 4);
        sb.append('-');
        appendPadded(sb, ld.getMonthValue(), 2);
        sb.append('-');
        appendPadded(sb, ld.getDayOfMonth(), 2);
        sb.append('"');
    }

    private void appendLocalDateTime(StringBuilder sb, LocalDateTime ldt) {
        sb.append('"');
        appendPadded(sb, ldt.getYear(), 4);
        sb.append('-');
        appendPadded(sb, ldt.getMonthValue(), 2);
        sb.append('-');
        appendPadded(sb, ldt.getDayOfMonth(), 2);
        sb.append('T');
        appendPadded(sb, ldt.getHour(), 2);
        sb.append(':');
        appendPadded(sb, ldt.getMinute(), 2);
        sb.append(':');
        appendPadded(sb, ldt.getSecond(), 2);
        int nano = ldt.getNano();
        if (nano > 0) {
            sb.append('.');
            sb.append(nano);
        }
        sb.append('"');
    }

    private void appendPadded(StringBuilder sb, int val, int width) {
        if (width == 4) {
            if (val < 1000) sb.append('0');
            if (val < 100) sb.append('0');
            if (val < 10) sb.append('0');
        } else if (width == 2) {
            if (val < 10) sb.append('0');
        }
        sb.append(val);
    }


    private void appendEscapedString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default   -> {
                    if (c < 0x20) sb.append(CTRL_ESCAPES[c]);
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
