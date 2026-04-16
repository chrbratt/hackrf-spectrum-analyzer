package shared.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import shared.mvc.ModelValue.ModelValueInt;

/**
 * Pins down the notification semantics that UI + engine code rely on. The
 * previous implementation delegated to {@link java.util.Observable} (removed);
 * these tests make sure the replacement behaves the same.
 */
class ModelValueTest {

	@Test
	@DisplayName("setValue fires listeners only when the value actually changes")
	void notifiesOnChange() {
		ModelValue<String> m = new ModelValue<>("name", "a");
		AtomicInteger calls = new AtomicInteger();
		m.addListener(() -> calls.incrementAndGet());

		m.setValue("a");
		assertEquals(0, calls.get(), "same value should not notify");

		m.setValue("b");
		assertEquals(1, calls.get(), "new value should notify once");
	}

	@Test
	@DisplayName("Consumer listener receives the current value")
	void consumerReceivesCurrentValue() {
		ModelValue<String> m = new ModelValue<>("name", "a");
		AtomicInteger calls = new AtomicInteger();
		m.addListener((String v) -> {
			if ("b".equals(v)) calls.incrementAndGet();
		});
		m.setValue("b");
		assertEquals(1, calls.get());
	}

	@Test
	@DisplayName("callObservers forces a notification even without a value change")
	void callObserversForceNotify() {
		ModelValue<String> m = new ModelValue<>("name", "a");
		AtomicInteger calls = new AtomicInteger();
		m.addListener(() -> calls.incrementAndGet());
		m.callObservers();
		assertEquals(1, calls.get());
	}

	@Test
	@DisplayName("removeListener stops further notifications")
	void removeListener() {
		ModelValue<String> m = new ModelValue<>("name", "a");
		AtomicInteger calls = new AtomicInteger();
		java.util.function.Consumer<String> l = v -> calls.incrementAndGet();
		m.addListener(l);
		m.setValue("b");
		m.removeListener(l);
		m.setValue("c");
		assertEquals(1, calls.get());
	}

	@Test
	@DisplayName("Bounded ModelValueInt rejects out-of-range values")
	void boundedRejects() {
		ModelValueInt m = new ModelValueInt("gain", 0, 8, 0, 40);
		assertTrue(m.isBounded());
		assertEquals(8, m.getStep());
		assertThrows(IllegalStateException.class, () -> m.setValue(-1));
		assertThrows(IllegalStateException.class, () -> m.setValue(41));
	}

	@Test
	@DisplayName("Unbounded ModelValueInt accepts any value")
	void unboundedAccepts() {
		ModelValueInt m = new ModelValueInt("free", 0);
		assertFalse(m.isBounded());
		m.setValue(-9999);
		m.setValue(9999);
		assertEquals(9999, m.getValue());
	}

	@Test
	@DisplayName("null transitions are handled symmetrically")
	void nullTransitions() {
		ModelValue<String> m = new ModelValue<>("name", null);
		AtomicInteger calls = new AtomicInteger();
		m.addListener(() -> calls.incrementAndGet());

		m.setValue(null);
		assertEquals(0, calls.get(), "null -> null should not notify");

		m.setValue("a");
		assertEquals(1, calls.get());

		m.setValue(null);
		assertEquals(2, calls.get(), "non-null -> null should notify");
	}
}
