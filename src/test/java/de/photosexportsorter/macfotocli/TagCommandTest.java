package de.photosexportsorter.macfotocli;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
