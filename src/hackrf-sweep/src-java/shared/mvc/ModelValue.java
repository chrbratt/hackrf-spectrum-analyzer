package shared.mvc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A named value that notifies registered listeners whenever it changes.
 * <p>
 * Originally built on {@link java.util.Observable}, which was deprecated in
 * Java 9. The semantics are identical: listeners registered via
 * {@link #addListener(Consumer)} / {@link #addListener(Runnable)} are invoked
 * on the thread that calls {@link #setValue(Object)} or
 * {@link #callObservers()}, in registration order.
 *
 * @param <T> value type
 */
public class ModelValue<T> {

	public static class ModelValueInt extends ModelValue<Integer> {
		protected final boolean isBounded;
		protected final int min, max, step;

		public ModelValueInt(String name, int initialValue) {
			super(name, initialValue);
			isBounded = false;
			step = min = max = 0;
		}

		public ModelValueInt(String name, int initialValue, int step, int min, int max) {
			super(name, initialValue);
			isBounded = true;
			this.min = min;
			this.max = max;
			this.step = step;
		}

		public int getMax() {
			return max;
		}

		public int getMin() {
			return min;
		}

		public int getStep() {
			return step;
		}

		@Override
		public void setValue(Integer value) {
			if (isBounded() && (value < min || value > max))
				throw new IllegalStateException(
						"New value of '" + name + "' '" + value + "' is outside of bounds <" + min + ", " + max + ">");
			super.setValue(value);
		}

		public boolean isBounded() {
			return isBounded;
		}
	}

	public static class ModelValueBoolean extends ModelValue<Boolean> {
		public ModelValueBoolean(String name, Boolean initialValue) {
			super(name, initialValue);
		}
	}

	// CopyOnWriteArrayList lets listeners register/unregister safely while a
	// notification is in flight (mirrors the old Observable's iteration guarantees).
	private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

	private T value;
	protected final String name;

	public ModelValue(String name, T initialValue) {
		this.value = initialValue;
		this.name = name;
	}

	public void addListener(Consumer<T> listener) {
		listeners.add(listener);
	}

	public void addListener(Runnable listener) {
		listeners.add(v -> listener.run());
	}

	public void removeListener(Consumer<T> listener) {
		listeners.remove(listener);
	}

	public void setValue(T value) {
		if (value == null || this.value == null) {
			if (value == this.value)
				return;
		} else if (this.value.equals(value)) {
			return;
		}
		this.value = value;
		callObservers();
	}

	/**
	 * Force all listeners to be notified with the current value, even when it
	 * has not actually changed. Kept for compatibility with callers that relied
	 * on {@code Observable.setChanged()/notifyObservers()}.
	 */
	public void callObservers() {
		for (Consumer<T> listener : listeners) {
			listener.accept(value);
		}
	}

	public T getValue() {
		return value;
	}

	@Override
	public String toString() {
		return name;
	}
}
