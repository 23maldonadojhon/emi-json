package org.emi.json;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Caché de metadatos de un Java Record: MethodHandles, nombres de componentes y
 * fragmentos de serialización pre-computados. Se construye una única vez por tipo
 * en el constructor de EmiJson y es compartida (sin estado mutable) entre
 * JsonParser y JsonSerializer, haciéndola thread-safe de forma inherente.
 *
 * <p>Todo el coste de reflexión se paga aquí en tiempo de inicialización;
 * el camino caliente (parse / toJson) no toca reflexión en absoluto.
 */
final class RecordMetadata<T> {

    final Class<T> targetClass;

    // Handle del constructor canónico del Record (args en orden de declaración).
    // invokeWithArguments evita boxing manual para primitivos al llamar al constructor.
    final MethodHandle constructorHandle;

    final List<RecordComponent> components;

    // Handles de los accessors del Record (p. ej. record.name(), record.age()).
    // Más rápidos que Method.invoke() gracias a la inlining del JIT sobre MethodHandle.
    final List<MethodHandle> accessorHandles;

    // Nombres de componentes en bytes UTF-8: permite al parser comparar keys del JSON
    // directamente contra el MemorySegment sin crear ningún String intermedio.
    final byte[][] componentNameBytes;

    // Fragmentos de llave listos para hacer sb.append(): "\"name\":" y "  \"name\": "
    // Se calculan una vez aquí para que el serializer solo encadene appends en el loop.
    final String[] compactKeys;
    final String[] prettyKeys;

    // Tipo genérico de cada componente (e.g. List<String>, no solo List).
    // getType() devuelve el tipo borrado; getGenericType() preserva los type arguments
    // necesarios para saber el tipo de elemento al parsear un array JSON.
    final Type[] genericTypes;

    // Capacidad inicial del StringBuilder usada en toJson/toBytes.
    // Suma real de todas las llaves + separadores + estimación mínima de 8 bytes por valor.
    // Evita el primer resize interno del StringBuilder en la mayoría de los casos.
    final int compactMinCapacity;

    RecordMetadata(Class<T> targetClass) {
        this.targetClass = targetClass;
        this.components = Arrays.asList(targetClass.getRecordComponents());

        int n = components.size();
        this.componentNameBytes = new byte[n][];
        this.compactKeys        = new String[n];
        this.prettyKeys         = new String[n];
        this.genericTypes       = new Type[n];
        int keysSize = 0;
        for (int i = 0; i < n; i++) {
            String name           = components.get(i).getName();
            componentNameBytes[i] = name.getBytes(StandardCharsets.UTF_8);
            compactKeys[i]        = "\"" + name + "\":";
            prettyKeys[i]         = "  \"" + name + "\": ";
            genericTypes[i]       = components.get(i).getGenericType();
            keysSize             += compactKeys[i].length();
        }
        // 2 = llaves {} | (n-1) = comas | n*8 = estimación mínima de valores
        this.compactMinCapacity = 2 + keysSize + (n - 1) + n * 8;

        try {
            var lookup     = MethodHandles.lookup();
            var paramTypes = components.stream().map(RecordComponent::getType).toArray(Class<?>[]::new);
            var constructor = targetClass.getDeclaredConstructor(paramTypes);
            constructor.setAccessible(true);
            this.constructorHandle = lookup.unreflectConstructor(constructor);

            this.accessorHandles = components.stream()
                    .map(c -> {
                        try {
                            var accessor = c.getAccessor();
                            accessor.setAccessible(true);
                            return lookup.unreflect(accessor);
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Cannot access accessor for: " + c.getName(), e);
                        }
                    })
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize handles for " + targetClass.getName(), e);
        }
    }

    /**
     * Crea un array de argumentos con los valores cero correspondientes a cada componente.
     * Los tipos de referencia permanecen {@code null}; los primitivos necesitan su zero-value
     * explícito porque invokeWithArguments lanzaría NullPointerException al hacer unboxing.
     */
    Object[] initArgs() {
        Object[] args = new Object[components.size()];
        for (int i = 0; i < components.size(); i++) {
            Class<?> t = components.get(i).getType();
            if (!t.isPrimitive()) continue;
            args[i] = switch (t.getSimpleName()) {
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
        return args;
    }

    /**
     * Verifica que todos los componentes del Record hayan sido asignados durante el parsing.
     * Solo se invoca cuando {@code strict = true}; en modo laxo los campos ausentes
     * conservan su valor cero de {@link #initArgs()}.
     */
    void validateAllPresent(BitSet seen) {
        for (int i = 0; i < components.size(); i++) {
            if (!seen.get(i))
                throw new RuntimeException(
                        "Strict mode: missing required field \"" + components.get(i).getName() + "\"");
        }
    }
}
