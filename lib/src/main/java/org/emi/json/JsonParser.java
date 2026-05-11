package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Deserializa JSON plano (objeto de un nivel) a una instancia de Java Record.
 * Diseñado para cero allocations en el camino caliente de las keys: nunca crea
 * un String para comparar nombres de campo; trabaja directamente en bytes del
 * MemorySegment contra los bytes pre-codificados en RecordMetadata.
 *
 * <p>Es thread-safe: todos los campos son finales y el estado de cada parseo
 * (args, seen) es local al stack del llamante.
 */
final class JsonParser<T> {

    // Tabla de 256 entradas: lookup O(1) para decidir si un byte termina un valor
    // no-string (número, booleano, null). Más rápido que múltiples comparaciones ==.
    private static final boolean[] IS_DELIMITER = new boolean[256];

    static {
        // Whitespace ASCII (0-32), coma y llave de cierre terminan valores primitivos.
        for (int i = 0; i <= 32; i++)
            IS_DELIMITER[i] = true;
        IS_DELIMITER[','] = true;
        IS_DELIMITER['}'] = true;
    }

    private final RecordMetadata<T> meta;
    private final boolean strict;

    JsonParser(RecordMetadata<T> meta, boolean strict) {
        this.meta   = meta;
        this.strict = strict;
    }

