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


    private final RecordSerializer serializer;
    private final RecordMetadata<T> meta;

    JsonSerializer(RecordMetadata<T> meta, boolean prettyPrint) {
        this.meta = meta;
        this.serializer = SerializerGenerator.generateSerializer(meta.targetClass, prettyPrint);
    }

    /**
     * Serializa una instancia de Record a un String JSON.
     * Utiliza un StringBuilder poolerizado para minimizar las asignaciones de memoria.
     * 
     * @param record La instancia del Record a serializar.
     * @return El JSON resultante como String.
     * 
     * <p>Ejemplo:</p>
     * <pre>{@code
     * String json = serializer.toJson(new User("Emi", 25)); // {"name":"Emi","age":25}
     * }</pre>
     */
    String toJson(T record) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);
        build(sb, record);
        return sb.toString();
    }


    /**
     * Serializa un Record directamente a un arreglo de bytes (UTF-8).
     * Esta versión es más eficiente que toJson().getBytes() ya que intenta
     * evitar copias intermedias de strings.
     * 
     * @param record La instancia a serializar.
     * @return El JSON en bytes UTF-8.
     * 
     * <p>Ejemplo:</p>
     * <pre>{@code
     * byte[] data = serializer.toBytes(user);
     * outputStream.write(data);
     * }</pre>
     */
    byte[] toBytes(T record) {
        StringBuilder sb = SB_POOL.get();
        sb.setLength(0);
        build(sb, record);
        
        // Optimización: Usamos Charset.encode directamente sobre el CharBuffer del StringBuilder.
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
        serializer.serialize(sb, record);
    }


    /**
     * Escribe el valor JSON correcto según el tipo de dato en tiempo de ejecución.
     * Soporta Strings, Booleanos, Números, Listas, Enums y otros Records (recursivo).
     * 
     * @param sb Destino de la serialización.
     * @param value El objeto a serializar.
     * 
     * <p>Ejemplo:</p>
     * <pre>{@code
     * JsonSerializer.staticAppendValue(sb, "Hola"); // Escribe "Hola"
     * JsonSerializer.staticAppendValue(sb, 123);    // Escribe 123
     * }</pre>
     */
    public static void staticAppendValue(StringBuilder sb, Object value) {
        switch (value) {
            case null          -> sb.append("null");
            case String s      -> appendEscapedString(sb, s);
            case Boolean b     -> sb.append(b);
            case Character c   -> appendEscapedString(sb, String.valueOf(c));
            case List<?> lst   -> appendList(sb, lst);
            case Enum<?> e     -> appendEscapedString(sb, e.name());
            case Record r      -> appendNestedRecord(sb, r);
            case LocalDate ld      -> appendLocalDate(sb, ld);
            case LocalDateTime ldt -> appendLocalDateTime(sb, ldt);
            case Instant inst      -> appendEscapedString(sb, inst.toString());

            default -> {
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
    private static void appendList(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            staticAppendValue(sb, list.get(i));
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
    private static void appendNativeArray(StringBuilder sb, Object arr) {
        sb.append('[');
        int len = Array.getLength(arr);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(',');
            staticAppendValue(sb, Array.get(arr, i));
        }
        sb.append(']');
    }

    /**
     * Serializa un Record anidado creando un JsonSerializer compacto para su tipo.
     * Delega en {@link #build} (package-private) para escribir directo en el StringBuilder
     * padre sin crear un String intermedio.
     */
    @SuppressWarnings("unchecked")
    private static void appendNestedRecord(StringBuilder sb, Record record) {
        var type = (Class<Object>) (Class<?>) record.getClass();
        JsonSerializer.of(RecordMetadata.of(type), false).build(sb, (Object) record);
    }

    private static void appendLocalDate(StringBuilder sb, LocalDate ld) {
        sb.append('"');
        appendPadded(sb, ld.getYear(), 4);
        sb.append('-');
        appendPadded(sb, ld.getMonthValue(), 2);
        sb.append('-');
        appendPadded(sb, ld.getDayOfMonth(), 2);
        sb.append('"');
    }

    private static void appendLocalDateTime(StringBuilder sb, LocalDateTime ldt) {
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

    private static void appendPadded(StringBuilder sb, int val, int width) {
        if (width == 4) {
            if (val < 1000) sb.append('0');
            if (val < 100) sb.append('0');
            if (val < 10) sb.append('0');
        } else if (width == 2) {
            if (val < 10) sb.append('0');
        }
        sb.append(val);
    }


    private static void appendEscapedString(StringBuilder sb, String s) {
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
