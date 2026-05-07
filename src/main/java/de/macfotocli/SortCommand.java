package de.macfotocli;

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
import picocli.CommandLine.Help.Ansi;

@Command(name = "sort", description = "Reorganizes Apple Photos exports into a clean, chronological structure.")
public class SortCommand implements Callable<Integer> {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Option(names = "--sourceRoot", required = true, description = "source folder with mac photos app export")
    private File source;

    @Option(names = "--targetRoot", required = true, description = "target folder for transformed directory structure")
    File target;

    @Option(names = "--locale", required = true, description = "Locale to transform the date e.g. en, de ...")
    private String locale;

    @Option(names = "--deleteSource", description = "delete the copied source folder. default is false")
    private boolean deleteSource = false;

    @Override
    public Integer call() throws Exception {
        if (!source.exists() || !source.isDirectory()) {
            spec.commandLine().getErr().println(Ansi.AUTO.string("@|red Error: Source root must be a valid directory.|@"));
            return 1;
        }

        spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow Sorting photos from:|@ " + source.getAbsolutePath()));
        spec.commandLine().getOut().println(Ansi.AUTO.string("@|yellow Target directory:|@   " + target.getAbsolutePath()));
        spec.commandLine().getOut().println();

        Set<String> dirsInDir = listDirsUsingFilesList(source.getAbsolutePath());
        dirsInDir.forEach(dir -> {
            try {
                PathInfo pathInfo = createTargetDirs(re_sort_location_date(dir, locale));
                spec.commandLine().getOut()
                        .println(Ansi.AUTO.string("@|green Processing:|@ " + Path.of(pathInfo.sourceDir()).getFileName()));

                FileUtils.copyDirectory(new File(pathInfo.sourceDir()), new File(pathInfo.targetDir()));
                if (deleteSource) {
                    FileUtils.deleteQuietly(new File(pathInfo.sourceDir()));
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });

        spec.commandLine().getOut().println();
        spec.commandLine().getOut().println(Ansi.AUTO.string("@|bold,green Success! Photos have been reorganized.|@"));
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
        String[] fileNameParts = dirParts[dirParts.length - 1].split(",", 2);
        String reSortedDirName = "";
        String datePart;

        if (fileNameParts.length == 2) {
            reSortedDirName = ", " + fileNameParts[0].trim();
            datePart = fileNameParts[1].trim();
        } else {
            datePart = fileNameParts[0];
        }

        String newDatePart = datePart.replace("ä", "ä");

        LocalDate inDate = LocalDate.parse(newDatePart,
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(Locale.forLanguageTag(locale)));

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
