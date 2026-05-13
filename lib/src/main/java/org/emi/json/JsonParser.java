package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsea un MemorySegment JSON directamente a un Java Record.
 * Versión optimizada para inlining del JIT y escaneo SIMD.
 */
final class JsonParser<T> {

    private static final boolean[] IS_DELIMITER = new boolean[256];
    static {
        IS_DELIMITER[' '] = true;
        IS_DELIMITER['\n'] = true;
        IS_DELIMITER['\r'] = true;
        IS_DELIMITER['\t'] = true;
        IS_DELIMITER[','] = true;
        IS_DELIMITER[':'] = true;
        IS_DELIMITER['}'] = true;
        IS_DELIMITER[']'] = true;
    }

    private final RecordMetadata<T> meta;
    private final boolean strict;

    JsonParser(RecordMetadata<T> meta, boolean strict) {
        this.meta   = meta;
        this.strict = strict;
    }

    T parse(String json) {
        return parse(MemorySegment.ofArray(json.getBytes(StandardCharsets.UTF_8)));
    }

    T parse(MemorySegment segment) {
        long size = segment.byteSize();
        Object[] args = meta.initArgs();
        long seen = 0;
        long pos = VectorScanner.skipWhitespace(segment, 0);

        try {
            if (segment.get(ValueLayout.JAVA_BYTE, pos) != '{')
                throw new JsonParseException("Expected '{' to start JSON object", pos);

            while (pos < size) {
                pos = VectorScanner.findDelimiter(segment, pos, (byte) '"');
                if (pos == -1) break;

                long keyStart = ++pos;
                pos = VectorScanner.findDelimiter(segment, pos, (byte) '"');
                int idx = findComponentIndex(segment, keyStart, pos);

                pos = VectorScanner.findDelimiter(segment, pos, (byte) ':');
                pos = VectorScanner.skipWhitespace(segment, pos + 1);

                byte firstByte = segment.get(ValueLayout.JAVA_BYTE, pos);
                if (firstByte == '"') {
                    long valStart = ++pos;
                    pos = findStringEnd(segment, pos, size);
                    if (idx >= 0) {
                        TypeKind kind = meta.componentTypeKinds[idx];
                        String s = extractStringEscaped(segment, valStart, pos);
                        args[idx] = (kind == TypeKind.STRING) ? s : convertType(s, meta.components.get(idx).getType());
                        seen |= (1L << idx);
                    }
                } else if (firstByte == '{') {
                    long objEnd = findObjectEnd(segment, pos, size);
                    if (idx >= 0 && meta.componentTypeKinds[idx] == TypeKind.RECORD) {
                        args[idx] = parseNestedRecord(extractString(segment, pos, objEnd + 1), meta.components.get(idx).getType());
                        seen |= (1L << idx);
                    }
                    pos = objEnd;
                } else if (firstByte == '[') {
                    long arrEnd = findArrayEnd(segment, pos, size);
                    if (idx >= 0) {
                        String raw = extractString(segment, pos, arrEnd + 1);
                        Class<?> fieldType = meta.components.get(idx).getType();
                        Type itemType = fieldType.isArray() ? fieldType.getComponentType() : meta.genericTypes[idx];
                        List<Object> list = parseArrayValue(raw, itemType);
                        args[idx] = (fieldType.isArray()) ? toNativeArray(list, fieldType.getComponentType()) : list;
                        seen |= (1L << idx);
                    }
                    pos = arrEnd;
                } else {
                    long valStart = pos;
                    while (pos < size && !isJsonDelimiter(segment.get(ValueLayout.JAVA_BYTE, pos))) pos++;
                    if (idx >= 0) {
                        TypeKind kind = meta.componentTypeKinds[idx];
                        args[idx] = switch (kind) {
                            case INT     -> parseDirectInt(segment, valStart, pos);
                            case LONG    -> parseDirectLong(segment, valStart, pos);
                            case BOOLEAN -> segment.get(ValueLayout.JAVA_BYTE, valStart) == 't';
                            default      -> parsePrimitiveDirect(segment, valStart, pos, meta.components.get(idx).getType());
                        };
                        seen |= (1L << idx);
                    }
                    pos--;
                }
                pos++;
            }
        } catch (EmiJsonException e) {
            throw e;
        } catch (Throwable t) {
            throw new JsonParseException("Unexpected error during parsing: " + t.getMessage(), t);
        }

        if (strict) meta.validateAllPresent(seen);
        return meta.instantiator.instantiate(args);
    }

