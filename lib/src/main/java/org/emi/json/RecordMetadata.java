package org.emi.json;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

enum TypeKind {
    INT, LONG, DOUBLE, FLOAT, BOOLEAN, STRING, 
    BIG_DECIMAL, BIG_INTEGER, DATE, DATETIME, INSTANT,
    ENUM, RECORD, LIST, ARRAY, OTHER
}

/**
 * Contenedor de metadatos de un Record optimizados para acceso de ultra-alta velocidad.
 * Mantiene la estructura del Record, tipos genéricos y una tabla de búsqueda perfecta (O(1))
 * para resolver nombres de campos desde bytes JSON sin crear Strings intermedios.
 * 
 * <p>Ejemplo de uso interno:</p>
 * <pre>{@code
 * RecordMetadata<User> meta = RecordMetadata.of(User.class);
 * int fieldIndex = meta.findComponentIndex(segment, start, len);
 * }</pre>
 *
 * @param <T> Tipo del Record.
 */
final class RecordMetadata<T> {

    private static final ConcurrentHashMap<Class<?>, RecordMetadata<?>> CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    static <T> RecordMetadata<T> of(Class<T> targetClass) {
        return (RecordMetadata<T>) CACHE.computeIfAbsent(targetClass, RecordMetadata::new);
    }

    final Class<T> targetClass;
    final RecordInstantiator<T> instantiator;
    final List<RecordComponent> components;
    final List<MethodHandle> accessorHandles;
    final byte[][] componentNameBytes;
    final String[] compactKeys;
    final String[] prettyKeys;
    final Type[] genericTypes;
    final TypeKind[] componentTypeKinds;
    final Object[] argsTemplate;
    final int compactMinCapacity;
    final int[] lookupTable;
    final int lookupMask;

    RecordMetadata(Class<T> targetClass) {
        this.targetClass = targetClass;
        this.components = Arrays.asList(targetClass.getRecordComponents());
        this.instantiator = ParserGenerator.generateInstantiator(targetClass);

        int n = components.size();
        this.componentNameBytes = new byte[n][];
        this.compactKeys        = new String[n];
        this.prettyKeys         = new String[n];
        this.genericTypes       = new Type[n];
        this.componentTypeKinds = new TypeKind[n];
        
        int keysSize = 0;
        for (int i = 0; i < n; i++) {
            String name           = components.get(i).getName();
            componentNameBytes[i] = name.getBytes(StandardCharsets.UTF_8);
            compactKeys[i]        = "\"" + name + "\":";
            prettyKeys[i]         = "  \"" + name + "\": ";
            genericTypes[i]       = components.get(i).getGenericType();
            componentTypeKinds[i] = resolveTypeKind(components.get(i).getType());
            keysSize             += compactKeys[i].length();
        }
        this.compactMinCapacity = 2 + keysSize + (n - 1) + n * 8;

        // --- Optimización: Tabla de búsqueda de llaves (O(1)) ---
        int tableSize = 1;
        while (tableSize < n * 2) tableSize <<= 1;
        this.lookupMask = tableSize - 1;
        this.lookupTable = new int[tableSize];
        Arrays.fill(lookupTable, -1);

        for (int i = 0; i < n; i++) {
            byte[] bytes = componentNameBytes[i];
            int h = hash(bytes, 0, bytes.length);
            int pos = h & lookupMask;
            // Manejo simple de colisiones (lineal) si fuera necesario, 
            // pero para records pequeños suele ser directo.
            while (lookupTable[pos] != -1) pos = (pos + 1) & lookupMask;
            lookupTable[pos] = i;
        }

        try {
            var lookup     = MethodHandles.lookup();
            // Eliminamos la búsqueda manual del constructor, delegamos a ParserGenerator
            // que es más rápido y genera bytecode nativo.

            this.accessorHandles = components.stream()
                    .map(c -> {
                        try {
                            var accessor = c.getAccessor();
                            accessor.setAccessible(true);
                            return lookup.unreflect(accessor);
                        } catch (IllegalAccessException e) {
                            throw new JsonMappingException("Cannot access accessor for: " + c.getName(), e);
                        }
                    })
                    .toList();
        } catch (EmiJsonException e) {
            throw e;
        } catch (Exception e) {
            throw new JsonMappingException("Failed to initialize handles for " + targetClass.getName(), e);
        }

        this.argsTemplate = new Object[n];
        for (int i = 0; i < n; i++) {
            Class<?> t = components.get(i).getType();
            if (!t.isPrimitive()) continue;
            argsTemplate[i] = switch (t.getSimpleName()) {
                case "int"     -> 0;
                case "long"    -> 0L;
                case "double"  -> 0.0;
                case "float"   -> 0.0f;
                case "boolean" -> false;
                case "byte"    -> (byte) 0;
                case "short"   -> (short) 0;
                case "char"    -> '\0';
                default        -> null;
            };
        }
    }

    private TypeKind resolveTypeKind(Class<?> type) {
        if (type == int.class     || type == Integer.class)    return TypeKind.INT;
        if (type == long.class    || type == Long.class)       return TypeKind.LONG;
        if (type == double.class  || type == Double.class)     return TypeKind.DOUBLE;
        if (type == float.class   || type == Float.class)      return TypeKind.FLOAT;
        if (type == boolean.class || type == Boolean.class)    return TypeKind.BOOLEAN;
        if (type == String.class)                              return TypeKind.STRING;
        if (type == java.math.BigDecimal.class)                return TypeKind.BIG_DECIMAL;
        if (type == java.math.BigInteger.class)                return TypeKind.BIG_INTEGER;
        if (type == java.time.LocalDate.class)                 return TypeKind.DATE;
        if (type == java.time.LocalDateTime.class)             return TypeKind.DATETIME;
        if (type == java.time.Instant.class)                   return TypeKind.INSTANT;
        if (type.isEnum())                                     return TypeKind.ENUM;
        if (type.isRecord())                                   return TypeKind.RECORD;
        if (type == java.util.List.class)                      return TypeKind.LIST;
        if (type.isArray())                                    return TypeKind.ARRAY;
        return TypeKind.OTHER;
    }

    Object[] initArgs() {
        int n = argsTemplate.length;
        Object[] args = new Object[n];
        for (int i = 0; i < n; i++) {
            args[i] = argsTemplate[i];
        }
        return args;
    }

    void validateAllPresent(long seen) {
        for (int i = 0; i < components.size() && i < 64; i++) {
            if ((seen & (1L << i)) == 0)
                throw new JsonMappingException("Strict mode: missing required field \"" + components.get(i).getName() + "\"");
        }
    }

    static int hash(byte[] bytes, int offset, int len) {
        if (len == 0) return 0;
        int h = len;
        h = h * 31 + bytes[offset];
        h = h * 31 + bytes[offset + len - 1];
        return h;
    }

    static int hash(java.lang.foreign.MemorySegment segment, long start, long len) {
        if (len == 0) return 0;
        int h = (int) len;
        h = h * 31 + segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, start);
        h = h * 31 + segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, start + len - 1);
        return h;
    }
}
