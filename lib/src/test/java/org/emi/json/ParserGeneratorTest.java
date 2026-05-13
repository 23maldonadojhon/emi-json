package org.emi.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ParserGeneratorTest {

    public record User(int id, String name, boolean active) {
    }

    @Test
    public void testGenerator() {
        RecordInstantiator<User> instantiator = ParserGenerator.generateInstantiator(User.class);
        assertNotNull(instantiator);

        Object[] args = { 1, "Emi", true };
        User user = instantiator.instantiate(args);

        assertEquals(1, user.id());
        assertEquals("Emi", user.name());
        assertTrue(user.active());
    }
}
