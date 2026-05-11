package org.emi.json;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmiJsonTest {

    record Person(String name, int age, double score, boolean active) {}
    record Numbers(int count, long total, double ratio) {}
    record WithList(String name, List<String> tags, List<Integer> scores) {}
    record WithEmptyList(String name, List<String> items) {}
    record WithFloat(String name, float value) {}
    record Address(String city, int zip) {}
    record PersonWithAddress(String name, Address address) {}
    enum Status { ACTIVE, INACTIVE }
    record Event(String name, Status status) {}
    record WithBigNumbers(BigDecimal price, BigInteger quantity) {}
    record WithSmallTypes(short count, byte flag, char initial) {}
    record WithIntArray(String name, int[] scores) {}
    record WithStringArray(String name, String[] tags) {}
    record WithDates(LocalDate date, LocalDateTime dateTime, Instant timestamp) {}

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructorRejectsNonRecord() {
        assertThrows(IllegalArgumentException.class, () -> new EmiJson<>(String.class));
    }

    @Test
    void constructorAcceptsRecord() {
        assertDoesNotThrow(() -> new EmiJson<>(Person.class));
    }

    // -------------------------------------------------------------------------
    // parse()
    // -------------------------------------------------------------------------

    @Test
    void parseStringAndInt() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"Ana\",\"age\":30,\"score\":9.5,\"active\":true}");
        assertEquals("Ana", p.name());
        assertEquals(30, p.age());
    }

    @Test
    void parseDouble() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"X\",\"age\":0,\"score\":3.14,\"active\":false}");
        assertEquals(3.14, p.score());
    }

    @Test
    void parseBoolean() {
        var parser = new EmiJson<>(Person.class);
        Person pTrue  = parser.parse("{\"name\":\"A\",\"age\":1,\"score\":1.0,\"active\":true}");
        Person pFalse = parser.parse("{\"name\":\"B\",\"age\":2,\"score\":2.0,\"active\":false}");
        assertTrue(pTrue.active());
        assertFalse(pFalse.active());
    }

    @Test
    void parseLong() {
        var parser = new EmiJson<>(Numbers.class);
        Numbers n = parser.parse("{\"count\":5,\"total\":9999999999,\"ratio\":1.5}");
        assertEquals(9_999_999_999L, n.total());
    }

    @Test
    void parseNegativeInt() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"X\",\"age\":-5,\"score\":0.0,\"active\":false}");
        assertEquals(-5, p.age());
    }

    @Test
    void parseIgnoresUnknownKeys() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"Bob\",\"age\":25,\"score\":7.0,\"active\":false,\"extra\":\"ignored\"}");
        assertEquals("Bob", p.name());
        assertEquals(25, p.age());
    }

    @Test
    void parseWithLeadingWhitespace() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("   {\"name\":\"Ana\",\"age\":30,\"score\":9.5,\"active\":true}");
        assertEquals("Ana", p.name());
    }

    // -------------------------------------------------------------------------
    // toJson()
    // -------------------------------------------------------------------------

    @Test
    void toJsonStartsWithBrace() {
        var parser = new EmiJson<>(Person.class);
        String json = parser.toJson(new Person("Ana", 30, 9.5, true));
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
    }

    @Test
    void toJsonQuotesStringValues() {
        var parser = new EmiJson<>(Person.class);
        String json = parser.toJson(new Person("Ana", 30, 9.5, true));
        assertTrue(json.contains("\"name\":\"Ana\""));
    }

    @Test
    void toJsonDoesNotQuoteBoolean() {
        var parser = new EmiJson<>(Person.class);
        String json = parser.toJson(new Person("X", 0, 0.0, false));
        assertTrue(json.contains("\"active\":false"));
        assertFalse(json.contains("\"false\""));
    }

    @Test
    void toJsonDoesNotQuoteNumbers() {
        var parser = new EmiJson<>(Person.class);
        String json = parser.toJson(new Person("X", 42, 3.14, true));
        assertTrue(json.contains("\"age\":42"));
        assertTrue(json.contains("\"score\":3.14"));
        assertFalse(json.contains("\"42\""));
    }

    // -------------------------------------------------------------------------
    // toBytes()
    // -------------------------------------------------------------------------

    @Test
    void toBytesIsUtf8() {
        var parser = new EmiJson<>(Person.class);
        byte[] bytes = parser.toBytes(new Person("Ana", 30, 9.5, true));
        String result = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(result.startsWith("{"));
        assertTrue(result.contains("Ana"));
    }

    @Test
    void toBytesMatchesToJson() {
        var parser = new EmiJson<>(Person.class);
        Person p = new Person("Ana", 30, 9.5, true);
        String json = parser.toJson(p);
        byte[] bytes = parser.toBytes(p);
        assertEquals(json, new String(bytes, StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Round-trip
    // -------------------------------------------------------------------------

    @Test
    void roundTripPreservesAllFields() {
        var parser = new EmiJson<>(Person.class);
        Person original = new Person("Ana", 30, 9.5, true);
        Person recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    @Test
    void roundTripWithFalseBoolean() {
        var parser = new EmiJson<>(Person.class);
        Person original = new Person("Bob", 25, 7.0, false);
        Person recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    // -------------------------------------------------------------------------
    // Arrays / Lists
    // -------------------------------------------------------------------------

    @Test
    void parseStringList() {
        var parser = new EmiJson<>(WithList.class);
        WithList r = parser.parse("{\"name\":\"Ana\",\"tags\":[\"java\",\"json\"],\"scores\":[10,20]}");
        assertEquals(List.of("java", "json"), r.tags());
    }

    @Test
    void parseIntList() {
        var parser = new EmiJson<>(WithList.class);
        WithList r = parser.parse("{\"name\":\"Ana\",\"tags\":[],\"scores\":[1,2,3]}");
        assertEquals(List.of(1, 2, 3), r.scores());
    }

    @Test
    void parseEmptyList() {
        var parser = new EmiJson<>(WithEmptyList.class);
        WithEmptyList r = parser.parse("{\"name\":\"X\",\"items\":[]}");
        assertTrue(r.items().isEmpty());
    }

    @Test
    void toJsonSerializesList() {
        var parser = new EmiJson<>(WithList.class);
        WithList r = new WithList("Ana", List.of("a", "b"), List.of(1, 2));
        String json = parser.toJson(r);
        assertTrue(json.contains("\"tags\":[\"a\",\"b\"]"));
        assertTrue(json.contains("\"scores\":[1,2]"));
    }

    // -------------------------------------------------------------------------
    // float / Float
    // -------------------------------------------------------------------------

    @Test
    void parseFloat() {
        var parser = new EmiJson<>(WithFloat.class);
        WithFloat r = parser.parse("{\"name\":\"x\",\"value\":3.14}");
        assertEquals(3.14f, r.value(), 0.001f);
    }

    @Test
    void parseFloatZero() {
        var parser = new EmiJson<>(WithFloat.class);
        WithFloat r = parser.parse("{\"name\":\"x\",\"value\":0.0}");
        assertEquals(0.0f, r.value());
    }

    @Test
    void roundTripFloat() {
        var parser = new EmiJson<>(WithFloat.class);
        WithFloat original = new WithFloat("x", 3.14f);
        WithFloat recovered = parser.parse(parser.toJson(original));
        assertEquals(original.value(), recovered.value(), 0.0001f);
    }

    // -------------------------------------------------------------------------
    // Notación científica
    // -------------------------------------------------------------------------

    @Test
    void parseScientificDouble() {
        var parser = new EmiJson<>(Numbers.class);
        Numbers n = parser.parse("{\"count\":0,\"total\":0,\"ratio\":1.5e2}");
        assertEquals(150.0, n.ratio());
    }

    @Test
    void parseScientificNegativeExponent() {
        var parser = new EmiJson<>(Numbers.class);
        Numbers n = parser.parse("{\"count\":0,\"total\":0,\"ratio\":2.3E-4}");
        assertEquals(2.3e-4, n.ratio(), 1e-10);
    }

    @Test
    void parseScientificInt() {
        var parser = new EmiJson<>(Numbers.class);
        Numbers n = parser.parse("{\"count\":1e2,\"total\":0,\"ratio\":0.0}");
        assertEquals(100, n.count());
    }

    @Test
    void parseScientificLong() {
        var parser = new EmiJson<>(Numbers.class);
        Numbers n = parser.parse("{\"count\":0,\"total\":2E3,\"ratio\":0.0}");
        assertEquals(2000L, n.total());
    }

    // -------------------------------------------------------------------------
    // null literal en campos de referencia
    // -------------------------------------------------------------------------

    @Test
    void parseNullStringField() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":null,\"age\":30,\"score\":9.5,\"active\":true}");
        assertNull(p.name());
        assertEquals(30, p.age());
    }

    @Test
    void parseNullListField() {
        var parser = new EmiJson<>(WithList.class);
        WithList r = parser.parse("{\"name\":\"X\",\"tags\":null,\"scores\":[]}");
        assertNull(r.tags());
    }

    @Test
    void roundTripNullField() {
        var parser = new EmiJson<>(Person.class);
        Person original = new Person(null, 30, 9.5, true);
        Person recovered = parser.parse(parser.toJson(original));
        assertNull(recovered.name());
        assertEquals(30, recovered.age());
    }

    // -------------------------------------------------------------------------
    // Escape sequences
    // -------------------------------------------------------------------------

    @Test
    void parseEscapedQuote() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"O\\\"Brien\",\"age\":0,\"score\":0.0,\"active\":false}");
        assertEquals("O\"Brien", p.name());
    }

    @Test
    void parseEscapedBackslash() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"C:\\\\Users\",\"age\":0,\"score\":0.0,\"active\":false}");
        assertEquals("C:\\Users", p.name());
    }

    @Test
    void parseEscapeTab() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"hello\\tworld\",\"age\":0,\"score\":0.0,\"active\":false}");
        assertEquals("hello\tworld", p.name());
    }

    @Test
    void parseUnicodeEscape() {
        var parser = new EmiJson<>(Person.class);
        Person p = parser.parse("{\"name\":\"caf\\u00e9\",\"age\":0,\"score\":0.0,\"active\":false}");
        assertEquals("café", p.name());
    }

    @Test
    void roundTripEscapedString() {
        var parser = new EmiJson<>(Person.class);
        Person original = new Person("O\"Brien", 30, 9.5, true);
        Person recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    @Test
    void toJsonEscapesSpecialChars() {
        var parser = new EmiJson<>(Person.class);
        String json = parser.toJson(new Person("a\"b\\c", 0, 0.0, false));
        assertTrue(json.contains("\"a\\\"b\\\\c\""));
    }

    @Test
    void roundTripList() {
        var parser = new EmiJson<>(WithList.class);
        WithList original = new WithList("Ana", List.of("x", "y"), List.of(10, 20, 30));
        WithList recovered = parser.parse(parser.toJson(original));
        assertEquals(original.name(), recovered.name());
        assertEquals(original.tags(), recovered.tags());
        assertEquals(original.scores(), recovered.scores());
    }

    // -------------------------------------------------------------------------
    // Records anidados
    // -------------------------------------------------------------------------

    @Test
    void parseNestedRecord() {
        var parser = new EmiJson<>(PersonWithAddress.class);
        PersonWithAddress p = parser.parse("{\"name\":\"Ana\",\"address\":{\"city\":\"Madrid\",\"zip\":28001}}");
        assertEquals("Ana", p.name());
        assertEquals("Madrid", p.address().city());
        assertEquals(28001, p.address().zip());
    }

    @Test
    void roundTripNestedRecord() {
        var parser = new EmiJson<>(PersonWithAddress.class);
        PersonWithAddress original = new PersonWithAddress("Ana", new Address("Madrid", 28001));
        PersonWithAddress recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    // -------------------------------------------------------------------------
    // enum
    // -------------------------------------------------------------------------

    @Test
    void parseEnum() {
        var parser = new EmiJson<>(Event.class);
        Event e = parser.parse("{\"name\":\"launch\",\"status\":\"ACTIVE\"}");
        assertEquals(Status.ACTIVE, e.status());
    }

    @Test
    void parseEnumInactive() {
        var parser = new EmiJson<>(Event.class);
        Event e = parser.parse("{\"name\":\"shutdown\",\"status\":\"INACTIVE\"}");
        assertEquals(Status.INACTIVE, e.status());
    }

    @Test
    void roundTripEnum() {
        var parser = new EmiJson<>(Event.class);
        Event original = new Event("launch", Status.ACTIVE);
        Event recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    // -------------------------------------------------------------------------
    // BigDecimal / BigInteger
    // -------------------------------------------------------------------------

    @Test
    void parseBigDecimal() {
        var parser = new EmiJson<>(WithBigNumbers.class);
        WithBigNumbers r = parser.parse("{\"price\":3.14159265358979323846,\"quantity\":1}");
        assertEquals(new BigDecimal("3.14159265358979323846"), r.price());
    }

    @Test
    void parseBigInteger() {
        var parser = new EmiJson<>(WithBigNumbers.class);
        WithBigNumbers r = parser.parse("{\"price\":1,\"quantity\":123456789012345678901234567890}");
        assertEquals(new BigInteger("123456789012345678901234567890"), r.quantity());
    }

    @Test
    void roundTripBigDecimal() {
        var parser = new EmiJson<>(WithBigNumbers.class);
        WithBigNumbers original = new WithBigNumbers(new BigDecimal("3.14"), new BigInteger("42"));
        WithBigNumbers recovered = parser.parse(parser.toJson(original));
        assertEquals(original.price(), recovered.price());
        assertEquals(original.quantity(), recovered.quantity());
    }

    // -------------------------------------------------------------------------
    // short / byte / char
    // -------------------------------------------------------------------------

    @Test
    void parseSmallTypes() {
        var parser = new EmiJson<>(WithSmallTypes.class);
        WithSmallTypes r = parser.parse("{\"count\":42,\"flag\":7,\"initial\":\"X\"}");
        assertEquals((short) 42, r.count());
        assertEquals((byte) 7,   r.flag());
        assertEquals('X',        r.initial());
    }

    @Test
    void roundTripSmallTypes() {
        var parser = new EmiJson<>(WithSmallTypes.class);
        WithSmallTypes original = new WithSmallTypes((short) 100, (byte) 3, 'Z');
        WithSmallTypes recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }

    // -------------------------------------------------------------------------
    // Arrays nativos (int[], String[])
    // -------------------------------------------------------------------------

    @Test
    void parseIntArray() {
        var parser = new EmiJson<>(WithIntArray.class);
        WithIntArray r = parser.parse("{\"name\":\"x\",\"scores\":[1,2,3]}");
        assertArrayEquals(new int[]{1, 2, 3}, r.scores());
    }

    @Test
    void parseStringArray() {
        var parser = new EmiJson<>(WithStringArray.class);
        WithStringArray r = parser.parse("{\"name\":\"x\",\"tags\":[\"a\",\"b\"]}");
        assertArrayEquals(new String[]{"a", "b"}, r.tags());
    }

    @Test
    void toJsonIntArray() {
        var parser = new EmiJson<>(WithIntArray.class);
        String json = parser.toJson(new WithIntArray("x", new int[]{4, 5, 6}));
        assertTrue(json.contains("\"scores\":[4,5,6]"));
    }

    @Test
    void toJsonStringArray() {
        var parser = new EmiJson<>(WithStringArray.class);
        String json = parser.toJson(new WithStringArray("x", new String[]{"p", "q"}));
        assertTrue(json.contains("\"tags\":[\"p\",\"q\"]"));
    }

    // -------------------------------------------------------------------------
    // LocalDate / LocalDateTime / Instant
    // -------------------------------------------------------------------------

    @Test
    void parseLocalDate() {
        var parser = new EmiJson<>(WithDates.class);
        WithDates r = parser.parse("{\"date\":\"2024-06-15\",\"dateTime\":\"2024-06-15T10:30:00\",\"timestamp\":\"2024-06-15T10:30:00Z\"}");
        assertEquals(LocalDate.of(2024, 6, 15), r.date());
    }

    @Test
    void parseLocalDateTime() {
        var parser = new EmiJson<>(WithDates.class);
        WithDates r = parser.parse("{\"date\":\"2024-06-15\",\"dateTime\":\"2024-06-15T10:30:00\",\"timestamp\":\"2024-06-15T10:30:00Z\"}");
        assertEquals(LocalDateTime.of(2024, 6, 15, 10, 30, 0), r.dateTime());
    }

    @Test
    void parseInstant() {
        var parser = new EmiJson<>(WithDates.class);
        WithDates r = parser.parse("{\"date\":\"2024-06-15\",\"dateTime\":\"2024-06-15T10:30:00\",\"timestamp\":\"2024-06-15T10:30:00Z\"}");
        assertEquals(Instant.parse("2024-06-15T10:30:00Z"), r.timestamp());
    }

    @Test
    void roundTripDates() {
        var parser = new EmiJson<>(WithDates.class);
        WithDates original = new WithDates(
                LocalDate.of(2024, 6, 15),
                LocalDateTime.of(2024, 6, 15, 10, 30, 0),
                Instant.parse("2024-06-15T10:30:00Z"));
        WithDates recovered = parser.parse(parser.toJson(original));
        assertEquals(original, recovered);
    }
}
