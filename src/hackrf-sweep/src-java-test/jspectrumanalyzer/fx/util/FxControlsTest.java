package jspectrumanalyzer.fx.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The snap logic is the safety net that keeps invalid gain values from ever
 * reaching {@code hackrf_sweep}. Regressions here crash the native layer, so
 * the behaviour is pinned down with tests.
 */
class FxControlsTest {

	@Test
	@DisplayName("LNA gain: input between steps snaps to the closest valid 8 dB multiple")
	void lnaSnap() {
		assertEquals(0, FxControls.snap(0, 0, 40, 8));
		assertEquals(0, FxControls.snap(3, 0, 40, 8));
		assertEquals(8, FxControls.snap(5, 0, 40, 8));
		assertEquals(8, FxControls.snap(11, 0, 40, 8));
		// 12 is halfway between 8 and 16 → rounds up (Math.round ties to +inf).
		assertEquals(16, FxControls.snap(12, 0, 40, 8));
		assertEquals(16, FxControls.snap(15, 0, 40, 8));
		assertEquals(40, FxControls.snap(37, 0, 40, 8));
		assertEquals(40, FxControls.snap(40, 0, 40, 8));
	}

	@Test
	@DisplayName("VGA gain: step 2, max 62 dB")
	void vgaSnap() {
		assertEquals(0, FxControls.snap(0, 0, 62, 2));
		// Halfway points round to the upper step (Math.round ties to +inf).
		assertEquals(2, FxControls.snap(1, 0, 62, 2));
		assertEquals(4, FxControls.snap(3, 0, 62, 2));
		assertEquals(4, FxControls.snap(4, 0, 62, 2));
		assertEquals(62, FxControls.snap(61, 0, 62, 2));
		assertEquals(62, FxControls.snap(62, 0, 62, 2));
	}

	@Test
	@DisplayName("Out-of-range values are clamped")
	void clampBounds() {
		assertEquals(0, FxControls.snap(-5, 0, 40, 8));
		assertEquals(40, FxControls.snap(99, 0, 40, 8));
	}

	@Test
	@DisplayName("step=0 or step=1 disables snapping but still clamps")
	void noStep() {
		assertEquals(5, FxControls.snap(5, 0, 40, 0));
		assertEquals(5, FxControls.snap(5, 0, 40, 1));
		assertEquals(0, FxControls.snap(-1, 0, 40, 0));
		assertEquals(40, FxControls.snap(50, 0, 40, 1));
	}

	@Test
	@DisplayName("Non-aligned max: snap never exceeds max")
	void nonAlignedMax() {
		// step 3, max 10 -> valid: 0, 3, 6, 9 (10 is not on the grid)
		assertEquals(9, FxControls.snap(10, 0, 10, 3));
		assertEquals(9, FxControls.snap(9, 0, 10, 3));
		assertEquals(6, FxControls.snap(7, 0, 10, 3));
	}
}
