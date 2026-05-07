package de.photosexportsorter.macfotocli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FixDateCommandTest {

    @Test
    void testExtractDateFromPath() {
        assertEquals("2024-03-15", FixDateCommand.extractDateFromPath(Path.of("/source/2024/2024-03-15, Berlin/img.jpg")));
        assertEquals("2019-10-12", FixDateCommand.extractDateFromPath(Path.of("2019-10-12/img.jpg")));
        assertEquals("2004-01-29", FixDateCommand.extractDateFromPath(Path.of("/Volumes/Backup/2004-01-29/DSC00328.JPG")));
        assertNull(FixDateCommand.extractDateFromPath(Path.of("/source/NoDateHere/img.jpg")));
    }

    @Test
    void testDetermineNewDateTime_Match() {
        assertNull(FixDateCommand.determineNewDateTime("2024-03-15", "2024:03:15 13:45:00"));
        assertNull(FixDateCommand.determineNewDateTime("2024-03-15", "2024:03:15"));
    }

    @Test
    void testDetermineNewDateTime_Mismatch() {
        // Keeps the existing time if the date doesn't match
        assertEquals("2024:03:15 13:45:00", FixDateCommand.determineNewDateTime("2024-03-15", "2020:01:01 13:45:00"));
        
        // Adds default time if time is completely missing
        assertEquals("2024:03:15 12:00:00", FixDateCommand.determineNewDateTime("2024-03-15", "2020:01:01"));
        
        // Handles null or empty existing date
        assertEquals("2024:03:15 12:00:00", FixDateCommand.determineNewDateTime("2024-03-15", null));
        assertEquals("2024:03:15 12:00:00", FixDateCommand.determineNewDateTime("2024-03-15", "   "));
        
        // Retains invalid original time format string length
        assertEquals("2024:03:15 12:30", FixDateCommand.determineNewDateTime("2024-03-15", "2020:01:01 12:30"));
    }
}
