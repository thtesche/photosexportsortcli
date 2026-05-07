package de.photosexportsorter.macfotocli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Command(name = "fixdate", description = "Checks and fixes EXIF DateTimeOriginal based on directory names (YYYY-MM-DD).")
public class FixDateCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(arity = "1..*", description = "Directories containing photos to check and fix")
    private List<File> directories = new ArrayList<>();

    @Option(names = "--dryRun", description = "If set, only checks and prints differences without writing to the files")
    private boolean dryRun = false;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    @Override
    public Integer call() throws Exception {
        if (directories == null || directories.isEmpty()) {
            spec.commandLine().getErr().println(Ansi.AUTO.string("@|red Error: No directories specified.|@"));
            return 1;
        }

        if (dryRun) {
            spec.commandLine().getOut().println(Ansi.AUTO.string("@|bold,yellow [DRY RUN ENABLED - No files will be modified]|@\n"));
        }

        for (File dir : directories) {
            if (!dir.exists() || !dir.isDirectory()) {
                spec.commandLine().getErr().println(Ansi.AUTO.string("@|yellow Warning: Skipping invalid directory:|@ " + dir.getAbsolutePath()));
                continue;
            }

            spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|yellow Scanning directory:|@ " + dir.getAbsolutePath()));

            try (Stream<Path> stream = Files.walk(dir.toPath())) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isImageFile)
                        .forEach(this::processFile);
            }
        }

        spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|bold,green Date fixing complete.|@"));
        return 0;
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("._")) {
            return false;
        }
        name = name.toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".heic") ||
               name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v") || name.endsWith(".avi");
    }

    private boolean isVideoFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".m4v") || name.endsWith(".avi");
    }

    private void processFile(Path path) {
        String fullPath = path.toAbsolutePath().toString();

        // 1. Extract date from directory structure
        String expectedDate = extractDateFromPath(path);
        if (expectedDate == null) {
            spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string("@|cyan Analyzing:|@ " + fullPath + " ... @|yellow [Skipped: No YYYY-MM-DD found in path]|@\n"));
            spec.commandLine().getOut().flush();
            return;
        }

        spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string("@|cyan Analyzing:|@ " + fullPath));
        spec.commandLine().getOut().flush();

        try {
            // 2. Get existing EXIF date
            String existingDateTime = getExifDateTimeOriginal(path);
            
            // 3. Compare and determine new date
            String newDateTime = determineNewDateTime(expectedDate, existingDateTime);

            if (newDateTime == null) {
                // Matches perfectly! Do not print anything, next file will overwrite this line
                return;
            }

            spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string("@|yellow [Mismatch]|@ " + fullPath + " | Exif: '" + (existingDateTime == null ? "None" : existingDateTime) + "' -> New: '" + newDateTime + "' ... "));

            // 4. Write if not dry run
            if (dryRun) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow [Skipped writing]|@"));
            } else {
                writeExifDateTime(path, newDateTime);
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|green [Written]|@"));
            }

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|red [ERROR: " + msg + "]|@"));
        }
    }

    static String extractDateFromPath(Path path) {
        Path parent = path.getParent();
        if (parent == null) return null;
        Matcher matcher = DATE_PATTERN.matcher(parent.toString());
        String lastMatch = null;
        // In case there are multiple dates in the path, use the last one (deepest folder)
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        return lastMatch;
    }

    static String determineNewDateTime(String expectedDate, String existingDateTime) {
        String expectedExifDate = expectedDate.replace("-", ":"); // EXIF format is YYYY:MM:DD

        if (existingDateTime == null || existingDateTime.trim().isEmpty()) {
            return expectedExifDate + " 12:00:00";
        }

        // existingDateTime should be something like "2004:01:29 13:45:00"
        if (existingDateTime.length() >= 10) {
            String datePart = existingDateTime.substring(0, 10);
            if (datePart.equals(expectedExifDate)) {
                return null; // They match!
            }
            
            // They don't match, preserve the time if it exists
            if (existingDateTime.length() > 10) {
                String timePart = existingDateTime.substring(10);
                return expectedExifDate + timePart;
            } else {
                return expectedExifDate + " 12:00:00";
            }
        }

        return expectedExifDate + " 12:00:00";
    }

    private String getExifDateTimeOriginal(Path path) throws IOException, InterruptedException {
        ProcessBuilder pb;
        if (isVideoFile(path)) {
            pb = new ProcessBuilder(
                    "exiftool",
                    "-api", "QuickTimeUTC",
                    "-DateTimeOriginal",
                    "-CreationDate",
                    "-CreateDate",
                    "-j",
                    path.toAbsolutePath().toString()
            );
        } else {
            pb = new ProcessBuilder(
                    "exiftool",
                    "-DateTimeOriginal",
                    "-j",
                    path.toAbsolutePath().toString()
            );
        }
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        process.waitFor();
        
        if (output.startsWith("[")) {
            JsonNode root = mapper.readTree(output);
            if (root.isArray() && root.size() > 0) {
                JsonNode first = root.get(0);
                if (first.has("DateTimeOriginal")) {
                    return first.get("DateTimeOriginal").asText();
                } else if (first.has("CreationDate")) {
                    return first.get("CreationDate").asText();
                } else if (first.has("CreateDate")) {
                    return first.get("CreateDate").asText();
                }
            }
        }
        return null;
    }

    private void writeExifDateTime(Path path, String dateTime) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-overwrite_original");
        
        if (isVideoFile(path)) {
            command.add("-api");
            command.add("QuickTimeUTC");
            command.add("-AllDates=" + dateTime);
            command.add("-Keys:CreationDate=" + dateTime);
            command.add("-UserData:ContentCreateDate=" + dateTime);
        } else {
            command.add("-DateTimeOriginal=" + dateTime);
            command.add("-CreateDate=" + dateTime);
        }
        command.add(path.toAbsolutePath().toString());
        
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Exiftool failed with exit code: " + exitCode);
        }
    }
}