    /**
     * Convierte el String a bytes UTF-8 y delega al overload MemorySegment.
     * La conversión getBytes() es la única allocation inevitable en este camino:
     * el String de Java no expone acceso directo a sus bytes internos.
     */
    T parse(String json) {
        return parse(MemorySegment.ofArray(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Parsea el JSON desde un MemorySegment ya disponible, sin copia adicional.
     * Usar este overload cuando el origen sea un buffer de red o un archivo mapeado.
     *
     * <p>El loop principal alterna entre buscar keys (delimitadas por {@code "})
     * y valores (string o primitivo). Las posiciones son índices de byte absolutos
     * dentro del segmento; VectorScanner las avanza con SIMD.
     */
    T parse(MemorySegment segment) {
        long size = segment.byteSize();
        Object[] args = meta.initArgs();           // valores cero por campo
        var seen = new BitSet(meta.components.size());

        long pos = VectorScanner.skipWhitespace(segment, 0);

        try {
            if (segment.get(ValueLayout.JAVA_BYTE, pos) != '{')
                throw new RuntimeException("Invalid JSON start");

            while (pos < size) {
                pos = VectorScanner.findDelimiter(segment, pos, (byte) '"');
                if (pos == -1) break;

                // La key está entre las dos comillas: comparamos bytes in-place,
                // sin construir ningún String para el nombre del campo.
                long keyStart = ++pos;
                pos = VectorScanner.findDelimiter(segment, pos, (byte) '"');
                int idx = findComponentIndex(segment, keyStart, pos);

                pos = VectorScanner.findDelimiter(segment, pos, (byte) ':');
                pos = VectorScanner.skipWhitespace(segment, pos + 1);

                byte firstByte = segment.get(ValueLayout.JAVA_BYTE, pos);
                if (firstByte == '"') {
                    // Valor string: delimitado por comillas; pos avanza sobre la comilla inicial.
                    long valStart = ++pos;
                    pos = findStringEnd(segment, pos, size);
                    assignValue(args, seen, idx, extractStringEscaped(segment, valStart, pos));
                } else if (firstByte == '{') {
                    // Valor objeto: parseo recursivo si el tipo destino es también un Record.
                    long objEnd = findObjectEnd(segment, pos, size);
                    if (idx >= 0) {
                        Class<?> nestedType = meta.components.get(idx).getType();
                        if (nestedType.isRecord()) {
                            String raw = extractString(segment, pos, objEnd + 1);
                            args[idx] = parseNestedRecord(raw, nestedType);
                            seen.set(idx);
                        }
                    }
                    pos = objEnd;
                } else if (firstByte == '[') {
                    // Valor array: busca el ']' de cierre respetando anidamiento,
                    // extrae el bloque raw y lo convierte a List<?> o array nativo según el tipo destino.
                    long arrEnd = findArrayEnd(segment, pos, size);
                    if (idx >= 0) {
                        String raw = extractString(segment, pos, arrEnd + 1);
                        Class<?> fieldType = meta.components.get(idx).getType();
                        List<Object> list  = parseArrayValue(raw, meta.genericTypes[idx]);
                        args[idx] = fieldType.isArray()
                                ? toNativeArray(list, fieldType.getComponentType())
                                : list;
                        seen.set(idx);
                    }
                    pos = arrEnd;
                } else {
                    // Valor primitivo (número, booleano, null): termina en el primer delimitador.
                    long valStart = pos;
                    while (pos < size && !isJsonDelimiter(segment.get(ValueLayout.JAVA_BYTE, pos)))
                        pos++;
                    assignValue(args, seen, idx, extractString(segment, valStart, pos));
                    pos--; // retrocede uno para que el pos++ final quede sobre el delimitador
                }
                pos++;
            }
        } catch (Throwable t) {
            throw new RuntimeException("Parsing failed at byte position: " + pos, t);
        }

        if (strict) meta.validateAllPresent(seen);
        try {
            return meta.targetClass.cast(meta.constructorHandle.invokeWithArguments(args));
        } catch (Throwable t) {
            throw new RuntimeException("Constructor invocation failed for " + meta.targetClass.getSimpleName(), t);
        }
    }

    /**
     * Busca el índice del componente cuyo nombre en bytes coincide exactamente con
     * el rango [start, end) del segmento. Retorna -1 si la key no pertenece al Record
     * (campo desconocido), en cuyo caso assignValue lo descarta silenciosamente.
     *
     * <p>La comparación de longitud primero ({@code names[i].length != len}) evita
     * iterar sobre los bytes cuando el tamaño ya no coincide, que es el caso frecuente
     * en un JSON con muchos campos de nombres distintos.
     */
    private int findComponentIndex(MemorySegment segment, long start, long end) {
        int len = (int) (end - start);
        byte[][] names = meta.componentNameBytes;
        outer:
        for (int i = 0; i < names.length; i++) {
            if (names[i].length != len) continue;
            for (int j = 0; j < len; j++) {
                if (segment.get(ValueLayout.JAVA_BYTE, start + j) != names[i][j])
                    continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Materializa un String desde el rango [start, end) del segmento.
     * Es la única allocation de objeto en el camino caliente; no hay forma de
     * evitarla porque el Record espera un String, no bytes crudos.
     */
    private String extractString(MemorySegment segment, long start, long end) {
        return new String(segment.asSlice(start, end - start).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    /**
     * Asigna el valor ya convertido en el slot correspondiente de args y marca el campo
     * como visto en el BitSet. Si {@code index < 0} (key desconocida), no hace nada.
     */
    private void assignValue(Object[] args, BitSet seen, int index, String rawValue) {
        if (index < 0) return;
        args[index] = convertType(rawValue.trim(), meta.components.get(index).getType());
        seen.set(index);
    }

    /**
     * Convierte el texto crudo del JSON al tipo Java del componente.
     * Los tipos no reconocidos se dejan como String (comportamiento pass-through).
     */
    private Object convertType(String val, Class<?> type) {
        if ("null".equals(val) && !type.isPrimitive()) return null;
        // Enum.valueOf resuelve la constante por nombre; aplica a cualquier enum antes del switch.
        if (type.isEnum()) {
            @SuppressWarnings("unchecked")
            var enumType = (Class<Enum>) type;
            return Enum.valueOf(enumType, val);
        }
        return switch (type.getSimpleName()) {
            // Notación científica (1e5, 2.3E-4) y decimales (.0) no son válidos para parseInt/parseLong;
            // se parsean como double y se castean al tipo destino (truncando la parte fraccionaria).
            case "int", "Integer"     -> isDecimalOrScientific(val) ? (int) Double.parseDouble(val) : Integer.parseInt(val);
            case "long", "Long"       -> isDecimalOrScientific(val) ? (long) Double.parseDouble(val) : Long.parseLong(val);
            case "double", "Double"   -> Double.parseDouble(val);
            case "float", "Float"     -> Float.parseFloat(val);
            case "boolean", "Boolean" -> Boolean.parseBoolean(val);
            case "BigDecimal"         -> new BigDecimal(val);
            case "BigInteger"         -> new BigInteger(val);
            case "short", "Short"     -> Short.parseShort(val);
            case "byte",  "Byte"      -> Byte.parseByte(val);
            // char llega como string de un carácter tras quitar comillas; toma el primero.
            case "char", "Character"  -> val.isEmpty() ? '\0' : val.charAt(0);
            case "LocalDate"          -> LocalDate.parse(val);
            case "LocalDateTime"      -> LocalDateTime.parse(val);
            case "Instant"            -> Instant.parse(val);
            default                   -> val;
        };
    }

    // indexOf evita allocation; suficiente para detectar si el literal requiere parseo flotante.
    private static boolean isDecimalOrScientific(String val) {
        return val.indexOf('.') >= 0 || val.indexOf('e') >= 0 || val.indexOf('E') >= 0;
    }

    // & 0xFF convierte el byte con signo de Java a índice sin signo (0-255) para IS_DELIMITER.
    private boolean isJsonDelimiter(byte b) {
        return IS_DELIMITER[b & 0xFF];
    }

    /**
     * Recorre el segmento desde {@code start} (que apunta a {@code [}) hasta encontrar
     * el {@code ]} de cierre al mismo nivel de anidamiento. Necesario para arrays anidados
     * como {@code [[1,2],[3,4]]}; para arrays planos depth nunca supera 1.
     */
    private long findArrayEnd(MemorySegment segment, long start, long size) {
        int depth = 0;
        for (long i = start; i < size; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if      (b == '[') depth++;
            else if (b == ']') { if (--depth == 0) return i; }
        }
        throw new RuntimeException("Unclosed '[' at byte position: " + start);
    }

    /**
     * Convierte una List de valores ya convertidos a un array Java nativo del tipo correcto.
     * Los tipos primitivos requieren sus propios arrays (int[], long[], etc.); el caso default
     * usa reflection para crear un Object[] tipado (String[], Integer[], etc.).
     */
    private Object toNativeArray(List<Object> list, Class<?> componentType) {
        int n = list.size();
        return switch (componentType.getSimpleName()) {
            case "int"     -> { int[]     a = new int[n];     for (int i=0;i<n;i++) a[i] = (Integer)   list.get(i); yield a; }
            case "long"    -> { long[]    a = new long[n];    for (int i=0;i<n;i++) a[i] = (Long)      list.get(i); yield a; }
            case "double"  -> { double[]  a = new double[n];  for (int i=0;i<n;i++) a[i] = (Double)    list.get(i); yield a; }
            case "float"   -> { float[]   a = new float[n];   for (int i=0;i<n;i++) a[i] = (Float)     list.get(i); yield a; }
            case "boolean" -> { boolean[] a = new boolean[n]; for (int i=0;i<n;i++) a[i] = (Boolean)   list.get(i); yield a; }
            case "short"   -> { short[]   a = new short[n];   for (int i=0;i<n;i++) a[i] = (Short)     list.get(i); yield a; }
            case "byte"    -> { byte[]    a = new byte[n];    for (int i=0;i<n;i++) a[i] = (Byte)      list.get(i); yield a; }
            case "char"    -> { char[]    a = new char[n];    for (int i=0;i<n;i++) a[i] = (Character) list.get(i); yield a; }
            default        -> {
                // Array de tipos de referencia: reflection crea el array con el tipo exacto.
                Object[] a = (Object[]) java.lang.reflect.Array.newInstance(componentType, n);
                list.toArray(a);
                yield a;
            }
        };
    }

    /**
     * Busca el {@code }} de cierre de un objeto JSON respetando anidamiento y strings.
     * Necesario para no confundir llaves dentro de strings ({@code "key":"{val}"}) con
     * delimitadores estructurales del objeto.
     */
    private long findObjectEnd(MemorySegment segment, long start, long size) {
        int depth = 0;
        for (long i = start; i < size; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if (b == (byte)'"') {
                // Salta el contenido del string para no contar { } dentro de valores string.
                for (i++; i < size; i++) {
                    byte c = segment.get(ValueLayout.JAVA_BYTE, i);
                    if      (c == (byte)'\\') i++;   // escape: salta el siguiente byte
                    else if (c == (byte)'"')  break; // cierre de string
                }
            } else if (b == (byte)'{') depth++;
            else if (b == (byte)'}') { if (--depth == 0) return i; }
        }
        throw new RuntimeException("Unclosed '{' at byte position: " + start);
    }

    /**
     * Parsea recursivamente un Record anidado creando un JsonParser dedicado para su tipo.
     * El RecordMetadata se construye bajo demanda; para cachear múltiples instancias del
     * mismo tipo anidado se necesitaría un Map externo (optimización futura).
     */
    @SuppressWarnings("unchecked")
    private <N> N parseNestedRecord(String raw, Class<N> nestedType) {
        return new JsonParser<>(new RecordMetadata<>(nestedType), false).parse(raw);
    }

    /**
     * Busca el cierre {@code "} de un valor string respetando secuencias de escape.
     * VectorScanner no puede usarse aquí porque no distingue {@code \"} (comilla escapada)
     * de {@code "} (cierre real). El scanner escalar salta el byte siguiente a cada {@code \}.
     */
    private long findStringEnd(MemorySegment segment, long start, long size) {
        for (long i = start; i < size; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if      (b == (byte)'\\') i++;   // salta el byte escapado
            else if (b == (byte)'"')  return i;
        }
        throw new RuntimeException("Unclosed string at byte position: " + start);
    }

    /**
     * Extrae un valor string procesando secuencias de escape JSON.
     * Fast path: si el rango no contiene {@code \}, delega en {@code extractString} sin
     * allocation extra. Slow path: decodifica a String primero (preserva UTF-8 multi-byte)
     * y luego procesa los escapes char a char.
     */
    private String extractStringEscaped(MemorySegment segment, long start, long end) {
        for (long i = start; i < end; i++) {
            if (segment.get(ValueLayout.JAVA_BYTE, i) != (byte)'\\') continue;
            // Hay al menos un escape: decodifica todo y procesa
            String raw = extractString(segment, start, end);
            var sb = new StringBuilder(raw.length());
            for (int j = 0; j < raw.length(); j++) {
                char c = raw.charAt(j);
                if (c != '\\' || j + 1 >= raw.length()) { sb.append(c); continue; }
                j++;
                switch (raw.charAt(j)) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n'  -> sb.append('\n');
                    case 't'  -> sb.append('\t');
                    case 'r'  -> sb.append('\r');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'u'  -> {
                        if (j + 4 < raw.length()) {
                            int codePoint = 0;
                            for (int k = 1; k <= 4; k++) {
                                char hex = raw.charAt(j + k);
                                int val = (hex >= '0' && hex <= '9') ? hex - '0' :
                                          (hex >= 'a' && hex <= 'f') ? hex - 'a' + 10 :
                                          (hex >= 'A' && hex <= 'F') ? hex - 'A' + 10 : 0;
                                codePoint = (codePoint << 4) | val;
                            }
                            sb.append((char) codePoint);
                            j += 4;
                        }
                    }
                    default -> { sb.append('\\'); sb.append(raw.charAt(j)); }
                }
            }
            return sb.toString();
        }
        return extractString(segment, start, end);
    }

    /**
     * Convierte el bloque raw {@code "[elem1, elem2, ...]"} a una {@code List<?>}.
     * El tipo de elemento se deduce del type argument genérico del campo en el Record
     * (e.g. {@code List<Integer>} → elemento {@code Integer}).
     * Valores {@code null} literales en el array se preservan como {@code null}.
     */
    private List<Object> parseArrayValue(String raw, Type genericType) {
        String content = raw.trim();
        // quita los corchetes envolventes
        content = content.substring(1, content.length() - 1).trim();

        List<Object> result = new ArrayList<>();
        if (content.isEmpty()) return result;

        Class<?> elementType = resolveElementType(genericType);
        for (String elem : splitElements(content)) {
            String token = elem.trim();
            if (token.equals("null")) {
                result.add(null);
                continue;
            }
            // quita comillas si el elemento es un string JSON
            if (token.startsWith("\"") && token.endsWith("\""))
                token = token.substring(1, token.length() - 1);
            result.add(convertType(token, elementType));
        }
        return result;
    }

    /**
     * Extrae el tipo del elemento de un campo List o array del Record.
     * Para {@code List<String>} devuelve {@code String.class};
     * para {@code String[]} devuelve {@code String.class}.
     * Devuelve {@code String.class} como fallback si no hay información genérica.
     */
    private Class<?> resolveElementType(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            if (arg instanceof Class<?> c) return c;
        }
        if (genericType instanceof Class<?> c && c.isArray())
            return c.getComponentType();
        return String.class;
    }

    /**
     * Divide los elementos de un array JSON respetando strings entre comillas
     * y anidamiento de corchetes/llaves. Un simple {@code split(",")} rompería
     * strings que contengan comas o arrays/objetos anidados.
     */
    private List<String> splitElements(String content) {
        List<String> elements = new ArrayList<>();
        int depth     = 0;
        boolean inStr = false;
        int start     = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && (i == 0 || content.charAt(i - 1) != '\\')) {
                inStr = !inStr;
            } else if (!inStr) {
                if      (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
                else if (c == ',' && depth == 0) {
                    elements.add(content.substring(start, i));
                    start = i + 1;
                }
            }
        }
        elements.add(content.substring(start));
        return elements;
    }
}
