package team.rainfall.finality;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FinalityLoggerTest {
    @TempDir Path temporaryDirectory;

    @Test
    void consoleHasAnsiColorWithoutTimeWhileFileUsesStartupNameAndTimestamps() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(console, true, "UTF-8"));
            FinalityLogger.init(temporaryDirectory);
            FinalityLogger.info("startup-check");

            Path logFile = FinalityLogger.getLogFile();
            assertNotNull(logFile);
            assertTrue(logFile.getFileName().toString().matches("\\d{8}-\\d{6}-\\d{3}(?:-\\d+)?\\.log"));

            String consoleText = new String(console.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(consoleText.contains("\u001B[32m[INFO] startup-check"));
            assertFalse(consoleText.contains("202"));

            FinalityLogger.close();
            String fileText = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            assertTrue(fileText.matches("(?s).*\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] \\[INFO\\] startup-check.*"));
        } finally {
            FinalityLogger.close();
            System.setOut(originalOut);
        }
    }
}
