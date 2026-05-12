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
                throw new RuntimeException("Invalid JSON");

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
                        TypeKind kind = meta.components.get(idx).getType().isRecord() ? TypeKind.RECORD : meta.componentTypeKinds[idx];
                        List<Object> list = parseArrayValue(raw, meta.genericTypes[idx]);
                        args[idx] = (meta.components.get(idx).getType().isArray()) ? toNativeArray(list, meta.components.get(idx).getType().getComponentType()) : list;
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
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }

        if (strict) meta.validateAllPresent(seen);
        try {
            return meta.targetClass.cast(meta.constructorHandle.invokeWithArguments(args));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private int findComponentIndex(MemorySegment segment, long start, long end) {
        int len = (int) (end - start);
        byte[][] names = meta.componentNameBytes;
        outer:
        for (int i = 0; i < names.length; i++) {
            if (names[i].length != len) continue;
            for (int j = 0; j < len; j++) {
                if (segment.get(ValueLayout.JAVA_BYTE, start + j) != names[i][j]) continue outer;
            }
            return i;
        }
        return -1;
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
            res = res * 10 + (segment.get(ValueLayout.JAVA_BYTE, i) - '0');
        }
        return neg ? -res : res;
    }

    private long parseDirectLong(MemorySegment segment, long start, long end) {
        long res = 0;
        boolean neg = segment.get(ValueLayout.JAVA_BYTE, start) == '-';
        for (long i = neg ? start + 1 : start; i < end; i++) {
            res = res * 10 + (segment.get(ValueLayout.JAVA_BYTE, i) - '0');
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
        if (type == int.class || type == Integer.class) return Integer.parseInt(val);
        if (type == long.class || type == Long.class) return Long.parseLong(val);
        if (type == double.class || type == Double.class) return Double.parseDouble(val);
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(val);
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, val);
        return val;
    }

    @SuppressWarnings("unchecked")
    private <N> N parseNestedRecord(String raw, Class<N> nestedType) {
        return new JsonParser<>(RecordMetadata.of(nestedType), false).parse(raw);
    }

    private List<Object> parseArrayValue(String raw, Type genericType) {
        List<Object> list = new ArrayList<>();
        String content = raw.substring(1, raw.length() - 1).trim();
        if (content.isEmpty()) return list;
        Type itemType = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments()[0] : Object.class;
        for (String part : content.split(",")) {
            String p = part.trim();
            if (p.startsWith("\"")) p = p.substring(1, p.length() - 1);
            list.add(convertType(p, (Class<?>) (itemType instanceof Class ? itemType : Object.class)));
        }
        return list;
    }

    private Object toNativeArray(List<Object> list, Class<?> componentType) {
        Object arr = Array.newInstance(componentType, list.size());
        for (int i = 0; i < list.size(); i++) Array.set(arr, i, list.get(i));
        return arr;
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t");
    }
}
