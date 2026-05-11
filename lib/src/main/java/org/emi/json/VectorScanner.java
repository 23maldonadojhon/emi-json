package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import jdk.incubator.vector.*;

/**
 * Utilidad de escaneo de bytes con aceleración SIMD via Vector API (incubator).
 * Todas las operaciones trabajan directamente sobre MemorySegment para evitar copias.
 * No instanciable: solo métodos estáticos, sin estado.
 */
final class VectorScanner {

    // SPECIES_PREFERRED selecciona el ancho de registro SIMD más amplio que soporta la CPU
    // en tiempo de ejecución (128, 256 o 512 bits). El JIT lo especializa por plataforma.
    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;

    /**
     * Avanza {@code offset} hasta el primer byte > 32 (primer carácter no-whitespace).
     * Procesa {@code SPECIES.length()} bytes por iteración en el tramo vectorizable;
     * el tail (<= un vector) se completa con un loop escalar.
     * {@code loopBound} garantiza que el acceso vectorial nunca lee más allá del segmento.
     */
    static long skipWhitespace(MemorySegment segment, long offset) {
        long i = offset;
        long size = segment.byteSize();
        long limit = SPECIES.loopBound(size);

        for (; i < limit - SPECIES.length(); i += SPECIES.length()) {
            ByteVector v = ByteVector.fromMemorySegment(SPECIES, segment, i, ByteOrder.nativeOrder());
            // GT 32 identifica cualquier byte imprimible; firstTrue() devuelve la posición exacta.
            var mask = v.compare(VectorOperators.GT, (byte) 32);
            if (mask.anyTrue()) return i + mask.firstTrue();
        }
        // Tail escalar: bytes restantes que no llenan un vector completo.
        while (i < size && segment.get(ValueLayout.JAVA_BYTE, i) <= 32)
            i++;
        return i;
    }

    /**
     * Busca la próxima ocurrencia de {@code target} a partir de {@code offset}.
     * Retorna la posición absoluta del byte encontrado, o {@code -1} si no existe.
     * {@code broadcast} replica el byte buscado en todos los lanes del vector para
     * comparar 16/32/64 bytes simultáneamente con una sola instrucción SIMD.
     */
    static long findDelimiter(MemorySegment segment, long offset, byte target) {
        long i = offset;
        long size = segment.byteSize();
        long limit = SPECIES.loopBound(size);
        // El vector de comparación se construye una sola vez fuera del loop.
        ByteVector vTarget = ByteVector.broadcast(SPECIES, target);

        for (; i < limit - SPECIES.length(); i += SPECIES.length()) {
            ByteVector vInput = ByteVector.fromMemorySegment(SPECIES, segment, i, ByteOrder.nativeOrder());
            var mask = vInput.compare(VectorOperators.EQ, vTarget);
            if (mask.anyTrue()) return i + mask.firstTrue();
        }
        // Tail escalar.
        while (i < size && segment.get(ValueLayout.JAVA_BYTE, i) != target)
            i++;
        return (i < size) ? i : -1;
    }
}
