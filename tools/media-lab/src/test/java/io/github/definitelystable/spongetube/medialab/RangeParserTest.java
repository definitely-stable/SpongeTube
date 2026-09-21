package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class RangeParserTest {

    @Test
    void noRangeReturnsFull() {
        assertInstanceOf(RangeDecision.Full.class, RangeParser.parse(null, 100));
    }

    @Test
    void boundedRangeIsInclusiveInHeaderAndExclusiveInternally() {
        RangeDecision.Partial partial =
                assertInstanceOf(RangeDecision.Partial.class, RangeParser.parse("bytes=10-19", 100));
        assertEquals(10, partial.startInclusive());
        assertEquals(20, partial.endExclusive());
        assertEquals(10, partial.length());
    }

    @Test
    void openEndedRangeExtendsToEnd() {
        RangeDecision.Partial partial =
                assertInstanceOf(RangeDecision.Partial.class, RangeParser.parse("bytes=90-", 100));
        assertEquals(90, partial.startInclusive());
        assertEquals(100, partial.endExclusive());
    }

    @Test
    void suffixRangeSelectsTail() {
        RangeDecision.Partial partial =
                assertInstanceOf(RangeDecision.Partial.class, RangeParser.parse("bytes=-25", 100));
        assertEquals(75, partial.startInclusive());
        assertEquals(100, partial.endExclusive());
    }

    @Test
    void oversizedSuffixSelectsWholeRepresentationAsPartial() {
        RangeDecision.Partial partial =
                assertInstanceOf(RangeDecision.Partial.class, RangeParser.parse("bytes=-500", 100));
        assertEquals(0, partial.startInclusive());
        assertEquals(100, partial.endExclusive());
    }

    @Test
    void endBeyondEofIsClamped() {
        RangeDecision.Partial partial =
                assertInstanceOf(RangeDecision.Partial.class, RangeParser.parse("bytes=90-999", 100));
        assertEquals(90, partial.startInclusive());
        assertEquals(100, partial.endExclusive());
    }

    @Test
    void startAtOrBeyondLengthIsUnsatisfiable() {
        assertInstanceOf(
                RangeDecision.Unsatisfiable.class,
                RangeParser.parse("bytes=100-101", 100));
        assertInstanceOf(
                RangeDecision.Unsatisfiable.class,
                RangeParser.parse("bytes=101-", 100));
    }

    @Test
    void reversedRangeIsUnsatisfiable() {
        assertInstanceOf(
                RangeDecision.Unsatisfiable.class,
                RangeParser.parse("bytes=20-10", 100));
    }

    @Test
    void zeroLengthSuffixIsUnsatisfiableByLabContract() {
        assertInstanceOf(
                RangeDecision.Unsatisfiable.class,
                RangeParser.parse("bytes=-0", 100));
    }

    @Test
    void malformedAndMultiRangeAreIgnoredDeterministically() {
        assertInstanceOf(RangeDecision.Full.class, RangeParser.parse("bytes=abc-def", 100));
        assertInstanceOf(RangeDecision.Full.class, RangeParser.parse("bytes=0-1,4-5", 100));
        assertInstanceOf(RangeDecision.Full.class, RangeParser.parse("items=0-1", 100));
    }
}
