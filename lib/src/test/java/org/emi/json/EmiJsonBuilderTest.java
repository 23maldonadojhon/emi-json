package org.emi.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmiJsonBuilderTest {

    record Person(String name, int age) {
    }

    record Point(double x, double y) {
    }

    // -------------------------------------------------------------------------
    // EmiJson.of() — static factory
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void of_rejectsNonRecord() {
        assertThrows(IllegalArgumentException.class, () -> EmiJson.of((Class) String.class));
    }

    @Test
    void of_parseDirectly() {
        var json = """
                {
                    "name": "Emi",
                    "age": 1
                }
                """;

        Person p = EmiJson.of(Person.class).parse(json);
        assertEquals("Emi", p.name());
        assertEquals(1, p.age());
    }

    @Test
    void of_build_roundTrip() {
        var parser = EmiJson.of(Person.class).build();
        var original = new Person("Ilan", 13);
        assertEquals(original, parser.parse(parser.toJson(original)));
    }

    // -------------------------------------------------------------------------
    // strict()
    // -------------------------------------------------------------------------

    @Test
    void strict_throwsOnMissingField() {
        var json = """
                {
                    "name": "Emi"
                }
                """;

        var parser = EmiJson.of(Person.class).strict().build();
        var ex = assertThrows(RuntimeException.class, () -> parser.parse(json));
        assertTrue(ex.getMessage().contains("age"), "Error must name the missing field");
    }

    @Test
    void strict_passesWhenAllFieldsPresent() {
        var json = """
                {
                    "name": "Emi",
                    "age": 1
                }
                """;

        var parser = EmiJson.of(Person.class).strict().build();
        assertDoesNotThrow(() -> parser.parse(json));
    }

    @Test
    void nonStrict_doesNotThrowOnMissingField() {
        var json = """
                {
                    "name": "Emi",
                }
                """;

        var parser = EmiJson.of(Person.class).build();
        assertDoesNotThrow(() -> parser.parse(json));
    }

    // -------------------------------------------------------------------------
    // prettyPrint()
    // -------------------------------------------------------------------------

    @Test
    void prettyPrint_containsNewlinesAndIndent() {
        String json = EmiJson.of(Person.class).prettyPrint().toJson(new Person("Emi", 30));
        assertTrue(json.contains("\n"), "Pretty output must contain newlines");
        assertTrue(json.contains("  "), "Pretty output must contain indentation");
    }

    @Test
    void prettyPrint_isValidJson_roundTrips() {
        var parser = EmiJson.of(Person.class).prettyPrint().build();
        var original = new Person("Emi", 30);
        String pretty = parser.toJson(original);
        Person recovered = EmiJson.of(Person.class).parse(pretty);
        assertEquals(original, recovered);
    }

    @Test
    void compact_outputHasNoNewlines() {
        String json = EmiJson.of(Person.class).toJson(new Person("Emi", 30));
        assertFalse(json.contains("\n"), "Compact output must not contain newlines");
    }

    // -------------------------------------------------------------------------
    // Combinado: strict + prettyPrint
    // -------------------------------------------------------------------------

    @Test
    void strictAndPrettyPrint_combined() {
        var parser = EmiJson.of(Person.class).strict().prettyPrint().build();
        var original = new Person("Emi", 30);
        String pretty = parser.toJson(original);
        Person recovered = EmiJson.of(Person.class).strict().parse(pretty);
        assertEquals(original, recovered);
    }
}
