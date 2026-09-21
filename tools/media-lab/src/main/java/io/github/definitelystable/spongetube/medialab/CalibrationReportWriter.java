package io.github.definitelystable.spongetube.medialab;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class CalibrationReportWriter {

    private CalibrationReportWriter() {
    }

    static void write(Path path, ResolvedScenario scenario, SessionCalibration calibration)
            throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.writeString(
                path,
                calibration.toJson(scenario) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }
}
