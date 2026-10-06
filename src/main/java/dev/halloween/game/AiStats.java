package dev.halloween.game;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.world.entity.Entity;

/** Counts what bots and townsfolk do (flee, hide, lock, get stuck...), for tuning and the AI simulation test. */
public final class AiStats {
	private static final Map<String, Map<String, Integer>> COUNTS = new TreeMap<>();

	private AiStats() {
	}

	public static void add(Entity who, String event) {
		add(who == null ? "?" : who.getPlainTextName(), event);
	}

	public static void add(String who, String event) {
		COUNTS.computeIfAbsent(who, k -> new TreeMap<>()).merge(event, 1, Integer::sum);
		COUNTS.computeIfAbsent("*total", k -> new TreeMap<>()).merge(event, 1, Integer::sum);
	}

	public static Map<String, Map<String, Integer>> snapshot() {
		Map<String, Map<String, Integer>> copy = new TreeMap<>();
		COUNTS.forEach((k, v) -> copy.put(k, new TreeMap<>(v)));
		return copy;
	}

	public static void reset() {
		COUNTS.clear();
	}
}
