package com.tcleaner;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Main class - CLI entry point.
 * 
 * Usage:
 *   java -jar tcleaner.jar [OPTIONS] [INPUT_DIR]
 * 
 * Options:
 *   -i, --input <path>        Input directory (default: current directory)
 *   -o, --output <path>      Output file (default: tcleaner_output.txt)
 *   -s, --start-date <date>  Start date filter (YYYY-MM-DD)
 *   -e, --end-date <date>    End date filter (YYYY-MM-DD)
 *   -k, --keyword <word>     Include only messages with keyword
 *   -x, --exclude <word>     Exclude messages with keyword
 *   -v, --verbose            Verbose output
 *   --help                   Show help
 */
public class Main {

    @Parameter(names = {"-i", "--input"}, description = "Input directory with result.json")
    private String inputPath = ".";

    @Parameter(names = {"-o", "--output"}, description = "Output file path")
    private String outputPath = "tcleaner_output.txt";

    @Parameter(names = {"-s", "--start-date"}, description = "Start date filter (YYYY-MM-DD)")
    private String startDate;

    @Parameter(names = {"-e", "--end-date"}, description = "End date filter (YYYY-MM-DD)")
    private String endDate;

    @Parameter(names = {"-k", "--keyword"}, description = "Include only messages with keyword", listConverter = StringListConverter.class)
    private List<String> keywords = new ArrayList<>();

    @Parameter(names = {"-x", "--exclude"}, description = "Exclude messages with keyword", listConverter = StringListConverter.class)
    private List<String> excludeKeywords = new ArrayList<>();

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"--help"}, help = true)
    private boolean help = false;

    public static void main(String[] args) {
        Main main = new Main();
        JCommander jCommander = JCommander.newBuilder()
                .addObject(main)
                .build();

        jCommander.setProgramName("tcleaner");

        try {
            jCommander.parse(args);
        } catch (ParameterException e) {
            System.err.println("Error: " + e.getMessage());
            jCommander.usage();
            System.exit(1);
        }

        if (main.help) {
            jCommander.usage();
            return;
        }

        main.run();
    }

    private void run() {
        try {
            Path inputDir = Paths.get(inputPath);
            Path resultFile = inputDir.resolve("result.json");

            if (!Files.exists(resultFile)) {
                System.err.println("Error: result.json not found in " + inputDir.toAbsolutePath());
                System.exit(1);
            }

            if (verbose) {
                System.out.println("Processing: " + resultFile.toAbsolutePath());
            }

            TelegramExporter exporter = new TelegramExporter();
            MessageFilter filter = hasFilters() ? buildFilter() : null;
            List<String> processed = exporter.processFile(resultFile, filter);

            Path outputFile = Paths.get(outputPath);
            StringBuilder sb = new StringBuilder();
            for (String line : processed) {
                sb.append(line).append("\n");
            }

            Files.writeString(outputFile, sb.toString());

            if (verbose) {
                System.out.println("Output written to: " + outputFile.toAbsolutePath());
                System.out.println("Total messages: " + processed.size());
            } else {
                System.out.println("Done! Processed " + processed.size() + " messages.");
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    private boolean hasFilters() {
        return startDate != null
               || endDate != null
               || !keywords.isEmpty()
               || !excludeKeywords.isEmpty();
    }

    private MessageFilter buildFilter() {
        MessageFilter filter = new MessageFilter();
        
        if (startDate != null) {
            filter.withStartDate(LocalDate.parse(startDate));
        }
        
        if (endDate != null) {
            filter.withEndDate(LocalDate.parse(endDate));
        }
        
        for (String keyword : keywords) {
            filter.withKeyword(keyword);
        }
        
        for (String keyword : excludeKeywords) {
            filter.withExcludeKeyword(keyword);
        }
        
        return filter;
    }
}
