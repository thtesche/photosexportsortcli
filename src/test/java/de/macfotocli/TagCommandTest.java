package de.macfotocli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagCommandTest {

    @Test
    void testFilterNewTags_NoExisting() {
        List<String> existing = Collections.emptyList();
        List<String> generated = Arrays.asList("Berlin", "Urlaub");

        List<String> result = TagCommand.filterNewTags(existing, generated);

        assertEquals(2, result.size());
        assertTrue(result.contains("Berlin"));
        assertTrue(result.contains("Urlaub"));
    }

    @Test
    void testFilterNewTags_AllExist() {
        List<String> existing = Arrays.asList("Berlin", "Urlaub");
        List<String> generated = Arrays.asList("Berlin", "Urlaub");

        List<String> result = TagCommand.filterNewTags(existing, generated);

        assertTrue(result.isEmpty());
    }

    @Test
    void testFilterNewTags_CaseInsensitiveExisting() {
        List<String> existing = Arrays.asList("berlin", "urlaub");
        List<String> generated = Arrays.asList("Berlin", "München");

        List<String> result = TagCommand.filterNewTags(existing, generated);

        assertEquals(1, result.size());
        assertEquals("München", result.get(0));
    }

    @Test
    void testFilterNewTags_DuplicatesInGenerated() {
        List<String> existing = Collections.emptyList();
        List<String> generated = Arrays.asList("Berlin", "berlin", "München");

        List<String> result = TagCommand.filterNewTags(existing, generated);

        assertEquals(2, result.size());
        assertEquals("Berlin", result.get(0));
        assertEquals("München", result.get(1));
    }

    @Test
    void testFilterNewTags_WhitespaceHandling() {
        List<String> existing = Arrays.asList("berlin");
        List<String> generated = Arrays.asList(" Berlin ", "  München  ");

        List<String> result = TagCommand.filterNewTags(existing, generated);

        assertEquals(1, result.size());
        assertEquals("München", result.get(0));
    }

    @Test
    void testMergeTags_CombinesAndPreservesOrder() {
        List<String> existing = Arrays.asList("Berlin", "Urlaub");
        List<String> generated = Arrays.asList("Sommer", "Berlin", "Sonne");

        List<String> result = TagCommand.mergeTags(existing, generated);

        assertEquals(4, result.size());
        assertEquals("Berlin", result.get(0));
        assertEquals("Urlaub", result.get(1));
        assertEquals("Sommer", result.get(2));
        assertEquals("Sonne", result.get(3));
    }

    @Test
    void testMergeTags_HandlesEmptyAndWhitespace() {
        List<String> existing = Arrays.asList("Berlin");
        List<String> generated = Arrays.asList("", "  München  ", null, "Berlin");

        List<String> result = TagCommand.mergeTags(existing, generated);

        assertEquals(2, result.size());
        assertEquals("Berlin", result.get(0));
        assertEquals("München", result.get(1));
    }

    @Test
    void testHasWords() {
        assertTrue(TagCommand.hasWords("Berlin/Urlaub/IMG_1234.jpg"));
        assertTrue(TagCommand.hasWords("Vacation 2024/12345.png"));
        assertTrue(TagCommand.hasWords("JustAFile.webp"));
        assertTrue(TagCommand.hasWords("Bär.jpg")); // Umlaut
        assertTrue(TagCommand.hasWords("2012/2012-07-18, Oberstdorf/004 (1).jpg"));

        // No letters in name/path
        assertFalse(TagCommand.hasWords("12345.jpg"));
        assertFalse(TagCommand.hasWords("2024/05/12/999.mp4"));

        // Ignored prefixes
        assertFalse(TagCommand.hasWords("IMG_1234.jpg"));
        assertFalse(TagCommand.hasWords("DSC_0001.JPG"));
        assertFalse(TagCommand.hasWords("2024/WP_12345.jpg"));
        assertFalse(TagCommand.hasWords("Screenshot_2024-05-01.png"));

        // Too short
        assertFalse(TagCommand.hasWords("P/123.jpg"));
        assertFalse(TagCommand.hasWords("AB.jpg"));
        assertFalse(TagCommand.hasWords("2012/2012-07-18/P003412412404.jpg"));
    }
}
