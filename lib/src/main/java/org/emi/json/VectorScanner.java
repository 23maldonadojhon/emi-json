package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.*;

/**
 * Utilidad de escaneo de bytes con aceleración SIMD via Vector API.
 * Optimización: Vectores de delimitadores pre-calculados para eliminar overhead de broadcast.
 */
final class VectorScanner {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    
    // Vectores pre-calculados para los caracteres más comunes en el hot path del JSON.
    private static final ByteVector V_QUOTE = ByteVector.broadcast(SPECIES, (byte) '"');
    private static final ByteVector V_COLON = ByteVector.broadcast(SPECIES, (byte) ':');
    private static final ByteVector V_COMMA = ByteVector.broadcast(SPECIES, (byte) ',');
    private static final ByteVector V_SPACE = ByteVector.broadcast(SPECIES, (byte) ' ');

    static long skipWhitespace(MemorySegment segment, long offset) {
        long i = offset;
        long size = segment.byteSize();
        long limit = size - SPECIES.length();

        for (; i <= limit; i += SPECIES.length()) {
            ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, i, ByteOrder.nativeOrder());
            var mask = v.compare(VectorOperators.GT, (byte) 32);
            if (mask.anyTrue()) return i + mask.firstTrue();
        }
        while (i < size && segment.get(ValueLayout.JAVA_BYTE, i) <= 32) i++;
        return i;
    }

    static long findDelimiter(MemorySegment segment, long offset, byte target) {
        // Selección de vector pre-calculado para evitar broadcast en el loop.
        ByteVector vTarget = switch (target) {
            case '"' -> V_QUOTE;
            case ':' -> V_COLON;
            case ',' -> V_COMMA;
            default  -> ByteVector.broadcast(SPECIES, target);
        };

        long i = offset;
        long size = segment.byteSize();
        long limit = size - SPECIES.length();

        for (; i <= limit; i += SPECIES.length()) {
            ByteVector vInput = ByteVector.fromMemorySegment(SPECIES, segment, i, ByteOrder.nativeOrder());
            var mask = vInput.compare(VectorOperators.EQ, vTarget);
            if (mask.anyTrue()) return i + mask.firstTrue();
        }
        while (i < size && segment.get(ValueLayout.JAVA_BYTE, i) != target) i++;
        return (i < size) ? i : -1;
    }
}
