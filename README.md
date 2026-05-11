# EmiJson — High Performance JSON Parser

EmiJson es un parser y serializer JSON de ultra alto rendimiento para Java, diseñado para aprovechar las últimas características de la JVM y eliminar por completo el uso de *Reflection* clásico durante el procesamiento de datos.

## 🚀 Requisitos del Sistema

Para compilar y ejecutar este proyecto de forma exitosa, necesitas cumplir con los siguientes requisitos:

- **Java 25** (Obligatorio para acceso nativo y óptimo a las APIs más recientes, incluyendo Vector API y FFM).
- **Gradle 9.4 o superior** (El proyecto ya incluye el wrapper configurado en la versión 9.4.1, por lo que basta con usar `./gradlew`).
- **Módulos Incubadores Habilitados**: Dado que el proyecto utiliza la **Vector API** para acelerar el procesamiento mediante SIMD, es **obligatorio** incluir el flag `--add-modules jdk.incubator.vector` en la JVM al ejecutar o compilar (esto ya está configurado en el `build.gradle.kts`).

## 🛠️ Cómo compilar y probar

Puedes ejecutar la suite de pruebas automatizadas con el siguiente comando:

```bash
./gradlew test
```
*(Nota importante: Al ejecutar los tests, verás un mensaje rojo que dice `WARNING: Using incubator modules: jdk.incubator.vector`. **Esto no es un error**, es una advertencia obligatoria de la JVM al usar módulos experimentales. Los tests se ejecutarán con normalidad).*

---

## 🧠 Internals y Arquitectura

Documento de referencia técnica: qué tecnologías usa EmiJson y por qué, y qué técnicas aplica en el camino caliente (parse / serialize).
---

## Tecnologías

### Java Records (JDK 16+)

EmiJson solo acepta Java Records como tipo objetivo. Un Record garantiza que:

- Sus componentes están declarados en orden fijo y son inmutables.
- Expone un constructor canónico con todos los componentes como parámetros.
- Expone un accessor por componente con el mismo nombre (`id()`, `name()`, etc.).

Estas garantías permiten obtener el constructor y los accessors por reflexión una sola vez en tiempo de inicialización y cachearlos como `MethodHandle`. Si el tipo no fuera un Record, no existiría ningún contrato sobre la forma del constructor ni de los accessors.

---

### FFM API — `java.lang.foreign` (JDK 21+, estable en JDK 22)

`MemorySegment` representa un bloque de memoria con acceso de bajo nivel. En EmiJson se usa para operar sobre los bytes del JSON sin copiarlos a estructuras intermedias de Java.

**Por qué importa:**

Una vez que el JSON está en memoria como `byte[]`, se envuelve en un `MemorySegment` con `MemorySegment.ofArray(bytes)`. A partir de ahí, `VectorScanner` y `JsonParser` leen bytes directamente por índice usando `ValueLayout.JAVA_BYTE` sin crear subarrays ni Strings hasta que sea estrictamente necesario.

```
String json
  └─ getBytes(UTF_8)          → byte[]         (única copia inevitable desde String)
       └─ MemorySegment.ofArray → MemorySegment  (zero-copy; apunta al mismo byte[])
            └─ VectorScanner / JsonParser         (acceso directo a bytes por índice)
```

---

### Vector API — `jdk.incubator.vector` (JDK 16+, incubator)

Permite emitir instrucciones SIMD (Single Instruction, Multiple Data) desde Java. En lugar de comparar un byte por iteración, un solo `ByteVector.compare(EQ, target)` compara 16, 32 o 64 bytes simultáneamente dependiendo del ancho de registro que soporte la CPU en runtime.

`ByteVector.SPECIES_PREFERRED` selecciona el ancho óptimo automáticamente; el JIT especializa el código por plataforma.

Se usa en `VectorScanner` para dos operaciones que dominan el tiempo de parsing:

| Operación | Sin SIMD | Con SIMD (AVX2, 32 bytes) |
|---|---|---|
| Saltar whitespace | 1 byte/iter | 32 bytes/iter |
| Buscar `"`, `:`, `}` | 1 byte/iter | 32 bytes/iter |

---

### MethodHandles — `java.lang.invoke` (JDK 7+)

`MethodHandle` es la alternativa a `Method.invoke()` que el JIT puede inlinear como si fuera una llamada directa al método. En EmiJson se obtienen y cachean en `RecordMetadata` en tiempo de inicialización:

- `constructorHandle` — constructor canónico del Record (para instanciar al final del parse).
- `accessorHandles` — un handle por componente (para leer valores durante la serialización).

El camino caliente no toca `java.lang.reflect` en absoluto.

---

### JMH — Java Microbenchmark Harness

Framework estándar de la JVM para medir throughput y latencia de operaciones de grano fino. Gestiona el warm-up del JIT, previene la eliminación de código muerto (DCE) y calcula intervalos de confianza.

El benchmark `EmiJsonBenchmark.parseSingleRecord` mide la latencia promedio de parsear un único registro JSON complejo (11 campos de tipos mixtos):

```
Benchmark                           Mode  Cnt   Score  Error  Units
EmiJsonBenchmark.parseSingleRecord  avgt    5  ≈ 10⁻⁶          s/op
```

`≈ 10⁻⁶ s/op` = **~1 microsegundo por parse**. El método retorna el Record para evitar que el JIT elimine el trabajo como dead code.

