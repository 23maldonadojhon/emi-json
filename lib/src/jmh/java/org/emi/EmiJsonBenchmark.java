package org.emi;

import org.emi.json.EmiJson;
import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL; // Soluciona error de símbolo URL

@State(Scope.Benchmark)
public class EmiJsonBenchmark {

    // Record interno para la prueba
    public static record EmiFrameworkSimple(
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
            String description) {
    }

    private List<java.lang.foreign.MemorySegment> segments;
    private EmiJson<EmiFrameworkSimple> parser;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        parser = EmiJson.of(EmiFrameworkSimple.class).build();
        segments = new ArrayList<>();

        String resourceName = "/emi_simple_linear.json"; // Nota el '/' inicial

        // Forma robusta de leer recursos dentro y fuera de un JAR
        try (InputStream is = getClass().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new RuntimeException("No se encontró el archivo: " + resourceName);
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                        segments.add(java.lang.foreign.MemorySegment.ofArray(bytes));
                    }
                }
            }
        }
        System.out.println("Segmentos cargados para el benchmark: " + segments.size());
    }

    // Índice por hilo para rotar entre líneas sin contención
    @State(Scope.Thread)
    public static class LineIndex {
        int idx = 0;
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public EmiFrameworkSimple parseSingleRecord(LineIndex state) {
        int index = state.idx++;
        if (index >= segments.size()) {
            index = 0;
            state.idx = 1;
        }
        java.lang.foreign.MemorySegment segment = segments.get(index);
        return parser.parse(segment);   // retorno obligatorio: evita dead-code elimination
    }
}