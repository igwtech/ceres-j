package server.ecs;

import java.util.Arrays;

/**
 * Light ECS entity pool.
 *
 * <p>Stores a free-list of recyclable entity ids plus a generation counter per slot
 * so that stale handles can be detected after an entity is destroyed. No frameworks,
 * no reflection, no component registry &mdash; just primitive arrays.
 *
 * <p>Entity handles are packed into a single {@code long}:
 * <pre>
 *   bits  0..31 : entity index (slot)
 *   bits 32..63 : entity generation
 * </pre>
 * A handle becomes "stale" as soon as the slot it references is freed: the stored
 * generation is bumped on every free, so {@link #isAlive(long)} returns {@code false}
 * for any previously-issued handle that points at a now-recycled slot.
 *
 * <p>This class is deliberately small. It does not know about components &mdash; component
 * storage lives in {@link ComponentArray} / {@link IntComponentArray}. Systems are just
 * plain methods elsewhere in the codebase that loop over the component arrays.
 *
 * <p>Not thread-safe. Callers should serialize access (the game loop is single-threaded
 * per zone in Ceres-J, which is where the ECS is expected to be driven).
 */
public final class World {

	/** Sentinel for "no entity". Equivalent to a handle of index 0, generation 0. */
	public static final long NULL = 0L;

	/** Initial slot capacity. Grows geometrically. */
	private static final int INITIAL_CAPACITY = 64;

	/** Generation of each slot. Starts at 1 so handle 0 is always NULL/stale. */
	private int[] generations;

	/** True if the slot is currently allocated. Parallel to {@link #generations}. */
	private boolean[] alive;

	/** Stack of free slot indices. */
	private int[] freeList;
	private int freeListSize;

	/** Highest slot index ever handed out + 1. */
	private int highWater;

	/** Number of currently alive entities. */
	private int liveCount;

	public World() {
		this(INITIAL_CAPACITY);
	}

	public World(int initialCapacity) {
		int cap = Math.max(1, initialCapacity);
		this.generations = new int[cap];
		this.alive = new boolean[cap];
		this.freeList = new int[cap];
		// Reserve slot 0 so that NULL (handle == 0) cannot collide with a real entity.
		this.generations[0] = 1;
		this.highWater = 1;
	}

	/**
	 * Allocates a new entity and returns its handle. Reuses a freed slot if available.
	 *
	 * @return packed entity handle (index + generation), never {@link #NULL}
	 */
	public long createEntity() {
		int index;
		if (freeListSize > 0) {
			index = freeList[--freeListSize];
		} else {
			index = highWater++;
			if (index >= generations.length) {
				grow(index + 1);
			}
			generations[index] = 1;
		}
		alive[index] = true;
		liveCount++;
		return pack(index, generations[index]);
	}

	/**
	 * Destroys the entity referenced by {@code handle}. Silently ignores a stale or
	 * already-destroyed handle. The slot's generation is bumped so any outstanding
	 * handles to this slot are invalidated.
	 *
	 * <p>Note: this does <em>not</em> clear component data. Callers that need to
	 * free component entries must do so explicitly via the relevant
	 * {@link ComponentArray#remove(int)} calls, or iterate over registered component
	 * arrays. Keeping this decoupled preserves the "just primitive arrays" goal.
	 */
	public void destroyEntity(long handle) {
		if (!isAlive(handle)) {
			return;
		}
		int index = index(handle);
		alive[index] = false;
		// Bump generation so any outstanding handles become stale. Wrap is fine:
		// with 32-bit generations the odds of a collision after 4 billion reuses
		// are astronomically low for a game server.
		generations[index]++;
		if (freeListSize >= freeList.length) {
			freeList = Arrays.copyOf(freeList, freeList.length * 2);
		}
		freeList[freeListSize++] = index;
		liveCount--;
	}

	/** Returns {@code true} if the handle still refers to a live entity. */
	public boolean isAlive(long handle) {
		if (handle == NULL) {
			return false;
		}
		int index = index(handle);
		if (index <= 0 || index >= highWater) {
			return false;
		}
		return alive[index] && generations[index] == generation(handle);
	}

	/** Number of currently alive entities. */
	public int size() {
		return liveCount;
	}

	/**
	 * Current slot capacity. Component arrays can use this as a hint when sizing
	 * their own backing stores, though they should grow on demand anyway.
	 */
	public int capacity() {
		return generations.length;
	}

	/**
	 * Number of slot indices that have ever been used. Iteration helpers use this
	 * as the upper bound for sparse-set scans.
	 */
	public int highWater() {
		return highWater;
	}

	/**
	 * Returns whether a raw slot index is live. Intended for iteration loops that
	 * scan {@code 1..highWater()} and want to skip dead slots without decoding a
	 * handle. Index 0 is always reported dead (it is the NULL sentinel).
	 */
	public boolean isIndexAlive(int index) {
		return index > 0 && index < highWater && alive[index];
	}

	/** Extracts the slot index from a packed handle. */
	public static int index(long handle) {
		return (int) (handle & 0xFFFFFFFFL);
	}

	/** Extracts the generation counter from a packed handle. */
	public static int generation(long handle) {
		return (int) (handle >>> 32);
	}

	/** Packs an (index, generation) pair into a handle. */
	public static long pack(int index, int generation) {
		return (((long) generation) << 32) | (index & 0xFFFFFFFFL);
	}

	private void grow(int minCapacity) {
		int newCap = generations.length;
		while (newCap < minCapacity) {
			newCap *= 2;
		}
		generations = Arrays.copyOf(generations, newCap);
		alive = Arrays.copyOf(alive, newCap);
	}
}
