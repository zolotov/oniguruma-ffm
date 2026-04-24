package me.zolotov.oniguruma;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnigurumaTest {
    @Test
    void createRegexAndStringMakeDefensiveCopies() {
        byte[] pattern = "[0-9]+".getBytes(StandardCharsets.UTF_8);
        byte[] text = "12:00pm".getBytes(StandardCharsets.UTF_8);

        try (var oniguruma = Oniguruma.createFromResources()) {
            var regex = oniguruma.createRegex(pattern);
            var string = oniguruma.createString(text);

            pattern[0] = 'x';
            text[0] = 'y';

            assertArrayEquals("[0-9]+".getBytes(StandardCharsets.UTF_8), regex.pattern());
            assertArrayEquals("12:00pm".getBytes(StandardCharsets.UTF_8), string.utf8Content());

            oniguruma.freeRegex(regex);
            oniguruma.freeString(string);
        }
    }

    @Test
    void invalidPatternRaisesRuntimeException() {
        try (var oniguruma = Oniguruma.createFromResources()) {
            var exception = assertThrows(
                    RuntimeException.class,
                    () -> oniguruma.createRegex("(".getBytes(StandardCharsets.UTF_8))
            );
            assertTrue(
                    exception.getMessage().toLowerCase().contains("parenthes")
                            || exception.getMessage().toLowerCase().contains("compile"),
                    () -> "unexpected error message: " + exception.getMessage()
            );
        }
    }

    @Test
    void matchRequestRejectsNegativeOffsets() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new OnigurumaMatchRequest(-1, true, true)
        );

        assertEquals("byteOffset must be non-negative", exception.getMessage());
    }
}
