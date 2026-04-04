package server.ecs;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Entity pool behavior: create/destroy/reuse with generation tracking.
 */
public class WorldTest {

	@Test
	public void createEntityReturnsLiveHandle() {
		World w = new World();
		long e = w.createEntity();
		assertTrue(w.isAlive(e));
		assertNotEquals(World.NULL, e);
		assertEquals(1, w.size());
	}

	@Test
	public void destroyEntityRemovesIt() {
		World w = new World();
		long e = w.createEntity();
		w.destroyEntity(e);
		assertFalse(w.isAlive(e));
		assertEquals(0, w.size());
	}

	@Test
	public void destroyedSlotIsRecycled() {
		World w = new World();
		long a = w.createEntity();
		w.destroyEntity(a);
		long b = w.createEntity();
		// Slot index should be reused
		assertEquals(World.index(a), World.index(b));
		// But generation must differ, so the old handle is stale
		assertNotEquals(World.generation(a), World.generation(b));
	}

	@Test
	public void staleHandleIsInvalidAfterReuse() {
		World w = new World();
		long a = w.createEntity();
		w.destroyEntity(a);
		long b = w.createEntity();
		assertFalse("old handle must be stale", w.isAlive(a));
		assertTrue("new handle must be alive", w.isAlive(b));
	}

	@Test
	public void nullHandleIsNeverAlive() {
		World w = new World();
		assertFalse(w.isAlive(World.NULL));
	}

	@Test
	public void destroyTwiceIsNoop() {
		World w = new World();
		long e = w.createEntity();
		w.destroyEntity(e);
		w.destroyEntity(e);
		assertEquals(0, w.size());
	}

	@Test
	public void sizeReflectsLiveEntities() {
		World w = new World();
		long a = w.createEntity();
		long b = w.createEntity();
		long c = w.createEntity();
		assertEquals(3, w.size());
		w.destroyEntity(b);
		assertEquals(2, w.size());
		// a and c still alive
		assertTrue(w.isAlive(a));
		assertFalse(w.isAlive(b));
		assertTrue(w.isAlive(c));
	}

	@Test
	public void growsBeyondInitialCapacity() {
		World w = new World(4);
		long[] es = new long[100];
		for (int i = 0; i < es.length; i++) {
			es[i] = w.createEntity();
		}
		for (long e : es) {
			assertTrue(w.isAlive(e));
		}
		assertEquals(100, w.size());
	}

	@Test
	public void packIndexGenerationRoundTrip() {
		long h = World.pack(12345, 7);
		assertEquals(12345, World.index(h));
		assertEquals(7, World.generation(h));
	}

	@Test
	public void slotZeroNeverAllocated() {
		World w = new World();
		for (int i = 0; i < 10; i++) {
			long e = w.createEntity();
			assertNotEquals("slot 0 must remain reserved for NULL", 0, World.index(e));
		}
	}

	@Test
	public void isIndexAliveMatchesHandleAlive() {
		World w = new World();
		long e = w.createEntity();
		assertTrue(w.isIndexAlive(World.index(e)));
		w.destroyEntity(e);
		assertFalse(w.isIndexAlive(World.index(e)));
	}

	@Test
	public void isIndexAliveRejectsZero() {
		World w = new World();
		assertFalse(w.isIndexAlive(0));
	}
}
