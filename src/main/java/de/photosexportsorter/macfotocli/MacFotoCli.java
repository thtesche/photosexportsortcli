package de.photosexportsorter.macfotocli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "macfotocli", mixinStandardHelpOptions = true, version = "macfotocli 1.2",
        description = "A powerful CLI to reorganize Apple Photos exports and auto-tag images.",
        subcommands = {
                SortCommand.class,
                TagCommand.class,
                DoctorCommand.class
        })
public class MacFotoCli implements Runnable {

    public static void main(String[] args) {
        String[] banner = {
                "@|bold,cyan  __  __            _____    _        ____ _     ___ |@",
                "@|bold,cyan |  \\/  | __ _  ___|  ___|__| |_ ___ / ___| |   |_ _||@",
                "@|bold,cyan | |\\/| |/ _` |/ __| |_ / _ \\ __/ _ \\ |   | |    | | |@",
                "@|bold,cyan | |  | | (_| | (__|  _| (_) | || (_) | |___| |___ | | |@",
                "@|bold,cyan |_|  |_|\\__,_|\\___|_|  \\___/ \\__\\___/ \\____|_____|___||@",
                ""
        };
        for (String line : banner) {
            System.out.println(CommandLine.Help.Ansi.AUTO.string(line));
        }

        int exitCode = new CommandLine(new MacFotoCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // Print usage help if no subcommand is provided
        CommandLine.usage(this, System.out);
    }
}
