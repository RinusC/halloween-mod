package dev.halloween.game;

import dev.halloween.Halloween;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/**
 * Where a slow server tick goes. The whole tick is timed (from its start to its end), and each part of ours
 * (MatchManager.tick's systems); a tick over {@link #SLOW_MS} is written to the log with the parts that took
 * longest, and what the rest of the game (entities, chunks, vanilla) took. For hunting freezes.
 */
public final class TickTimer {
	static final long SLOW_MS = 50;
	private static long tickStart;
	private static long partStart;
	private static final Map<String, Long> PARTS = new LinkedHashMap<>();
	/** Slow ticks seen so far (tests). */
	public static int slow;
	public static long worstMs;
	public static String worst = "";

	private TickTimer() {
	}

	public static void start(MinecraftServer server) {
		tickStart = System.nanoTime();
		PARTS.clear();
	}

	static void begin() {
		partStart = System.nanoTime();
	}

	static void lap(String name) {
		long now = System.nanoTime();
		PARTS.merge(name, now - partStart, Long::sum);
		partStart = now;
	}

	/** Adds time measured elsewhere. */
	static void add(String name, long nanos) {
		PARTS.merge(name, nanos, Long::sum);
	}

	/** Times one of our systems. */
	static void step(String name, Runnable r) {
		long t = System.nanoTime();
		r.run();
		PARTS.merge(name, System.nanoTime() - t, Long::sum);
	}

	public static void end(MinecraftServer server) {
		if (tickStart == 0) {
			return;
		}
		long total = (System.nanoTime() - tickStart) / 1_000_000;
		if (total < SLOW_MS) {
			return;
		}
		long ours = 0;
		for (Map.Entry<String, Long> e : PARTS.entrySet()) {
			// (Parts inside a part, "Bots.flee" in "Bots", are not counted twice.)
			if (!e.getKey().contains(".")) {
				ours += e.getValue();
			}
		}
		StringBuilder sb = new StringBuilder();
		PARTS.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(8)
			.forEach(e -> sb.append(' ').append(e.getKey()).append('=').append(e.getValue() / 1_000_000).append("ms"));
		String line = "Slow tick " + total + "ms (ours " + ours / 1_000_000 + "ms, rest " + (total - ours / 1_000_000) + "ms):" + sb;
		Halloween.LOGGER.warn(line);
		slow++;
		if (total > worstMs) {
			worstMs = total;
			worst = line;
		}
	}
}
