package de.photosexportsorter.sortcli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "sortcli", mixinStandardHelpOptions = true, version = "sortcli 0.1",
        description = "Sorts export structur (directories of mac's photos app")
class SortCli implements Callable<Integer> {

    @Option(names = "--source", description = "source folder with mac photos app export")
    private File source;

    @Option(names="--target", description = "target folder for transformed directory structure")
    private File target;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SortCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Set<String> filesInDir = listDirsUsingFilesList(source.getAbsolutePath());
        filesInDir.forEach(dir -> System.out.println(dir));

        return 0;
    }
    
    public Set<String> listDirsUsingFilesList(String dir) throws IOException {
    try (Stream<Path> stream = Files.list(Paths.get(dir))) {
        return stream
          .filter(file -> Files.isDirectory(file))
          .map(Path::toAbsolutePath)
          .map(Path::toString)
          .collect(Collectors.toSet());
    }
}
}
