package org.emi.json;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

public class LongFileTest {

    public record EmiFrameworkTest(
            int id,
            String uid,
            String framework_name,
            String version,
            Developer developer,
            List<String> tags,
            Metadata metadata,
            String description) {
        public record Developer(
                String name,
                double contribution_score,
                boolean is_active) {}

        public record Metadata(
                long timestamp,
                String environment,
                String build_status,
                List<String> dependencies) {}
    }

    public record EmiFrameworkSimple(
            int id,
            String uid,
            String framework_name,
            String version,
            String developer_name,
            double contribution_score,
            boolean is_active,
            long timestamp,
            String environment,
            String build_status,
            String description) {}

    @Test
    public void testLinearFile() throws Exception {
        String resourceName = "emi_simple_linear.json";
        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(resourceName);

        if (resource == null)
            throw new RuntimeException("Archivo no encontrado");

        Path path = Paths.get(resource.toURI());

        var emiJson = EmiJson.of(EmiFrameworkTest.class);

        long startTime = System.nanoTime();

        try (Stream<String> lines = Files.lines(path)) {
            lines.forEach(line -> {
                EmiFrameworkTest registro = emiJson.parse(line);
                System.out.println("ID procesado: " + registro.id());
            });
        }

        long endTime = System.nanoTime();
        System.out.println("Emi Framework - Procesamiento lineal completado");
        System.out.println("Tiempo total: " + (endTime - startTime) / 1_000_000.0 + " ms");
    }
}
