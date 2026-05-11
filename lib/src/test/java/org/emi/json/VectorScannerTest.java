package org.emi.json;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VectorScannerTest {

    private static MemorySegment seg(String s) {
        return MemorySegment.ofArray(s.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // skipWhitespace()
    // -------------------------------------------------------------------------

    @Test
    void skipWhitespaceNoLeading() {
        assertEquals(0, VectorScanner.skipWhitespace(seg("hello"), 0));
    }

    @Test
    void skipWhitespaceLeadingSpaces() {
        assertEquals(3, VectorScanner.skipWhitespace(seg("   hello"), 0));
    }

    @Test
    void skipWhitespaceAllSpaces() {
        MemorySegment s = seg("     ");
        assertEquals(5, VectorScanner.skipWhitespace(s, 0));
    }

    @Test
    void skipWhitespaceTab() {
        assertEquals(1, VectorScanner.skipWhitespace(seg("\thello"), 0));
    }

    @Test
    void skipWhitespaceNewline() {
        assertEquals(2, VectorScanner.skipWhitespace(seg("\r\nhello"), 0));
    }

    @Test
    void skipWhitespaceFromNonZeroOffset() {
        assertEquals(4, VectorScanner.skipWhitespace(seg("ab  X"), 2));
    }

    @Test
    void skipWhitespaceLongSegment() {
        String s = " ".repeat(64) + "X";
        assertEquals(64, VectorScanner.skipWhitespace(seg(s), 0));
    }

    @Test
    void skipWhitespaceLongSegmentMidway() {
        String s = "a".repeat(40) + "   Y";
        assertEquals(43, VectorScanner.skipWhitespace(seg(s), 40));
    }

    // -------------------------------------------------------------------------
    // findDelimiter()
    // -------------------------------------------------------------------------

    @Test
    void findDelimiterPresent() {
        assertEquals(3, VectorScanner.findDelimiter(seg("key:value"), 0, (byte) ':'));
    }

    @Test
    void findDelimiterAtStart() {
        assertEquals(0, VectorScanner.findDelimiter(seg(":rest"), 0, (byte) ':'));
    }

    @Test
    void findDelimiterNotPresent() {
        assertEquals(-1, VectorScanner.findDelimiter(seg("nodots"), 0, (byte) ':'));
    }

    @Test
    void findDelimiterEmptySegment() {
        assertEquals(-1, VectorScanner.findDelimiter(seg(""), 0, (byte) ':'));
    }

    @Test
    void findDelimiterFromOffset() {
        assertEquals(3, VectorScanner.findDelimiter(seg("a:b:c"), 2, (byte) ':'));
    }

    @Test
    void findDelimiterQuote() {
        assertEquals(6, VectorScanner.findDelimiter(seg("\"hello\"world"), 1, (byte) '"'));
    }

    @Test
    void findDelimiterLongSegment() {
        String s = "x".repeat(64) + ":end";
        assertEquals(64, VectorScanner.findDelimiter(seg(s), 0, (byte) ':'));
    }

    @Test
    void findDelimiterAtEndOfLongSegment() {
        String s = "x".repeat(63) + ":";
        assertEquals(63, VectorScanner.findDelimiter(seg(s), 0, (byte) ':'));
    }
}
