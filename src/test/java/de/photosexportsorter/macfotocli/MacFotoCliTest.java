package de.photosexportsorter.macfotocli;

import org.junit.jupiter.api.Test;
import java.io.File;
import static org.junit.jupiter.api.Assertions.*;

class MacFotoCliTest {

    @Test
    void testReSortWithLocationAndGermanDate() {
        SortCommand cmd = new SortCommand();
        cmd.target = new File("/tmp/target");

        // Input: "Berlin, 15. März 2024"
        SortCommand.PathInfo result = cmd.re_sort_location_date("/source/Berlin, 15. März 2024", "de");

        // Expected: /tmp/target/2024/2024-03-15, Berlin
        assertTrue(result.targetDir().endsWith("2024/2024-03-15, Berlin") ||
                result.targetDir().endsWith("2024\\2024-03-15, Berlin"),
                "Target dir should be correctly formatted. Got: " + result.targetDir());
    }

    @Test
    void testReSortWithDateOnly() {
        SortCommand cmd = new SortCommand();
        cmd.target = new File("/tmp/target");

        // Input: "20. April 2024"
        SortCommand.PathInfo result = cmd.re_sort_location_date("/source/20. April 2024", "de");

        // Expected: /tmp/target/2024/2024-04-20
        assertTrue(result.targetDir().endsWith("2024/2024-04-20") ||
                result.targetDir().endsWith("2024\\2024-04-20"),
                "Target dir should be correctly formatted. Got: " + result.targetDir());
    }

    @Test
    void testReSortWithEnglishDate() {
        SortCommand cmd = new SortCommand();
        cmd.target = new File("/tmp/target");

        // Input: "London, May 15, 2024" 
        SortCommand.PathInfo result = cmd.re_sort_location_date("/source/London, May 15, 2024", "en");

        assertTrue(result.targetDir().endsWith("2024/2024-05-15, London") ||
                result.targetDir().endsWith("2024\\2024-05-15, London"),
                "Target dir should be correctly formatted. Got: " + result.targetDir());
    }

    @Test
    void testProblematicDateConversionWithUnicodeDecomposition() {
        SortCommand cmd = new SortCommand();
        cmd.target = new File("/tmp/target");

        // Mac's 'März' can be 'März' (a + diaeresis)
        String decomposedMaerz = "Ma\u0308rz";
        String path = "/source/Berlin, 15. " + decomposedMaerz + " 2024";

        SortCommand.PathInfo result = cmd.re_sort_location_date(path, "de");

        assertTrue(result.targetDir().contains("2024-03-15"),
                "Should handle NFD decomposition. Got: " + result.targetDir());
    }
}
