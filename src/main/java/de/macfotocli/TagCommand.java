package de.macfotocli;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Help.Ansi;

@Command(name = "tag", description = "Automatically generate and write EXIF keywords using local Ollama model based on filepath.")
public class TagCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(arity = "0..*", description = "Directories containing photos to tag (supports wildcards like /200*)")
    private List<File> directories = new ArrayList<>();

    @Option(names = "--model", description = "Ollama model to use (default: gemma4)", defaultValue = "gemma4")
    private String model;

    @Option(names = "--dryRun", description = "If set, only queries Ollama but does NOT write to the files via exiftool")
    private boolean dryRun = false;

    @Option(names = { "-f", "--force" }, description = "Force re-tagging even if the image is already AI-tagged")
    private boolean force = false;

    @Option(names = "--ignore", description = "Comma-separated list of negative words to ignore (e.g. 'Backup,Urlaub')")
    private String ignoreWords;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String PRO_MARKER = "Gemma4-Pro-Prompt";

    @Override
    public Integer call() throws Exception {
        List<File> dirsToProcess = new ArrayList<>();
        if (directories != null) {
            dirsToProcess.addAll(directories);
        }

        if (dirsToProcess.isEmpty()) {
            spec.commandLine().getErr().println(Ansi.AUTO
                    .string("@|red Error: No directories specified. You must provide at least one directory.|@"));
            return 1;
        }

        if (dryRun) {
            spec.commandLine().getOut()
                    .println(Ansi.AUTO.string("@|bold,yellow [DRY RUN ENABLED - No files will be modified]|@\n"));
        }

        for (File dir : dirsToProcess) {
            if (!dir.exists() || !dir.isDirectory()) {
                spec.commandLine().getErr().println(
                        Ansi.AUTO.string("@|yellow Warning: Skipping invalid directory:|@ " + dir.getAbsolutePath()));
                continue;
            }

            spec.commandLine().getOut()
                    .println(Ansi.AUTO.string("\n@|yellow Scanning directory:|@ " + dir.getAbsolutePath()));

            try (Stream<Path> stream = Files.walk(dir.toPath())) {
                stream.filter(Files::isRegularFile)
                        .filter(this::isImageFile)
                        .forEach(path -> processFile(path, dir));
            }
        }

        spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|bold,green Tagging complete.|@"));
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

    private void processFile(Path path, File rootDirectory) {
        String fullPath = path.toAbsolutePath().toString();
        String relativePath = rootDirectory.toPath().relativize(path).toString();

        spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string("@|cyan Analyzing:|@ " + fullPath));
        spec.commandLine().getOut().flush();

        // Optimization: check if there are any descriptive words in the path (excluding
        // extension)
        if (!hasWords(relativePath)) {
            spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO
                    .string("@|yellow \u23ED\uFE0F  Skipping:|@ " + fullPath + " (No descriptive words in path)"));
            spec.commandLine().getOut().flush();
            return;
        }

        try {
            ExifMetadata metadata = getExifMetadata(path);

            // Skip if already tagged with Pro-Prompt and not forced
            if (!force && metadata.instructions != null && metadata.instructions.contains("Gemma4-Pro-Prompt")) {
                spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string(
                        "@|yellow \u23ED\uFE0F  Skipping:|@ " + fullPath + " (Already tagged with Pro-Prompt)"));
                spec.commandLine().getOut().flush();
                return;
            }

            List<String> generatedTags = getTagsFromOllama(relativePath);
            if (generatedTags == null || generatedTags.isEmpty()) {
                // No tags -> do not print, next file will overwrite
                return;
            }

            List<String> tagsToAdd = filterNewTags(metadata.keywords, generatedTags);

            if (!force && tagsToAdd.isEmpty() && metadata.alreadySynced) {
                // All tags already exist and are synced -> do not print, next file will
                // overwrite
                if (!dryRun) {
                    writeInstructionsTag(path);
                }
                return;
            }

            // Aggregate all tags: existing (union) + new from AI
            List<String> finalTags = mergeTags(metadata.keywords, generatedTags);

            spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO
                    .string("@|cyan Analyzing:|@ " + fullPath + " | @|green Tags:|@ " + tagsToAdd + " ... "));

            if (dryRun) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow [Skipped writing]|@"));
            } else {
                writeExifTags(path, finalTags);
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|green [Written]|@"));
            }

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|red [ERROR: " + msg + "]|@"));
        }
    }

    private List<String> getTagsFromOllama(String filePath) throws IOException, InterruptedException {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Extract meaningful descriptive keywords from this filepath for image tagging. ")
                .append("Look at BOTH folder names and the file name. ")
                .append("Extract ONLY valid semantic words (e.g. locations, objects, events, context). ")
                .append("CRITICAL RULES:\n")
                .append("- Split CamelCase words into separate words (e.g., 'SummerVacation' -> 'Summer', 'Vacation').\n")
                .append("- DO NOT include years, dates, or times (e.g. 2019, 2019-09-15, October 12).\n")
                .append("- DO NOT include numbers.\n")
                .append("- DO NOT include generic terms like 'BURST', 'COVER', 'TOP', 'IMG', 'HDR', 'export', 'source', 'Backup', 'Volumes', 'file', 'photo', 'random', 'id'.\n")
                .append("- DO NOT include file extensions (like jpg, jpeg, png).\n")
                .append("- Ignore any UUIDs, hashes, or random strings in the path, but STILL extract valid words from the rest of the path (like the folder name).\n")
                .append("- If no valid words are found in the entire path, return []. DO NOT hallucinate words.\n");

        if (ignoreWords != null && !ignoreWords.trim().isEmpty()) {
            promptBuilder.append("- DO NOT include any of the following specific words: ")
                    .append(ignoreWords).append(".\n");
        }

        promptBuilder.append("Return strictly a JSON array of strings, nothing else. Filepath: ").append(filePath);
        String prompt = promptBuilder.toString();

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("format", "json");
        requestBody.put("stream", false);
        requestBody.put("temperature", 0.0);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120)) // 2 minutes read timeout
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode());
        }

        JsonNode responseNode = mapper.readTree(response.body());
        JsonNode responseField = responseNode.get("response");
        if (responseField == null) {
            throw new RuntimeException("Missing 'response' field in Ollama output. Body: " + response.body());
        }
        String responseText = responseField.asText();

        JsonNode tagsNode = mapper.readTree(responseText);
        List<String> tags = new ArrayList<>();
        if (tagsNode.isArray()) {
            for (JsonNode node : tagsNode) {
                if (!node.asText().trim().isEmpty()) {
                    tags.add(node.asText().trim());
                }
            }
        } else if (tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isArray() && tags.isEmpty()) {
                    for (JsonNode node : entry.getValue()) {
                        if (!node.asText().trim().isEmpty()) {
                            tags.add(node.asText().trim());
                        }
                    }
                }
            });
        }
        return tags;
    }

    private static class ExifMetadata {
        String instructions;
        List<String> keywords = new ArrayList<>();
        boolean alreadySynced = true;
    }

    private ExifMetadata getExifMetadata(Path imagePath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("exiftool", "-XMP:Instructions", "-keywords", "-Subject", "-j",
                imagePath.toAbsolutePath().toString()).start();
        String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        ExifMetadata metadata = new ExifMetadata();
        if (json == null || json.trim().isEmpty()) {
            return metadata;
        }

        try {
            JsonNode root = mapper.readTree(json);
            if (root.isArray() && root.size() > 0) {
                JsonNode fileNode = root.get(0);

                JsonNode instructionsNode = fileNode.get("Instructions");
                if (instructionsNode != null) {
                    metadata.instructions = instructionsNode.asText();
                }

                Set<String> kwSet = new HashSet<>();
                JsonNode keywordsNode = fileNode.get("Keywords");
                if (keywordsNode != null) {
                    if (keywordsNode.isArray()) {
                        keywordsNode.forEach(n -> kwSet.add(n.asText()));
                    } else {
                        kwSet.add(keywordsNode.asText());
                    }
                }

                Set<String> subSet = new HashSet<>();
                JsonNode subjectNode = fileNode.get("Subject");
                if (subjectNode != null) {
                    if (subjectNode.isArray()) {
                        subjectNode.forEach(n -> subSet.add(n.asText()));
                    } else {
                        subSet.add(subjectNode.asText());
                    }
                }

                metadata.alreadySynced = kwSet.equals(subSet);
                metadata.keywords.addAll(kwSet);
                for (String s : subSet) {
                    if (!kwSet.contains(s)) {
                        metadata.keywords.add(s);
                    }
                }
            }
        } catch (Exception e) {
            // ignore JSON parse errors and return empty list
        }
        return metadata;
    }

    public static List<String> filterNewTags(List<String> existingTags, List<String> generatedTags) {
        Set<String> existingLower = existingTags.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<String> tagsToAdd = new ArrayList<>();
        Set<String> addedLower = new HashSet<>();

        for (String tag : generatedTags) {
            if (tag == null || tag.trim().isEmpty()) {
                continue;
            }
            String lower = tag.trim().toLowerCase();
            if (!existingLower.contains(lower) && !addedLower.contains(lower)) {
                tagsToAdd.add(tag.trim());
                addedLower.add(lower);
            }
        }
        return tagsToAdd;
    }

    private static final Set<String> IGNORED_WORDS = Set.of(
            "IMG", "DSC", "PANO", "VID", "SCAN", "WP", "P", "SCREENSHOT", "SCREEN", "RECORDING", "HDR", "BURST",
            "COVER", "TOP");

    public static boolean hasWords(String relativePath) {
        String pathWithoutExtension = relativePath;
        int lastDot = relativePath.lastIndexOf('.');
        if (lastDot > 0) {
            pathWithoutExtension = relativePath.substring(0, lastDot);
        }

        // Split by non-letter characters (including numbers, underscores, etc.)
        // Supports German umlauts and ß
        String[] parts = pathWithoutExtension.split("[^a-zA-ZäöüÄÖÜß]+");

        for (String part : parts) {
            if (part.length() < 3)
                continue; // Skip single/double letters like 'P' or 'WP'
            if (IGNORED_WORDS.contains(part.toUpperCase()))
                continue;

            // If we found a word that is at least 3 chars long and not in the blacklist
            return true;
        }
        return false;
    }

    public static List<String> mergeTags(List<String> existingTags, List<String> generatedTags) {
        Set<String> allTagsSet = new LinkedHashSet<>(existingTags);
        allTagsSet.addAll(generatedTags.stream()
                .filter(t -> t != null && !t.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toList()));
        return new ArrayList<>(allTagsSet);
    }

    private void writeInstructionsTag(Path imagePath) throws IOException, InterruptedException {
        String timestamp = java.time.LocalDate.now().toString();
        String instructions = "AI-Tagged: " + timestamp + " via " + PRO_MARKER;

        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-m");
        command.add("-overwrite_original");
        command.add("-XMP:Instructions=" + instructions);
        command.add(imagePath.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("exiftool failed to write instructions with exit code " + exitCode);
        }
    }

    private void writeExifTags(Path imagePath, List<String> tags) throws IOException, InterruptedException {
        String timestamp = java.time.LocalDate.now().toString();
        String instructions = "AI-Tagged: " + timestamp + " via " + PRO_MARKER;

        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-m");
        command.add("-overwrite_original");

        boolean isVideo = isVideoFile(imagePath);

        for (String tag : tags) {
            command.add("-keywords=" + tag);
        }
        for (String tag : tags) {
            command.add("-Subject=" + tag);
        }
        if (isVideo) {
            for (String tag : tags) {
                command.add("-Keys:Keywords=" + tag);
            }
            for (String tag : tags) {
                command.add("-ItemList:Keyword=" + tag);
            }
        }

        command.add("-XMP:Instructions=" + instructions);
        command.add(imagePath.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("exiftool failed with exit code " + exitCode);
        }
    }
}
