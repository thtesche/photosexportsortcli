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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
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

@Command(name = "visiontag", description = "Automatically generate and write EXIF keywords using local Ollama vision model based on image content.")
public class VisionTagCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Parameters(arity = "0..*", description = "Directories containing photos to tag")
    private List<File> directories = new ArrayList<>();

    @Option(names = "--model", description = "Ollama model to use (default: gemma4)", defaultValue = "gemma4")
    private String model;

    @Option(names = "--dryRun", description = "If set, only queries Ollama but does NOT write to the files via exiftool")
    private boolean dryRun = false;

    @Option(names = { "-f", "--force" }, description = "Force re-tagging even if the image is already AI-tagged")
    private boolean force = false;

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

            String listName = ProcessingList.getListFilename("visiontag", dir);
            File listFile = new File(listName);
            File doneFile = new File(listName + ".done");

            ProcessingList pList;
            if (listFile.exists()) {
                spec.commandLine().getOut().println(Ansi.AUTO.string(
                        "\n@|yellow Resuming directory:|@ " + dir.getAbsolutePath() + " (using " + listName + ")"));
                pList = ProcessingList.load(listFile);
                if (!pList.rootPath.equals(dir.getAbsolutePath())) {
                    spec.commandLine().getErr()
                            .println(Ansi.AUTO.string("@|red Error: List file root path mismatch. Expected:|@ "
                                    + dir.getAbsolutePath() + " @|red but found:|@ " + pList.rootPath));
                    continue;
                }
            } else if (doneFile.exists()) {
                spec.commandLine().getOut().println(Ansi.AUTO
                        .string("\n@|green Skipping directory (already fully processed):|@ " + dir.getAbsolutePath()));
                continue;
            } else {
                spec.commandLine().getOut()
                        .println(Ansi.AUTO.string("\n@|yellow Scanning directory:|@ " + dir.getAbsolutePath()));
                pList = new ProcessingList();
                pList.rootPath = dir.getAbsolutePath();
                pList.command = "visiontag";
                try (Stream<Path> stream = Files.walk(dir.toPath())) {
                    List<Path> allFiles = stream.filter(Files::isRegularFile)
                            .filter(this::isImageFile)
                            .collect(Collectors.toList());
                    for (Path p : allFiles) {
                        ProcessingList.Entry e = new ProcessingList.Entry();
                        e.path = dir.toPath().relativize(p).toString();
                        pList.entries.add(e);
                    }
                }
                pList.save(listFile);
            }

            int count = 0;
            int total = pList.entries.size();
            for (ProcessingList.Entry entry : pList.entries) {
                count++;
                if ("DONE".equals(entry.status)) {
                    continue;
                }

                Path filePath = dir.toPath().resolve(entry.path);
                if (!Files.exists(filePath)) {
                    spec.commandLine().getOut()
                            .println(Ansi.AUTO.string("\r\033[K@|yellow Skipping (file missing):|@ " + entry.path));
                    entry.status = "DONE";
                    pList.save(listFile);
                    continue;
                }

                String prefix = Ansi.AUTO.string("@|blue [" + count + "/" + total + "]|@ ");
                processFile(filePath, prefix);

                entry.status = "DONE";
                pList.save(listFile);
            }

            if (listFile.exists()) {
                listFile.renameTo(doneFile);
                spec.commandLine().getOut()
                        .println(Ansi.AUTO.string("\n@|bold,green Directory complete: |@" + dir.getAbsolutePath()));
            }
        }

        spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|bold,green Vision tagging complete.|@"));
        return 0;
    }

    boolean isImageFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith("._") || path.toString().contains("@eaDir")) {
            return false;
        }
        name = name.toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
    }

    private void processFile(Path path, String prefix) {
        String fullPath = path.toAbsolutePath().toString();

        spec.commandLine().getOut().print("\r\033[K" + prefix + Ansi.AUTO.string("@|cyan Analyzing:|@ " + fullPath));
        spec.commandLine().getOut().flush();

        try {
            // Read existing metadata
            ExifMetadata metadata = getExifMetadata(path);

            if (!force && metadata.instructions != null && metadata.instructions.contains(PRO_MARKER)) {
                spec.commandLine().getOut().print("\r\033[K" + prefix + Ansi.AUTO.string(
                        "@|yellow \u23ED\uFE0F  Skipping:|@ " + fullPath + " (Already tagged with Pro-Prompt)"));
                spec.commandLine().getOut().flush();
                return;
            }

            if (metadata.instructions != null && metadata.instructions.contains("AI-Tagged")) {
                spec.commandLine().getOut().print("\r\033[K" + prefix
                        + Ansi.AUTO.string("@|cyan \uD83D\uDD04 Updating:|@ " + fullPath + " with better prompt... "));
            } else {
                spec.commandLine().getOut().print("\r\033[K" + prefix
                        + Ansi.AUTO.string("@|cyan \uD83E\uDD16 Analyzing content:|@ " + fullPath + " ... "));
            }
            spec.commandLine().getOut().flush();

            List<String> generatedTags = getTagsFromOllama(path);
            if (generatedTags == null || generatedTags.isEmpty()) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|red [No tags generated]|@"));
                return;
            }

            List<String> tagsToAdd = TagCommand.filterNewTags(metadata.keywords, generatedTags);

            if (tagsToAdd.isEmpty() && metadata.alreadySynced) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|green [Tags already present and synced]|@"));
                // Still update XMP:Instructions to mark it as processed!
                if (!dryRun) {
                    writeInstructionsTag(path);
                }
                return;
            }

            // Aggregate all tags: existing (union) + new from AI
            List<String> finalTags = TagCommand.mergeTags(metadata.keywords, generatedTags);

            spec.commandLine().getOut()
                    .print("\r\033[K" + prefix + Ansi.AUTO.string("@|cyan \uD83E\uDD16 Analyzing content:|@ " + fullPath
                            + " | @|green New Tags:|@ " + tagsToAdd + " ... "));

            if (dryRun) {
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow [Skipped writing]|@"));
            } else {
                writeExifTags(path, finalTags);
                spec.commandLine().getOut().println(Ansi.AUTO.string("@|green [Written]|@"));
            }

        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            spec.commandLine().getOut().println(Ansi.AUTO.string("\n@|red [\u274C ERROR: " + msg + "]|@"));
        }
    }

    private List<String> getTagsFromOllama(Path imagePath) throws IOException, InterruptedException {
        String base64Image = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));

        String prompt = "Analysiere dieses Bild hochpräzise. Erstelle 5-10 deutsche Schlagworte. \n" +
                "PRIORITÄTEN: \n" +
                "1. Ort (Stadt, Land, Sehenswürdigkeit). \n" +
                "2. Fahrzeuge (Marke, Modell UND Baureihe, falls erkennbar). \n" +
                "3. Spielzeug (Spezifische Lego-Themen, Sets oder Stein-Typen). \n" +
                "4. Sport (Sportart, Ausrüstung). \n" +
                "5. Hauptobjekte.\n" +
                "STRIKTE REGELN: \n" +
                "- Nenne NIEMALS Jahreszeiten (Sommer, Winter, Herbst, Frühling).\n" +
                "- Nenne NIEMALS Farben.\n" +
                "- Gib NUR Schlagworte aus, getrennt durch Kommata. \n" +
                "- KEIN Denkprozess, KEIN Einleitungstext.";

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);

        ArrayNode imagesArray = requestBody.putArray("images");
        imagesArray.add(base64Image);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:11434/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300)) // 5 minutes read timeout for vision
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error: " + response.statusCode());
        }

        JsonNode responseNode = mapper.readTree(response.body());
        JsonNode responseField = responseNode.get("response");
        if (responseField == null) {
            throw new RuntimeException("Missing 'response' field in Ollama output.");
        }
        String responseText = responseField.asText();

        return cleanOllamaResponse(responseText);
    }

    List<String> cleanOllamaResponse(String responseText) {
        // Clean up response based on bash script
        // sed -E 's/.*done thinking\.//g' | sed -E 's/.*Thinking\.//g' | sed -E
        // 's/<thought>.*<\/thought>//g'
        String cleanTags = responseText.replaceAll("(?s)<thought>.*?</thought>", "");
        if (cleanTags.contains("done thinking.")) {
            cleanTags = cleanTags.substring(cleanTags.lastIndexOf("done thinking.") + "done thinking.".length());
        }
        if (cleanTags.contains("Thinking.")) {
            cleanTags = cleanTags.substring(cleanTags.lastIndexOf("Thinking.") + "Thinking.".length());
        }

        cleanTags = cleanTags.replace("\n", "").replace("\r", "")
                .replace("\"", "").replace("'", "");
        if (cleanTags.endsWith(".")) {
            cleanTags = cleanTags.substring(0, cleanTags.length() - 1);
        }

        List<String> tags = new ArrayList<>();
        if (!cleanTags.trim().isEmpty() && !cleanTags.trim().equals("null")) {
            String[] splitTags = cleanTags.split(",");
            for (String tag : splitTags) {
                if (!tag.trim().isEmpty()) {
                    tags.add(tag.trim());
                }
            }
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
            // ignore JSON parse errors
        }
        return metadata;
    }

    private void writeInstructionsTag(Path imagePath) throws IOException, InterruptedException {
        String timestamp = LocalDate.now().toString();
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
        String timestamp = LocalDate.now().toString();
        String instructions = "AI-Tagged: " + timestamp + " via " + PRO_MARKER;

        List<String> command = new ArrayList<>();
        command.add("exiftool");
        command.add("-m");
        command.add("-overwrite_original");

        for (String tag : tags) {
            command.add("-keywords=" + tag);
        }
        for (String tag : tags) {
            command.add("-Subject=" + tag);
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
