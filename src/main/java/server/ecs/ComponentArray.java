package server.ecs;

import java.util.Arrays;

/**
 * Sparse-set component storage for reference-typed components.
 *
 * <p>Two parallel arrays:
 * <ul>
 *   <li>{@code sparse[entityIndex]} &rarr; dense slot (or {@code -1} if none)</li>
 *   <li>{@code dense[slot]}         &rarr; component value</li>
 * </ul>
 * Iteration is cache-friendly over the {@code dense} array; per-entity lookup is O(1).
 *
 * <p>Components are <em>not</em> required to be present for every entity. Adding a
 * component creates a new dense slot; removing it swaps with the last dense slot
 * and pops. This keeps the dense array packed so systems can iterate only over
 * entities that actually have the component.
 *
 * <p>Not thread-safe. Drive it from a single update thread per zone.
 *
 * @param <T> component type. Use plain data-holders (POJOs or records) &mdash; the ECS
 *            treats them as opaque. If you need primitive storage, use
 *            {@link IntComponentArray} instead.
 */
public final class ComponentArray<T> {

	private static final int INITIAL_DENSE_CAPACITY = 16;

	/** Maps entity index &rarr; dense slot, or -1. */
	private int[] sparse;

	/** Dense entity index for each dense slot (parallel to {@link #values}). */
	private int[] denseEntities;

	/** Dense component values. */
	private Object[] values;

	private int size;

	public ComponentArray() {
		this(INITIAL_DENSE_CAPACITY);
	}

	public ComponentArray(int initialDenseCapacity) {
		int cap = Math.max(1, initialDenseCapacity);
		this.sparse = new int[cap];
		Arrays.fill(this.sparse, -1);
		this.denseEntities = new int[cap];
		this.values = new Object[cap];
	}

	/**
	 * Adds or replaces the component on the given entity index.
	 *
	 * @param entityIndex entity slot index (as returned by
	 *                    {@link World#index(long)}); must be &gt; 0
	 * @param value       component value; must not be {@code null}
	 */
	public void set(int entityIndex, T value) {
		if (value == null) {
			throw new IllegalArgumentException("component value must not be null");
		}
		ensureSparseCapacity(entityIndex);
		int slot = sparse[entityIndex];
		if (slot >= 0) {
			values[slot] = value;
			return;
		}
		ensureDenseCapacity(size + 1);
		slot = size++;
		sparse[entityIndex] = slot;
		denseEntities[slot] = entityIndex;
		values[slot] = value;
	}

	/**
	 * Returns the component for the given entity index, or {@code null} if the entity
	 * does not have this component.
	 */
	@SuppressWarnings("unchecked")
	public T get(int entityIndex) {
		if (entityIndex < 0 || entityIndex >= sparse.length) {
			return null;
		}
		int slot = sparse[entityIndex];
		if (slot < 0) {
			return null;
		}
		return (T) values[slot];
	}

	/** Returns {@code true} if the entity currently has this component. */
	public boolean has(int entityIndex) {
		if (entityIndex < 0 || entityIndex >= sparse.length) {
			return false;
		}
		return sparse[entityIndex] >= 0;
	}

	/**
	 * Removes the component from the entity. No-op if not present. Uses
	 * swap-with-last so the dense array remains packed.
	 */
	public void remove(int entityIndex) {
		if (entityIndex < 0 || entityIndex >= sparse.length) {
			return;
		}
		int slot = sparse[entityIndex];
		if (slot < 0) {
			return;
		}
		int last = size - 1;
		if (slot != last) {
			int lastEntity = denseEntities[last];
			denseEntities[slot] = lastEntity;
			values[slot] = values[last];
			sparse[lastEntity] = slot;
		}
		values[last] = null;
		denseEntities[last] = 0;
		sparse[entityIndex] = -1;
		size--;
	}

	/** Number of entities that currently have this component. */
	public int size() {
		return size;
	}

	/**
	 * Entity index at dense slot {@code i}. Use with {@link #valueAt(int)} to iterate
	 * packed over present components:
	 * <pre>
	 * for (int i = 0, n = comp.size(); i &lt; n; i++) {
	 *     int e = comp.entityAt(i);
	 *     MyComp v = comp.valueAt(i);
	 *     ...
	 * }
	 * </pre>
	 */
	public int entityAt(int denseSlot) {
		return denseEntities[denseSlot];
	}

	/** Value at dense slot {@code i}. See {@link #entityAt(int)}. */
	@SuppressWarnings("unchecked")
	public T valueAt(int denseSlot) {
		return (T) values[denseSlot];
	}

	private void ensureSparseCapacity(int minCapacity) {
		if (minCapacity < sparse.length) {
			return;
		}
		int newCap = sparse.length;
		while (newCap <= minCapacity) {
			newCap *= 2;
		}
		int oldLen = sparse.length;
		sparse = Arrays.copyOf(sparse, newCap);
		Arrays.fill(sparse, oldLen, newCap, -1);
	}

	private void ensureDenseCapacity(int minCapacity) {
		if (minCapacity <= values.length) {
			return;
		}
		int newCap = values.length;
		while (newCap < minCapacity) {
			newCap *= 2;
		}
		denseEntities = Arrays.copyOf(denseEntities, newCap);
		values = Arrays.copyOf(values, newCap);
	}
}