---

## Técnicas y algoritmos

### 1. Lookup de key sin allocations (zero-alloc key lookup)

**Problema:** el parser necesita identificar a qué campo del Record pertenece cada key del JSON. La solución naive es crear un `String` con el nombre de la key y hacer `map.get(key)`. Eso implica una allocation de `String` + un hash por cada campo de cada JSON.

**Solución:** `RecordMetadata` pre-codifica los nombres de componentes a `byte[][]` en UTF-8 una sola vez. Durante el parse, `JsonParser.findComponentIndex` compara directamente los bytes del `MemorySegment` contra esos arrays sin crear ningún `String`:

```
MemorySegment bytes del JSON: [ ... "f" "r" "a" "m" "e" "w" "o" "r" "k" "_" "n" "a" "m" "e" ... ]
                                     ^                                                ^
                                  keyStart                                          keyEnd

componentNameBytes[2]:        [ "f" "r" "a" "m" "e" "w" "o" "r" "k" "_" "n" "a" "m" "e" ]
                                ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓   ✓  → índice 2
```

La comparación de longitud primera (`names[i].length != len`) evita iterar los bytes cuando el tamaño ya no coincide, descartando la mayoría de candidatos en O(1).

---

### 2. Keys pre-computadas en el serializer

**Problema:** al serializar, cada campo necesita escribir su key entre comillas seguida de dos puntos: `"framework_name":`. Construir ese fragmento dentro del loop caliente crea un `String` temporal por campo por invocación.

**Solución:** `RecordMetadata` pre-construye `compactKeys[]` y `prettyKeys[]` durante la inicialización:

```java
compactKeys[2] = "\"framework_name\":";     // listo para sb.append()
prettyKeys[2]  = "  \"framework_name\": ";  // versión pretty
```

El loop de `JsonSerializer.build` solo hace `sb.append(keys[i])`, que es un `char[]` copy interno del `StringBuilder`, sin crear ningún objeto nuevo.

---

### 3. Capacidad inicial del StringBuilder pre-calculada

`RecordMetadata.compactMinCapacity` suma en inicialización la longitud total de todas las keys compactas, más dos caracteres para `{}`, más `n-1` comas, más 8 bytes por campo como estimación mínima de valor. El `StringBuilder` se crea con esa capacidad para evitar su primer resize interno en la mayoría de los JSON de tamaño típico.

---

### 4. Tabla de lookup para delimitadores (`IS_DELIMITER`)

**Problema:** dentro del loop de parsing de valores primitivos, cada byte debe chequearse contra varios delimitadores posibles: `\n`, `\r`, ` `, `,`, `}`, y cualquier whitespace.

**Solución:** un array `boolean[256]` inicializado una sola vez como campo estático. El chequeo se reduce a:

```java
IS_DELIMITER[b & 0xFF]   // array access O(1), branch-predictor friendly
```

El `& 0xFF` convierte el byte con signo de Java (−128..127) a índice sin signo (0..255).

---

### 5. `toBytes()` sin String intermedio

`toJson(record)` produce un `String` y luego quien llame puede hacer `.getBytes(UTF_8)` si necesita bytes — eso son dos allocations (`String` + `byte[]`). `toBytes(record)` elimina la primera:

```
StringBuilder  →  CharBuffer.wrap(sb)  →  CharsetEncoder.encode()  →  ByteBuffer  →  byte[]
                  ↑ sin copiar a String ↑
```

`CharBuffer.wrap(StringBuilder)` expone el contenido del builder como `CharSequence` directamente al encoder, sin materializar el `String` intermedio.

---

### 6. Modo (compacto / pretty) resuelto en el constructor

`JsonSerializer` resuelve el modo una sola vez en el constructor asignando referencias a los arrays y separadores correctos. El método `build()` no evalúa ningún `if (prettyPrint)` en el loop caliente; solo hace `append` con las variables ya resueltas.

---

## Flujo de datos resumido

```
parse(String json)
│
├─ json.getBytes(UTF_8)              → byte[]           [única copia]
├─ MemorySegment.ofArray(bytes)      → MemorySegment    [zero-copy]
├─ VectorScanner.skipWhitespace      → pos inicial      [SIMD]
│
└─ loop por cada campo JSON
   ├─ VectorScanner.findDelimiter('"')  → keyStart/keyEnd  [SIMD]
   ├─ findComponentIndex               → idx              [byte compare, zero-alloc]
   ├─ VectorScanner.findDelimiter(':') → pos valor        [SIMD]
   └─ extractString / convertType      → args[idx]        [única alloc por valor]
│
├─ constructorHandle.invokeWithArguments(args)  → T record  [MethodHandle]
└─ return T


toJson(T record)  /  toBytes(T record)
│
├─ new StringBuilder(compactMinCapacity)        [capacidad pre-calculada]
├─ sb.append(open)                              ["{" o "{\n"]
│
└─ loop por cada componente
   ├─ sb.append(keys[i])                        [key pre-computada, zero-alloc]
   └─ accessorHandles.get(i).invoke(record)  →  valor  [MethodHandle]
│
├─ toJson   → sb.toString()
└─ toBytes  → CharsetEncoder.encode(CharBuffer.wrap(sb)) → byte[]  [sin String intermedio]
```
