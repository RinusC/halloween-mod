package dev.halloween.game;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * What a match leaves lying about in town: dropped things, bodies, townsfolk and officers, darts and thrown
 * stuff. "kill" commands only reach loaded chunks, and between matches everyone waits in the lobby far away,
 * so the town is unloaded and its leftovers would live on (still bodies, items, townsfolk with no mind left).
 * Every such entity gets its match's tag when it appears; when a chunk loads, whatever belongs to another match
 * goes. Untagged leftovers from before: townsfolk, officers and bodies go too; things dropped between matches stay.
 */
public final class Leftovers {
	private static final String PREFIX = "hw_m";
	private static final String FREE = PREFIX + "free";
	private static String current;
	private static final List<Entity> DOOMED = new ArrayList<>();

	private Leftovers() {
	}

	/** A match is being set up: from now on what appears belongs to it. */
	public static void begin(MinecraftServer server) {
		current = PREFIX + server.getTickCount();
	}

	public static void end() {
		current = null;
	}

	/** The tag for something made now: this match's, or (no match on: a sandbox kill, a test) one that is never tidied. */
	public static String stamp() {
		return current != null ? current : FREE;
	}

	private static boolean ours(Entity e) {
		if (e instanceof ItemEntity) {
			return true;
		}
		var tags = e.entityTags();
		return tags.contains(MatchManager.CORPSE_TAG) || tags.contains(Townsfolk.TAG) || tags.contains(Police.TAG) || tags.contains(Quake.TAG)
			|| tags.contains(Fight.DART_TAG) || tags.contains(Gear.THROW_TAG) || tags.contains(StreetWeapons.TAG);
	}

	public static void onLoad(Entity e, ServerLevel level) {
		if (!ours(e) || Lobby.contains(level.getServer(), e.position())) {
			return;
		}
		String mine = null;
		for (String t : e.entityTags()) {
			if (t.startsWith(PREFIX)) {
				mine = t;
			}
		}
		if (mine != null && (mine.equals(current) || mine.equals(FREE))) {
			return;
		}
		if (Townsfolk.registered(e) || Police.registered(e)) {
			// One this match put out, whose chunk is only loading now (they are made while the match is set up,
			// often far from anybody, and turn up when someone comes by): theirs, whatever the tags say.
			if (current != null && mine == null) {
				e.addTag(current);
			}
			return;
		}
		if (mine == null) {
			boolean townsperson = e.entityTags().contains(Townsfolk.TAG);
			// Townsfolk only appear while a match is set up: one turning up once it runs is an old one with no mind.
			if (current != null && !(townsperson && (Match.running() || Match.frozen()))) {
				e.addTag(current);
				return;
			}
			if (current == null && (e instanceof ItemEntity || e.entityTags().contains(Fight.DART_TAG) || e.entityTags().contains(Gear.THROW_TAG))) {
				// Dropped between matches (in the lobby or wherever): not ours to tidy.
				return;
			}
		}
		DOOMED.add(e);
	}

	/**
	 * Townsfolk and officers that nobody runs: whatever their tags say (one loaded while a match was being set up
	 * passes for new), a townsperson or officer the match doesn't know is a leftover with no mind, and goes.
	 */
	private static void sweep(MinecraftServer server) {
		net.minecraft.server.level.ServerLevel level = server.overworld();
		for (net.minecraft.world.entity.decoration.Mannequin m : level.getEntities(net.minecraft.world.entity.EntityTypes.MANNEQUIN,
			m -> m.entityTags().contains(Townsfolk.TAG) && !Townsfolk.registered(m) || m.entityTags().contains(Police.TAG) && !Police.registered(m))) {
			if (!Lobby.contains(server, m.position())) {
				DOOMED.add(m);
			}
		}
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 == 0) {
			sweep(server);
		}
		if (DOOMED.isEmpty()) {
			return;
		}
		for (Entity e : DOOMED) {
			if (!e.isRemoved()) {
				AiStats.add("*total", "leftover.removed");
				e.discard();
			}
		}
		DOOMED.clear();
	}
}
