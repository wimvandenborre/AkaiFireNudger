package com.akai.fire;

import com.bitwig.extension.controller.api.ControllerHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class DiagnosticLog {

    private static final Path LOG_PATH = Path.of(
            "/Users/wimvandenborre/Documents/Bitwig Studio/Extensions/FireNudger.log");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ControllerHost host;

    public DiagnosticLog(final ControllerHost host) {
        this.host = host;
        reset();
    }

    public synchronized void reset() {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            Files.writeString(LOG_PATH, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log("LOG_START path=" + LOG_PATH);
        } catch (final IOException exception) {
            reportFailure("reset", exception);
        }
    }

    public synchronized void log(final String message) {
        final String line = TIMESTAMP.format(LocalDateTime.now()) + " " + message + System.lineSeparator();
        try {
            Files.writeString(LOG_PATH, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException exception) {
            reportFailure("write", exception);
        }
    }

    public Path getPath() {
        return LOG_PATH;
    }

    private void reportFailure(final String operation, final IOException exception) {
        host.println("FireNudger diagnostic log " + operation + " failed: " + exception.getMessage());
    }
}
