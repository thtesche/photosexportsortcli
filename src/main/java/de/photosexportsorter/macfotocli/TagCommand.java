package de.photosexportsorter.macfotocli;

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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

    @Option(names = "--ignore", description = "Comma-separated list of negative words to ignore (e.g. 'Backup,Urlaub')")
    private String ignoreWords;

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        List<File> dirsToProcess = new ArrayList<>();
        if (directories != null) {
            dirsToProcess.addAll(directories);
        }

        if (dirsToProcess.isEmpty()) {
            spec.commandLine().getErr().println(Ansi.AUTO.string("@|red Error: No directories specified. You must provide at least one directory.|@"));
            return 1;
        }

        if (dryRun) {
            spec.commandLine().getOut().println(Ansi.AUTO.string("@|bold,yellow [DRY RUN ENABLED - No files will be modified]|@\n"));
        }

        for (File dir : dirsToProcess) {
            if (!dir.exists() || !dir.isDirectory()) {
                spec.commandLine().getErr().println(Ansi.AUTO.string("@|yellow Warning: Skipping invalid directory:|@ " + dir.getAbsolutePath()));
                continue;
            }

            spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|yellow Scanning directory:|@ " + dir.getAbsolutePath()));

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

        try {
            List<String> generatedTags = getTagsFromOllama(relativePath);
            if (generatedTags == null || generatedTags.isEmpty()) {
                // No tags -> do not print, next file will overwrite
                return;
            }

            List<String> existingTags = getExistingKeywords(path);
            List<String> tagsToAdd = filterNewTags(existingTags, generatedTags);

            if (tagsToAdd.isEmpty()) {
                // All tags already exist -> do not print, next file will overwrite
                return;
            }

            spec.commandLine().getOut().print("\r\033[K" + Ansi.AUTO.string("@|cyan Analyzing:|@ " + fullPath + " | @|green Tags:|@ " + tagsToAdd + " ... "));

            if (dryRun) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow [Skipped writing]|@"));
            } else {
                writeExifTags(path, tagsToAdd);
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
                .append("- DO NOT include generic terms like 'IMG', 'HDR'export', 'source', 'Backup', 'Volumes', 'file', 'photo', 'random', 'id'.\n")
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

    private List<String> getExistingKeywords(Path imagePath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("exiftool", "-keywords", "-j", imagePath.toAbsolutePath().toString()).start();
        String json = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();

        List<String> existing = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return existing;
        }

        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            if (root.isArray() && root.size() > 0) {
                com.fasterxml.jackson.databind.JsonNode fileNode = root.get(0);
                com.fasterxml.jackson.databind.JsonNode keywordsNode = fileNode.get("Keywords");
                if (keywordsNode != null) {
                    if (keywordsNode.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode n : keywordsNode) {
                            existing.add(n.asText());
                        }
                    } else {
                        existing.add(keywordsNode.asText());
                    }
                }
            }
        } catch (Exception e) {
            // ignore JSON parse errors and return empty list
        }
        return existing;
    }

    public static List<String> filterNewTags(List<String> existingTags, List<String> generatedTags) {
        java.util.Set<String> existingLower = existingTags.stream()
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.toSet());

        List<String> tagsToAdd = new ArrayList<>();
        java.util.Set<String> addedLower = new java.util.HashSet<>();

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

    private void writeExifTags(Path imagePath, List<String> tags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-overwrite_original");

        boolean isVideo = isVideoFile(imagePath);

        for (String tag : tags) {
            command.add("-keywords+=" + tag);
            if (isVideo) {
                command.add("-Keys:Keywords+=" + tag);
                command.add("-ItemList:Keyword+=" + tag);
            }
        }
        command.add(imagePath.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("exiftool failed with exit code " + exitCode);
        }
    }
}
