package server.ecs;

/**
 * Single, process-wide ECS instance for the game server.
 *
 * <p>Ceres-J drives its game loop from a mix of static managers (PlayerManager,
 * ZoneManager, ItemManager...). The ECS is exposed the same way to keep the porting
 * burden low: code that wants to read or write components calls
 * {@link #world()} or {@link #components()} directly.
 *
 * <p>This is intentionally a singleton &mdash; there is exactly one game world per
 * process. Tests that want isolation should construct their own {@link World} +
 * {@link Components} pair instead of touching this registry.
 *
 * <p>Not a "system manager". There is no update loop here: the existing game loop
 * calls into ECS logic directly from the packet handlers and zone ticks.
 */
public final class EcsRegistry {

	private static final World WORLD = new World();
	private static final Components COMPONENTS = new Components();

	private EcsRegistry() { }

	public static World world() {
		return WORLD;
	}

	public static Components components() {
		return COMPONENTS;
	}
}
