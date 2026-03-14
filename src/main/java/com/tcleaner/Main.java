package com.tcleaner;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Точка входа для CLI-режима приложения.
 *
 * <p>Запускается напрямую или через {@link TelegramCleanerApplication} при передаче
 * флага {@code --cli}:</p>
 * <pre>{@code
 * java -jar tcleaner.jar --cli -i /path/to/export -o output.txt
 * }</pre>
 *
 * <h2>Опции командной строки</h2>
 * <pre>
 *   -i, --input   &lt;path&gt;   Папка с result.json (по умолчанию: текущая директория)
 *   -o, --output  &lt;path&gt;   Выходной файл (по умолчанию: tcleaner_output.txt)
 *   -s, --start-date &lt;date&gt; Начальная дата фильтра (YYYY-MM-DD)
 *   -e, --end-date   &lt;date&gt; Конечная дата фильтра (YYYY-MM-DD)
 *   -k, --keyword    &lt;word&gt; Включать только сообщения с ключевым словом
 *   -x, --exclude    &lt;word&gt; Исключать сообщения с ключевым словом
 *   -v, --verbose           Подробный вывод
 *       --help              Справка
 * </pre>
 *
 * <p>Запись в выходной файл делегируется в {@link TelegramExporter#processFileToFile},
 * который использует {@code Files.write(path, lines)} — без промежуточного
 * {@code String.join} и связанного с ним двойного пика потребления памяти.</p>
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

    @Parameter(names = {"-k", "--keyword"}, description = "Include only messages with keyword",
            listConverter = StringListConverter.class)
    private List<String> keywords = new ArrayList<>();

    @Parameter(names = {"-x", "--exclude"}, description = "Exclude messages with keyword",
            listConverter = StringListConverter.class)
    private List<String> excludeKeywords = new ArrayList<>();

    @Parameter(names = {"-v", "--verbose"}, description = "Verbose output")
    private boolean verbose = false;

    @Parameter(names = {"--help"}, help = true)
    private boolean help = false;

    /**
     * Точка входа CLI.
     *
     * <p>Парсит аргументы через JCommander и вызывает {@link #run()}.
     * При ошибке парсинга выводит сообщение, справку и завершается с кодом 1.</p>
     *
     * @param args аргументы командной строки
     */
    public static void main(String[] args) {
        Main main = new Main();
        JCommander commander = JCommander.newBuilder()
                .addObject(main)
                .build();

        commander.setProgramName("tcleaner");

        try {
            commander.parse(args);
        } catch (ParameterException e) {
            System.err.println("Error: " + e.getMessage());
            commander.usage();
            System.exit(1);
        }

        if (main.help) {
            commander.usage();
            return;
        }

        main.run();
    }

    /**
     * Выполняет обработку: читает {@code result.json}, применяет фильтры и
     * записывает результат в выходной файл.
     *
     * <p>Запись делегируется в {@link TelegramExporter#processFileToFile}, который
     * использует {@code Files.write(path, lines)} — без промежуточного
     * {@code String.join} и удвоения памяти.</p>
     *
     * <p>При ошибке выводит сообщение в {@code stderr} и завершается с кодом 1.</p>
     */
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
            MessageFilter filter = MessageFilterFactory.build(
                    startDate, endDate,
                    keywords.isEmpty() ? null : String.join(",", keywords),
                    excludeKeywords.isEmpty() ? null : String.join(",", excludeKeywords));

            Path outputFile = Paths.get(outputPath);
            // Делегируем запись в processFileToFile: использует Files.write(path, lines)
            // — без промежуточного String.join, без двойного пика памяти.
            exporter.processFileToFile(resultFile, outputFile, filter);

            // try-with-resources обязателен: Files.lines держит открытый файловый
            // дескриптор до закрытия потока — без него утечка ресурсов на любой ОС,
            // а на Windows ещё и блокировка файла.
            long messageCount;
            try (var lineStream = Files.lines(outputFile)) {
                messageCount = lineStream.count();
            }

            if (verbose) {
                System.out.println("Output written to: " + outputFile.toAbsolutePath());
                System.out.println("Total messages: " + messageCount);
            } else {
                System.out.println("Done! Processed " + messageCount + " messages.");
            }

        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }
}
