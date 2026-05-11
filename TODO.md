# EmiJson — Tareas pendientes

Gaps identificados en el soporte de tipos y parsing. Ordenados por prioridad.

---

## Prioridad alta — Rompen JSON real

- [x] **Escape sequences en strings**
  - Manejar `\"`, `\\`, `\n`, `\t`, `\r`, `\uXXXX` dentro de valores string
  - Afecta: `JsonParser.extractString` — procesar escapes al materializar el String
  - Ejemplo roto: `{"name": "O\"Brien", "path": "C:\\Users"}`

- [x] **`null` literal en campos de referencia**
  - Si el JSON trae `"name": null`, hoy queda como el String `"null"` en vez de `null` Java
  - Afecta: `JsonParser.assignValue` / `convertType` — detectar el literal `"null"` antes de convertir
  - Aplica a: `String`, `List`, y cualquier tipo de referencia

- [x] **`float` / `Float`**
  - `initArgs()` ya maneja el zero-value, pero `convertType` no tiene el case y cae en `default` → queda como `String`
  - Fix: agregar `case "float", "Float" -> Float.parseFloat(val)` en `convertType`

- [x] **Notación científica en números**
  - `1.5e10`, `2.3E-4` — `Integer.parseInt` y `Long.parseLong` lanzan excepción
  - Fix: detectar presencia de `e`/`E` y parsear como `Double`, luego castear al tipo destino

---

## Prioridad media

- [x] **Records anidados**
  - `record Person(String name, Address address)` donde `Address` es otro Record
  - El parser encuentra `{` como primer byte y lo trata como primitivo — resultado incorrecto
  - Requiere: branch `else if (firstByte == '{')`, `findObjectEnd`, y llamada recursiva a `parse(MemorySegment)`

- [x] **`enum`**
  - `record Event(String name, Status status)` con `enum Status { ACTIVE, INACTIVE }`
  - Hoy `convertType` devuelve el String del nombre del enum en vez de la constante
  - Fix: en `convertType`, si el tipo es `type.isEnum()` → `Enum.valueOf(type, val)`

- [x] **`BigDecimal` / `BigInteger`**
  - Necesarios en contextos financieros donde `double` no tiene precisión suficiente
  - Fix: agregar cases en `convertType`

---

## Prioridad baja

- [x] **`short` / `byte` / `char`**
  - `initArgs()` ya los inicializa correctamente, pero `convertType` los deja como `String`
  - Fix: tres cases adicionales en `convertType`

- [x] **Arrays primitivos (`int[]`, `String[]`)**
  - Hoy solo se soporta `List<T>`
  - Opción: al final de `parseArrayValue`, si el tipo destino es array (`type.isArray()`), convertir el `List` a array nativo

- [x] **Tipos de fecha/hora**
  - `LocalDate`, `LocalDateTime`, `Instant` — comunes en APIs REST
  - Requiere acordar formato (ISO-8601 recomendado)
  - Fix: cases en `convertType` usando `LocalDate.parse(val)`, etc.

---

## Archivos impactados por estas tareas

| Tarea | Archivo principal |
|---|---|
| Escapes, null, notación científica, float, short, byte, enum, BigDecimal, fecha | `JsonParser.java` → `convertType`, `extractString`, `assignValue` |
| Records anidados | `JsonParser.java` → loop principal + método recursivo |
| Serializar enum, BigDecimal, fecha, arrays nativos | `JsonSerializer.java` → `appendJsonValue` |
