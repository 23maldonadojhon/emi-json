# Guía de Usuario: EmiJson 📖

Bienvenido a **EmiJson**, el parser de JSON para Java Records más rápido del ecosistema. Esta guía te enseñará cómo sacarle el máximo provecho.

## 📦 Instalación
Asegúrate de estar usando **JDK 25** o superior, ya que EmiJson depende de la **Class-File API** y la **Vector API**.

En tu `build.gradle.kts`, habilita los módulos de incubación:
```kotlin
tasks.withType<JavaCompile> {
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}
tasks.withType<Test> {
    jvmArgs("--add-modules=jdk.incubator.vector")
}
```

## 🚀 Uso Básico

### 1. Define tu Record
EmiJson está optimizado exclusivamente para **Java Records**.
```java
public record User(
    int id, 
    String name, 
    boolean active,
    LocalDate joinDate
) {}
```

### 2. Crea una instancia de EmiJson
Se recomienda reutilizar la instancia de `EmiJson`, ya que la primera vez genera el bytecode necesario y lo cachea.
```java
EmiJson<User> emi = new EmiJson<>(User.class);
```

### 3. Parsear JSON
```java
String json = "{\"id\": 1, \"name\": \"Emi\", \"active\": true, \"joinDate\": \"2024-01-01\"}";
User user = emi.parse(json);
```

### 4. Serializar a JSON
```java
// Formato compacto (ideal para APIs)
String compact = emi.toJson(user);

// Formato Pretty-Print (ideal para debug)
String pretty = emi.toPrettyJson(user);
```

## 🛠️ Funcionalidades Avanzadas

### Soporte de Tipos
EmiJson soporta una amplia gama de tipos de forma nativa:
- **Primitivos:** `int`, `long`, `double`, `float`, `boolean`, `byte`, `short`, `char`.
- **Objetos:** `String`, `BigDecimal`, `BigInteger`.
- **Fechas:** `LocalDate`, `LocalDateTime`, `Instant`.
- **Colecciones:** `List<T>` y Arreglos nativos (`int[]`, `String[]`, etc.).
- **Anidación:** Records dentro de Records.

### Notación Científica
El parser detecta automáticamente si un número viene en notación científica (ej: `1e2`) y lo convierte correctamente al tipo destino sin pérdida de precisión.

### Modo Estricto
Puedes activar el modo estricto para asegurar que el JSON contenga todos los campos definidos en el Record:
```java
User user = emi.parseStrict(json); // Lanza JsonMappingException si falta algún campo
```

## 📊 Rendimiento
EmiJson está diseñado para latencias de **sub-microsegundo**. 
- **Parsing:** ~880ns por registro pequeño.
- **Allocation:** Zero-allocation en la navegación (solo se crea el objeto final).

## ⚠️ Manejo de Errores
- `JsonParseException`: Error de sintaxis en el JSON (incluye la posición del error).
- `JsonMappingException`: El JSON es válido pero no coincide con la estructura del Record.
