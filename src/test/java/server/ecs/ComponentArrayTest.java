package server.ecs;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Sparse-set component storage for both reference-typed and primitive-int variants.
 */
public class ComponentArrayTest {

	@Test
	public void setAndGet() {
		ComponentArray<String> names = new ComponentArray<>();
		names.set(5, "alice");
		names.set(12, "bob");
		assertEquals("alice", names.get(5));
		assertEquals("bob", names.get(12));
		assertTrue(names.has(5));
		assertTrue(names.has(12));
		assertFalse(names.has(7));
	}

	@Test
	public void getMissingReturnsNull() {
		ComponentArray<String> names = new ComponentArray<>();
		assertNull(names.get(999));
		assertFalse(names.has(999));
	}

	@Test
	public void setReplacesExistingValue() {
		ComponentArray<String> names = new ComponentArray<>();
		names.set(5, "alice");
		names.set(5, "alice2");
		assertEquals("alice2", names.get(5));
		assertEquals(1, names.size());
	}

	@Test
	public void removeClearsComponent() {
		ComponentArray<String> names = new ComponentArray<>();
		names.set(5, "alice");
		names.set(12, "bob");
		names.remove(5);
		assertFalse(names.has(5));
		assertNull(names.get(5));
		// bob survives
		assertTrue(names.has(12));
		assertEquals("bob", names.get(12));
		assertEquals(1, names.size());
	}

	@Test
	public void removeMaintainsPackedDenseArray() {
		ComponentArray<String> names = new ComponentArray<>();
		names.set(1, "a");
		names.set(2, "b");
		names.set(3, "c");
		assertEquals(3, names.size());
		names.remove(1);
		assertEquals(2, names.size());
		// Iterate over the packed dense array and verify both remaining entries.
		boolean sawB = false, sawC = false;
		for (int i = 0; i < names.size(); i++) {
			int e = names.entityAt(i);
			String v = names.valueAt(i);
			if (e == 2 && "b".equals(v)) sawB = true;
			if (e == 3 && "c".equals(v)) sawC = true;
		}
		assertTrue(sawB);
		assertTrue(sawC);
	}

	@Test
	public void intComponentSetAndGet() {
		IntComponentArray health = new IntComponentArray();
		health.set(1, 100);
		health.set(2, 50);
		assertEquals(100, health.get(1));
		assertEquals(50, health.get(2));
		assertTrue(health.has(1));
		assertFalse(health.has(999));
	}

	@Test
	public void intComponentGetOrDefault() {
		IntComponentArray health = new IntComponentArray();
		assertEquals(-1, health.getOrDefault(999, -1));
		health.set(1, 100);
		assertEquals(100, health.getOrDefault(1, -1));
	}

	@Test(expected = IllegalStateException.class)
	public void intComponentGetMissingThrows() {
		IntComponentArray health = new IntComponentArray();
		health.get(999);
	}

	@Test
	public void intComponentRemove() {
		IntComponentArray health = new IntComponentArray();
		health.set(1, 100);
		health.set(2, 200);
		health.set(3, 300);
		health.remove(2);
		assertFalse(health.has(2));
		assertEquals(100, health.get(1));
		assertEquals(300, health.get(3));
		assertEquals(2, health.size());
	}

	@Test
	public void intComponentInPlaceUpdate() {
		IntComponentArray health = new IntComponentArray();
		health.set(1, 100);
		health.set(2, 200);
		// Iterate and subtract 10 from each
		for (int i = 0, n = health.size(); i < n; i++) {
			health.setValueAt(i, health.valueAt(i) - 10);
		}
		assertEquals(90, health.get(1));
		assertEquals(190, health.get(2));
	}

	@Test
	public void bulkIterationSmokeTest() {
		// Create 10k entities, set a component on each, iterate.
		final int N = 10_000;
		World w = new World(N);
		IntComponentArray health = new IntComponentArray(N);
		long[] handles = new long[N];
		for (int i = 0; i < N; i++) {
			handles[i] = w.createEntity();
			health.set(World.index(handles[i]), i * 2);
		}
		assertEquals(N, w.size());
		assertEquals(N, health.size());

		long sum = 0;
		long start = System.nanoTime();
		for (int i = 0, n = health.size(); i < n; i++) {
			sum += health.valueAt(i);
		}
		long elapsed = System.nanoTime() - start;

		// 10k sums of 0..19998 in steps of 2 = 2 * (0 + 1 + .. + 9999) = 9999 * 10000
		assertEquals(9999L * 10000L, sum);
		// Sanity: 10k iterations should take well under a second.
		assertTrue("iteration too slow: " + elapsed + "ns", elapsed < 1_000_000_000L);
	}

	@Test
	public void growsBeyondInitialCapacity() {
		ComponentArray<String> c = new ComponentArray<>(2);
		for (int i = 1; i <= 100; i++) {
			c.set(i, "e" + i);
		}
		assertEquals(100, c.size());
		for (int i = 1; i <= 100; i++) {
			assertEquals("e" + i, c.get(i));
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void nullComponentRejected() {
		ComponentArray<String> c = new ComponentArray<>();
		c.set(1, null);
	}
}
