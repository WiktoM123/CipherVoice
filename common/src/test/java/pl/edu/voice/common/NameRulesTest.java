package pl.edu.voice.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameRulesTest {
    @Test
    void acceptsLettersDigitsUnderscoreAndDash() {
        assertTrue(NameRules.isValid("pokoj"));
        assertTrue(NameRules.isValid("Pokój_1-A"));
        assertTrue(NameRules.isValid("a".repeat(40)));
    }

    @Test
    void rejectsEmptyTooLongAndSpecialCharacters() {
        assertFalse(NameRules.isValid(null));
        assertFalse(NameRules.isValid(""));
        assertFalse(NameRules.isValid("a".repeat(41)));
        assertFalse(NameRules.isValid("room name"));
        assertFalse(NameRules.isValid("room/name"));
        assertFalse(NameRules.isValid("room\nname"));
        assertThrows(IllegalArgumentException.class,
                () -> NameRules.requireValid("Nickname", "zły nick"));
    }
}
