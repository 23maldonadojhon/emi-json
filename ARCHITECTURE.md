# Arquitectura de EmiJson 🚀

EmiJson está diseñado bajo el principio de **"Zero-Overhead"**. Cada decisión arquitectónica busca minimizar las pausas del Garbage Collector (GC), maximizar el uso de los registros de la CPU y eliminar la reflexión en la ruta caliente (*hot-path*).

## Las 4 Columnas del Rendimiento

### 1. Aceleración SIMD (Vector API)
En lugar de escanear los bytes uno por uno (procesamiento escalar), EmiJson utiliza la **Java Vector API** (incubadora).
- **Cómo funciona:** Cargamos 128, 256 o 512 bits (según el procesador) en registros vectoriales.
- **Ventaja:** Podemos saltar espacios en blanco o encontrar comillas (`"`) procesando hasta 64 caracteres en una sola instrucción de CPU.
- **Ubicación:** `VectorScanner.java`.

### 2. Generación de Bytecode Dinámico (Class-File API)
La reflexión en Java (`Method.invoke`) es lenta y difícil de optimizar por el compilador JIT. EmiJson utiliza la nueva **Class-File API (Java 25)** para generar código en tiempo de ejecución.
- **Instanciación:** Generamos una clase que llama directamente al constructor canónico del Record (sin MethodHandles genéricos).
- **Serialización:** Generamos una clase que llama a los métodos de acceso (`user.name()`) de forma nativa.
- **Ubicación:** `ParserGenerator.java` y `SerializerGenerator.java`.

### 3. Acceso a Memoria Eficiente (Foreign Function & Memory API)
Tradicionalmente, parsear un JSON implica convertir bytes a `String` constantemente, lo que satura el Heap y genera presión en el GC.
- **MemorySegment:** EmiJson navega directamente sobre `MemorySegment`, permitiendo procesar datos tanto en el Heap como fuera de él (Off-heap) sin copias innecesarias.
- **Zero-Allocation Mapping:** No creamos objetos intermedios para las llaves del JSON; las resolvemos comparando bytes directamente.

### 4. Búsqueda de Campos O(1) (Perfect Hashing)
En lugar de comparar la llave del JSON contra cada campo del Record en un bucle (O(N)), utilizamos una **Tabla de Búsqueda Perfecta**.
- **Pre-proceso:** Al inicializar un Record, calculamos una función de hash optimizada para sus campos.
- **Búsqueda:** Durante el parseo, aplicamos el hash a los bytes encontrados y saltamos directamente al índice del componente correcto.

---

## Flujo de Ejecución

1. **Fase de Inicialización:**
   - Se analizan los componentes del Record.
   - Se genera el bytecode para instanciación y serialización.
   - Se construye la tabla de hash O(1).
2. **Fase de Parseo:**
   - `VectorScanner` encuentra las llaves.
   - `RecordMetadata` resuelve el índice del campo.
   - `JsonParser` extrae y convierte los valores.
3. **Fase de Finalización:**
   - Se invoca el instanciador generado para devolver el Record poblado.
