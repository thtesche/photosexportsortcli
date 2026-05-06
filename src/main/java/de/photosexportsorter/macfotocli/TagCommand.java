package de.photosexportsorter.macfotocli;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@Command(name = "tag", description = "Automatically generate and write EXIF keywords using local Ollama model based on filepath.")
public class TagCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = "--directory", required = true, description = "Directory containing photos to tag")
    private File directory;

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
        if (!directory.exists() || !directory.isDirectory()) {
            spec.commandLine().getErr().println("@|red Error: Target must be a valid directory.|@");
            return 1;
        }

        spec.commandLine().getOut().println("@|yellow Scanning directory:|@ " + directory.getAbsolutePath());
        if (dryRun) {
            spec.commandLine().getOut().println("@|bold,yellow [DRY RUN ENABLED - No files will be modified]|@\n");
        }

        try (Stream<Path> stream = Files.walk(directory.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isImageFile)
                    .forEach(this::processFile);
        }

        spec.commandLine().getOut().println("@|bold,green Tagging complete.|@");
        return 0;
    }

    private boolean isImageFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("._")) {
            return false;
        }
        name = name.toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".heic");
    }

    private void processFile(Path path) {
        String relativePath = directory.toPath().relativize(path).toString();
        spec.commandLine().getOut().print("@|cyan Analyzing:|@ " + relativePath + " ... ");
        try {
            List<String> tags = getTagsFromOllama(relativePath);
            if (tags == null || tags.isEmpty()) {
                spec.commandLine().getOut().println("@|yellow [No tags extracted]|@");
                return;
            }

            spec.commandLine().getOut().print("@|green Tags:|@ " + tags + " ... ");

            if (dryRun) {
                spec.commandLine().getOut().println("@|yellow [Skipped writing]|@");
            } else {
                writeExifTags(path, tags);
                spec.commandLine().getOut().println("@|green [Written]|@");
            }

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            spec.commandLine().getOut().println("@|red [ERROR: " + msg + "]|@");
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

    private void writeExifTags(Path imagePath, List<String> tags) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-overwrite_original");

        for (String tag : tags) {
            command.add("-keywords+=" + tag);
        }
        command.add(imagePath.toAbsolutePath().toString());

        Process process = new ProcessBuilder(command).start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("exiftool failed with exit code " + exitCode);
        }
    }
}
