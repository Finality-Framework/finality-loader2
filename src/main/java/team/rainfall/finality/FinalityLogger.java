package team.rainfall.finality;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Small framework logger with independent console and file formatting. */
public final class FinalityLogger {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final String RESET = "\u001B[0m";
    private static BufferedWriter file;
    private static boolean debug;
    private static Path logFile;
    private static LocalDateTime startedAt;

    private FinalityLogger() { }

    public static synchronized void init(Path workingDirectory) {
        try {
            if (file != null) file.close();
            startedAt = LocalDateTime.now();
            Path directory = workingDirectory.resolve("finality").resolve("logs");
            Files.createDirectories(directory);
            String baseName = startedAt.format(FILE_TIMESTAMP);
            Path candidate = directory.resolve(baseName + ".log");
            int suffix = 1;
            while (Files.exists(candidate)) candidate = directory.resolve(baseName + "-" + suffix++ + ".log");
            logFile = candidate;
            file = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            file.write("[" + startedAt.format(TIMESTAMP) + "] [INFO] Logger initiated");
            file.newLine();
            file.flush();
        } catch (IOException e) {
            file = null;
            logFile = null;
            System.err.println("Finality Logger: unable to open log file: " + e.getMessage());
        }
    }

    public static void setDebug(boolean enabled) { debug = enabled; }
    public static void info(String message) { write("INFO", message, false); }
    public static void warn(String message) { write("WARN", message, false); }
    public static void error(String message) { write("ERROR", message, true); }
    public static void error(String message, Throwable throwable) {
        write("ERROR", message + System.lineSeparator() + stackTrace(throwable), true);
    }
    public static void debug(String message) { if (debug) write("DEBUG", message, false); }

    public static synchronized Path getLogFile() { return logFile; }

    public static synchronized void close() {
        if (file == null) return;
        try { file.close(); }
        catch (IOException ignored) { }
        finally { file = null; }
    }

    private static synchronized void write(String level, String message, boolean stderr) {
        LocalDateTime now = LocalDateTime.now();
        String consoleLine = "[" + level + "] " + message;
        String fileLine = "[" + now.format(TIMESTAMP) + "] " + consoleLine;
        String color = color(level);
        if (stderr) System.err.println(color + consoleLine + RESET);
        else System.out.println(color + consoleLine + RESET);
        if (file != null) {
            try { file.write(fileLine); file.newLine(); file.flush(); }
            catch (IOException ignored) { }
        }
    }

    private static String color(String level) {
        if ("ERROR".equals(level)) return "\u001B[31m";
        if ("WARN".equals(level)) return "\u001B[33m";
        if ("DEBUG".equals(level)) return "\u001B[36m";
        return "\u001B[32m";
    }

    private static String stackTrace(Throwable throwable) {
        StringBuilder result = new StringBuilder(throwable.toString());
        for (StackTraceElement element : throwable.getStackTrace()) result.append(System.lineSeparator()).append("\tat ").append(element);
        return result.toString();
    }
}
