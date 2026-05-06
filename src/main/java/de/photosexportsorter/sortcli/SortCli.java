package de.photosexportsorter.sortcli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "sortcli", mixinStandardHelpOptions = true, version = "sortcli 1.0", description = "Reorganizes Apple Photos exports into a clean, chronological structure.", header = {
        "@|bold,cyan  ____  _           _            ____             _      ____ _     ___ |@",
        "@|bold,cyan |  _ \\| |__   ___ | |_ ___     / ___|  ___  _ __| |_   / ___| |   |_ _||@",
        "@|bold,cyan | |_) | '_ \\ / _ \\| __/ _ \\    \\___ \\ / _ \\| '__| __| | |   | |    | | |@",
        "@|bold,cyan |  __/| | | | (_) | || (_) |    ___) | (_) | |  | |_  | |___| |___ | | |@",
        "@|bold,cyan |_|   |_| |_|\\___/ \\__\\___/    |____/ \\___/|_|   \\__|  \\____|_____|___||@",
        ""
})
class SortCli implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = "--sourceRoot", description = "source folder with mac photos app export")
    private File source;

    @Option(names = "--targetRoot", description = "target folder for transformed directory structure")
    File target;

    @Option(names = "--locale", description = "Locale to transform the date e.g. en, de ...")
    private String locale;

    @Option(names = "--deleteSource", description = "delete the copied source folder. default is false")
    private boolean deleteSource = false;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SortCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (source == null || !source.exists() || !source.isDirectory()) {
            spec.commandLine().getErr().println("@|red Error: Source root must be a valid directory.|@");
            return 1;
        }

        if (target == null) {
            spec.commandLine().getErr().println("@|red Error: Target root must be specified.|@");
            return 1;
        }

        spec.commandLine().getOut().println("@|yellow Sorting photos from:|@ " + source.getAbsolutePath());
        spec.commandLine().getOut().println("@|yellow Target directory:|@   " + target.getAbsolutePath());
        spec.commandLine().getOut().println();

        Set<String> dirsInDir = listDirsUsingFilesList(source.getAbsolutePath());
        dirsInDir.forEach(dir -> {
            try {
                PathInfo pathInfo = createTargetDirs(re_sort_location_date(dir, locale));
                spec.commandLine().getOut()
                        .println("@|green Processing:|@ " + Path.of(pathInfo.sourceDir()).getFileName());

                FileUtils.copyDirectory(new File(pathInfo.sourceDir()), new File(pathInfo.targetDir()));
                if (deleteSource) {
                    FileUtils.deleteQuietly(new File(pathInfo.sourceDir()));
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        spec.commandLine().getOut().println();
        spec.commandLine().getOut().println("@|bold,green Success! Photos have been reorganized.|@");
        return 0;
    }

    private PathInfo createTargetDirs(PathInfo pathInfo) throws IOException {
        Path path = Path.of(pathInfo.targetDir());
        Files.createDirectories(path);
        return pathInfo;
    }

    private Set<String> listDirsUsingFilesList(String dir) throws IOException {
        try (Stream<Path> stream = Files.list(Path.of(dir))) {
            return stream
                    .filter(Files::isDirectory)
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }
    }

    PathInfo re_sort_location_date(String dir, String locale) {

        String[] dirParts = dir.split("/");

        String fileNameParts[] = dirParts[dirParts.length - 1].split(",", 2);
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
        // In Germany there is only the March (März) which is affected. More
        // replacements needs
        // to be added for other locales.
        // As the output date is an ISO date there are no longer non iso chars existent
        // at this stage.
        String newDatePart = datePart.replace("ä", "ä");

        LocalDate inDate = LocalDate.parse(newDatePart,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(new Locale(locale)));

        String outDateString = DateTimeFormatter.ISO_DATE.format(inDate);

        return new PathInfo(dir,
                Path.of(target.getAbsolutePath(), Integer.toString(inDate.getYear()), outDateString + reSortedDirName)
                        .toString());
    }

    record PathInfo(String sourceDir, String targetDir) {
        @Override
        public String toString() {
            return "sourceDir= " + sourceDir + ", targetDir=" + targetDir;
        }
    }
}