    private int findComponentIndex(MemorySegment segment, long start, long end) {
        long len = end - start;
        int h = RecordMetadata.hash(segment, start, len);
        int pos = h & meta.lookupMask;
        
        while (true) {
            int idx = meta.lookupTable[pos];
            if (idx == -1) return -1;
            
            byte[] name = meta.componentNameBytes[idx];
            if (name.length == len) {
                boolean match = true;
                for (int i = 0; i < len; i++) {
                    if (segment.get(ValueLayout.JAVA_BYTE, start + i) != name[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) return idx;
            }
            pos = (pos + 1) & meta.lookupMask;
        }
    }

    private String extractString(MemorySegment segment, long start, long end) {
        return new String(segment.asSlice(start, end - start).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    private String extractStringEscaped(MemorySegment segment, long start, long end) {
        String s = extractString(segment, start, end);
        return s.indexOf('\\') == -1 ? s : unescape(s);
    }

    private Object parsePrimitiveDirect(MemorySegment segment, long start, long end, Class<?> type) {
        if (end <= start) return null;
        return convertType(extractString(segment, start, end), type);
    }

    private int parseDirectInt(MemorySegment segment, long start, long end) {
        int res = 0;
        boolean neg = segment.get(ValueLayout.JAVA_BYTE, start) == '-';
        for (long i = neg ? start + 1 : start; i < end; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if (b < '0' || b > '9') return new java.math.BigDecimal(extractString(segment, start, end)).intValue();
            res = res * 10 + (b - '0');
        }
        return neg ? -res : res;
    }

    private long parseDirectLong(MemorySegment segment, long start, long end) {
        long res = 0;
        boolean neg = segment.get(ValueLayout.JAVA_BYTE, start) == '-';
        for (long i = neg ? start + 1 : start; i < end; i++) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, i);
            if (b < '0' || b > '9') return new java.math.BigDecimal(extractString(segment, start, end)).longValue();
            res = res * 10 + (b - '0');
        }
        return neg ? -res : res;
    }

    private boolean isJsonDelimiter(byte b) {
        return IS_DELIMITER[b & 0xFF];
    }

    private long findStringEnd(MemorySegment segment, long pos, long size) {
        while (pos < size) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, pos);
            if (b == '"' && segment.get(ValueLayout.JAVA_BYTE, pos - 1) != '\\') return pos;
            pos++;
        }
        return size;
    }

    private long findObjectEnd(MemorySegment segment, long pos, long size) {
        int depth = 0;
        while (pos < size) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, pos);
            if (b == '{') depth++;
            else if (b == '}') {
                if (--depth == 0) return pos;
            }
            pos++;
        }
        return size;
    }

    private long findArrayEnd(MemorySegment segment, long pos, long size) {
        int depth = 0;
        while (pos < size) {
            byte b = segment.get(ValueLayout.JAVA_BYTE, pos);
            if (b == '[') depth++;
            else if (b == ']') {
                if (--depth == 0) return pos;
            }
            pos++;
        }
        return size;
    }

    private Object convertType(String val, Class<?> type) {
        if (val == null || val.equals("null")) return null;
        if (type == String.class) return val;
        if (type == int.class || type == Integer.class) {
            return new java.math.BigDecimal(val).intValue();
        }
        if (type == long.class || type == Long.class) {
            return new java.math.BigDecimal(val).longValue();
        }
        if (type == double.class || type == Double.class) return Double.parseDouble(val);
        if (type == float.class || type == Float.class) return Float.parseFloat(val);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(val);
        if (type == short.class || type == Short.class) return Short.parseShort(val);
        if (type == byte.class || type == Byte.class) return Byte.parseByte(val);
        if (type == char.class || type == Character.class) return val.isEmpty() ? '\0' : val.charAt(0);
        if (type == java.math.BigDecimal.class) return new java.math.BigDecimal(val);
        if (type == java.math.BigInteger.class) return new java.math.BigInteger(val);
        if (type == java.time.LocalDate.class) return java.time.LocalDate.parse(val);
        if (type == java.time.LocalDateTime.class) return java.time.LocalDateTime.parse(val);
        if (type == java.time.Instant.class) return java.time.Instant.parse(val);
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, val);
        return val;
    }

    @SuppressWarnings("unchecked")
    private <N> N parseNestedRecord(String raw, Class<N> nestedType) {
        return new JsonParser<>(RecordMetadata.of(nestedType), false).parse(raw);
    }

    private List<Object> parseArrayValue(String raw, Type genericOrComponentType) {
        List<Object> list = new ArrayList<>();
        String content = raw.substring(1, raw.length() - 1).trim();
        if (content.isEmpty()) return list;
        
        Class<?> itemClass = Object.class;
        if (genericOrComponentType instanceof Class<?> cls) {
            itemClass = cls;
        } else if (genericOrComponentType instanceof ParameterizedType pt) {
            Type first = pt.getActualTypeArguments()[0];
            if (first instanceof Class<?> c) itemClass = c;
        }
        
        for (String part : content.split(",")) {
            String p = part.trim();
            if (p.startsWith("\"")) p = p.substring(1, p.length() - 1);
            list.add(convertType(p, itemClass));
        }
        return list;
    }

    private Object toNativeArray(List<Object> list, Class<?> componentType) {
        Object arr = Array.newInstance(componentType, list.size());
        for (int i = 0; i < list.size(); i++) {
            Object val = list.get(i);
            if (val == null) continue;
            if (componentType.isPrimitive()) {
                if (componentType == int.class) Array.setInt(arr, i, ((Number)val).intValue());
                else if (componentType == long.class) Array.setLong(arr, i, ((Number)val).longValue());
                else if (componentType == double.class) Array.setDouble(arr, i, ((Number)val).doubleValue());
                else if (componentType == float.class) Array.setFloat(arr, i, ((Number)val).floatValue());
                else if (componentType == boolean.class) Array.setBoolean(arr, i, (Boolean)val);
                else if (componentType == short.class) Array.setShort(arr, i, ((Number)val).shortValue());
                else if (componentType == byte.class) Array.setByte(arr, i, ((Number)val).byteValue());
                else if (componentType == char.class) Array.setChar(arr, i, (Character)val);
            } else {
                Array.set(arr, i, val);
            }
        }
        return arr;
    }

    private String unescape(String s) {
        if (s.indexOf('\\') == -1) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'b'  -> sb.append('\b');
                    case 'f'  -> sb.append('\f');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u'  -> {
                        if (i + 4 < s.length()) {
                            String hex = s.substring(i + 1, i + 5);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } else sb.append("\\u");
                    }
                    default -> sb.append('\\').append(next);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}
