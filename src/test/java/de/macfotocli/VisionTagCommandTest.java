package de.macfotocli;

import org.junit.jupiter.api.Test;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VisionTagCommandTest {

    private final VisionTagCommand command = new VisionTagCommand();

    @Test
    void testIsImageFile_ValidExtensions() {
        assertTrue(command.isImageFile(Paths.get("test.jpg")));
        assertTrue(command.isImageFile(Paths.get("test.jpeg")));
        assertTrue(command.isImageFile(Paths.get("test.png")));
        assertTrue(command.isImageFile(Paths.get("test.webp")));
        assertTrue(command.isImageFile(Paths.get("TEST.JPG")));
    }

    @Test
    void testIsImageFile_InvalidExtensions() {
        assertFalse(command.isImageFile(Paths.get("test.txt")));
        assertFalse(command.isImageFile(Paths.get("test.mp4")));
        assertFalse(command.isImageFile(Paths.get("test.pdf")));
    }

    @Test
    void testIsImageFile_ExcludesHiddenAndSpecial() {
        assertFalse(command.isImageFile(Paths.get("._test.jpg")));
        assertFalse(command.isImageFile(Paths.get("/some/path/@eaDir/test.jpg")));
    }

    @Test
    void testCleanOllamaResponse_Simple() {
        String input = "Berlin, Urlaub, Sommer";
        List<String> tags = command.cleanOllamaResponse(input);
        assertEquals(3, tags.size());
        assertEquals("Berlin", tags.get(0));
        assertEquals("Urlaub", tags.get(1));
        assertEquals("Sommer", tags.get(2));
    }

    @Test
    void testCleanOllamaResponse_WithThoughtBlock() {
        String input = "<thought>I should generate tags for this image.</thought>Berlin, Urlaub";
        List<String> tags = command.cleanOllamaResponse(input);
        assertEquals(2, tags.size());
        assertEquals("Berlin", tags.get(0));
        assertEquals("Urlaub", tags.get(1));
    }

    @Test
    void testCleanOllamaResponse_WithThinkingMarkers() {
        String input = "Thinking. Some process info here. done thinking.Berlin, Urlaub";
        List<String> tags = command.cleanOllamaResponse(input);
        assertEquals(2, tags.size());
        assertEquals("Berlin", tags.get(0));
        assertEquals("Urlaub", tags.get(1));
    }

    @Test
    void testCleanOllamaResponse_WithQuotesAndTrailingDot() {
        String input = "'Berlin', \"Urlaub\".";
        List<String> tags = command.cleanOllamaResponse(input);
        assertEquals(2, tags.size());
        assertEquals("Berlin", tags.get(0));
        assertEquals("Urlaub", tags.get(1));
    }

    @Test
    void testCleanOllamaResponse_NullOrEmpty() {
        assertTrue(command.cleanOllamaResponse("").isEmpty());
        assertTrue(command.cleanOllamaResponse("null").isEmpty());
    }
}
