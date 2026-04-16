package jspectrumanalyzer.fx.frequency;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import jspectrumanalyzer.core.FrequencyRange;

class FrequencyRangeValidatorTest {

    private final FrequencyRangeValidator validator = new FrequencyRangeValidator(0, 7250);

    @Test
    void coerceKeepsValidRangeUntouched() {
        FrequencyRange r = validator.coerce(100, 200);
        assertEquals(100, r.getStartMHz());
        assertEquals(200, r.getEndMHz());
    }

    @Test
    void coerceClampsBelowMin() {
        FrequencyRange r = validator.coerce(-50, 100);
        assertEquals(0, r.getStartMHz());
        assertEquals(100, r.getEndMHz());
    }

    @Test
    void coerceClampsAboveMax() {
        FrequencyRange r = validator.coerce(7000, 8000);
        assertEquals(7000, r.getStartMHz());
        assertEquals(7250, r.getEndMHz());
    }

    @Test
    void coercePushesEndAboveStartWhenCrossed() {
        FrequencyRange r = validator.coerce(500, 300);
        assertEquals(500, r.getStartMHz());
        assertEquals(501, r.getEndMHz());
    }

    @Test
    void coerceRespectingEndPullsStartDown() {
        FrequencyRange r = validator.coerceRespecting(500, 1000, 500, 200,
                FrequencyRangeValidator.Endpoint.END);
        assertEquals(199, r.getStartMHz());
        assertEquals(200, r.getEndMHz());
    }

    @Test
    void coerceRespectingStartPushesEndUp() {
        FrequencyRange r = validator.coerceRespecting(500, 1000, 1200, 1000,
                FrequencyRangeValidator.Endpoint.START);
        assertEquals(1200, r.getStartMHz());
        assertEquals(1201, r.getEndMHz());
    }

    @Test
    void coerceAtUpperBoundaryLeavesOneMhzSpan() {
        FrequencyRange r = validator.coerce(7250, 7250);
        assertEquals(7249, r.getStartMHz());
        assertEquals(7250, r.getEndMHz());
    }

    @Test
    void isValidRejectsTooNarrow() {
        assertFalse(validator.isValid(500, 500));
    }

    @Test
    void isValidAcceptsOneMhzSpan() {
        assertTrue(validator.isValid(500, 501));
    }

    @Test
    void isValidRejectsOutOfRange() {
        assertFalse(validator.isValid(-1, 100));
        assertFalse(validator.isValid(0, 8000));
    }

    @Test
    void constructorRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new FrequencyRangeValidator(100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new FrequencyRangeValidator(200, 100));
    }
}
