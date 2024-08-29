package de.photosexportsorter.sortcli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.io.FileUtils;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "sortcli", mixinStandardHelpOptions = true, version = "sortcli 0.1",
        description = "Sorts export structur (directories of mac's photos app")
class SortCli implements Callable<Integer> {

    @Option(names = "--source", description = "source folder with mac photos app export")
    private File source;

    @Option(names = "--target", description = "target folder for transformed directory structure")
    private File target;

    @Option(names = "--locale", description = "Locale to transform the date e.g. en, de ...")
    private String locale;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SortCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        Set<String> dirsInDir = listDirsUsingFilesList(source.getAbsolutePath());
        dirsInDir.forEach(dir -> {
            try {
                PathInfo pathInfo = createTargetDirs(re_sort_location_date(dir, locale));
                FileUtils.copyDirectory(new File(pathInfo.sourceDir), new File(pathInfo.targetDir));
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        return 0;
    }

    private PathInfo createTargetDirs(PathInfo pathInfo) throws IOException {
        Path path = Paths.get(pathInfo.getTargetDir());
        Path newTargetDir = Files.createDirectories(path);
        System.out.println(newTargetDir.toString());
        return pathInfo;
    }

    private Set<String> listDirsUsingFilesList(String dir) throws IOException {
        try (Stream<Path> stream = Files.list(Paths.get(dir))) {
            return stream
                    .filter(file -> Files.isDirectory(file))
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }

    }

    private PathInfo re_sort_location_date(String dir, String locale) {

        String[] dirParts = dir.split("/");

        String fileNameParts[] = dirParts[dirParts.length - 1].split(",");
        String reSortedDirName = "";

        String datePart;

        if (fileNameParts.length == 2) {
            reSortedDirName = ", " + fileNameParts[0].trim();
            datePart = fileNameParts[1].trim();
        } else {
            datePart = fileNameParts[0];
        }

        // Replacement for macs canonical decomposition
        // https://developer.apple.com/library/archive/technotes/tn/tn1150.html#UnicodeSubtleties
        // In Germany there is only the March (März) which is affected. More replacements needs
        // to be added for other locales.
        // As the output date is an ISO date there are no longer non iso chars existent at this stage. 
        String newDatePart = datePart.replace("ä", "ä");

        LocalDate inDate = LocalDate.parse(newDatePart, DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(new Locale(locale)));

        String outDateString = DateTimeFormatter.ISO_DATE.format(inDate);

        return new PathInfo(dir, Paths.get(target.getAbsolutePath(), Integer.toString(inDate.getYear()), outDateString + reSortedDirName).toString());

    }

    @Getter
    @AllArgsConstructor
    private class PathInfo {

        String sourceDir;
        String targetDir;

        @Override
        public String toString() {
            return "sourceDir= " + sourceDir + ", targetDir=" + targetDir;
        }

    }
}
