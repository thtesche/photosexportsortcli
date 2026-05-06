package de.photosexportsorter.macfotocli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "doctor", description = "Checks if required external dependencies (Ollama, exiftool) are available.")
public class DoctorCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().getOut().println("@|bold,cyan Running diagnostic checks...|@\n");
        boolean allGood = true;

        // Check Exiftool
        spec.commandLine().getOut().print("Checking for exiftool... ");
        try {
            Process process = new ProcessBuilder("exiftool", "-ver").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                String version = new String(process.getInputStream().readAllBytes()).trim();
                spec.commandLine().getOut().println("@|green [OK]|@ (Version: " + version + ")");
            } else {
                spec.commandLine().getOut().println("@|red [FAILED]|@ (Exit code: " + exitCode + ")");
                allGood = false;
            }
        } catch (IOException | InterruptedException e) {
            spec.commandLine().getOut().println("@|red [NOT FOUND]|@ (" + e.getMessage() + ")");
            allGood = false;
        }

        // Check Ollama
        spec.commandLine().getOut().print("Checking for Ollama (localhost:11434)... ");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:11434/"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains("Ollama is running")) {
                spec.commandLine().getOut().println("@|green [OK]|@");
            } else {
                spec.commandLine().getOut().println("@|yellow [WARNING]|@ (Unexpected response: " + response.body() + ")");
                allGood = false;
            }
        } catch (IOException | InterruptedException e) {
            spec.commandLine().getOut().println("@|red [NOT RUNNING]|@ (Could not connect to localhost:11434)");
            allGood = false;
        }

        spec.commandLine().getOut().println();
        if (allGood) {
            spec.commandLine().getOut().println("@|bold,green All systems go! Ready to tag some photos.|@");
            return 0;
        } else {
            spec.commandLine().getOut().println("@|bold,red Doctor found some issues. Please install/start the missing tools.|@");
            return 1;
        }
    }
}
