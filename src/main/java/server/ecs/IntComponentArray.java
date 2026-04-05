package server.ecs;

import java.util.Arrays;

/**
 * Primitive-int variant of {@link ComponentArray}. Same sparse-set layout, but the
 * dense value array is a plain {@code int[]} so there is no boxing on the hot path.
 *
 * <p>Use this for numeric components that are read/written every tick &mdash; health
 * pools, coordinates, skill points, zone ids. Use {@link ComponentArray} for strings
 * and compound POJOs.
 */
public final class IntComponentArray {

	private static final int INITIAL_DENSE_CAPACITY = 16;

	private int[] sparse;
	private int[] denseEntities;
	private int[] values;
	private int size;

	public IntComponentArray() {
		this(INITIAL_DENSE_CAPACITY);
	}

	public IntComponentArray(int initialDenseCapacity) {
		int cap = Math.max(1, initialDenseCapacity);
		this.sparse = new int[cap];
		Arrays.fill(this.sparse, -1);
		this.denseEntities = new int[cap];
		this.values = new int[cap];
	}

	public void set(int entityIndex, int value) {
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
	 * Returns the component's value for {@code entityIndex}, or {@code defaultValue}
	 * if the entity does not have this component.
	 */
	public int getOrDefault(int entityIndex, int defaultValue) {
		if (entityIndex < 0 || entityIndex >= sparse.length) {
			return defaultValue;
		}
		int slot = sparse[entityIndex];
		if (slot < 0) {
			return defaultValue;
		}
		return values[slot];
	}

	/**
	 * Returns the component's value. Throws if the entity does not have this component,
	 * so callers that know the entity must have it can fail fast.
	 */
	public int get(int entityIndex) {
		if (entityIndex < 0 || entityIndex >= sparse.length || sparse[entityIndex] < 0) {
			throw new IllegalStateException("entity " + entityIndex + " has no component");
		}
		return values[sparse[entityIndex]];
	}

	public boolean has(int entityIndex) {
		if (entityIndex < 0 || entityIndex >= sparse.length) {
			return false;
		}
		return sparse[entityIndex] >= 0;
	}

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
		values[last] = 0;
		denseEntities[last] = 0;
		sparse[entityIndex] = -1;
		size--;
	}

	public int size() {
		return size;
	}

	public int entityAt(int denseSlot) {
		return denseEntities[denseSlot];
	}

	public int valueAt(int denseSlot) {
		return values[denseSlot];
	}

	/**
	 * Writes a new value at dense slot {@code i}. Useful in tight iteration loops
	 * that update components in place without a hash lookup.
	 */
	public void setValueAt(int denseSlot, int value) {
		values[denseSlot] = value;
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
