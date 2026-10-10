package dev.halloween.game;

import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Match events (DESIGN.md, "Ивенты"): now and then something happens in town that one side can use. The first
 * comes some minutes in, then one every few minutes, two or three a match, never two at once; each is announced
 * a few seconds ahead. The side that is behind gets the events that help it more often. In the last minutes
 * Midnight strikes: the survivors see every way out, and Michael sees everyone, once a minute.
 *
 * <ul>
 * <li>Loomis's call (survivors): a phone rings far from Michael; whoever answers sees him for a while and learns
 * where something for getting out lies.</li>
 * <li>A forgotten bag (survivors): out on the street, three good finds in it; everyone sees where.</li>
 * <li>Brackett's patrol (survivors): a police car drives the streets; Michael in its headlights raises the wanted
 * level; waved down, it leaves an officer to look after you (see PatrolCar).</li>
 * <li>Blackout (Michael): the whole town goes dark; a sparking transformer, two sets of wires, brings it back.</li>
 * <li>Samhain night (Michael): a red night; stalking goes twice as fast, Killer Sense doesn't tire, the Runt
 * pounces twice as often.</li>
 * <li>A party (both): music blasting from a house drowns footsteps, breathing and screams round it; the
 * townsfolk drift over.</li>
 * <li>A fire (both): a house burns; smoke hides what is inside, the townsfolk run out, and in there lies one
 * more thing for getting out.</li>
 * </ul>
 */
public final class Events {
	public enum Side {
		SURVIVORS, SHAPE, NEUTRAL
	}

	/** Who sees a marker or a glow: survivors, Michael's side (him and the Runt), or everyone. */
	static final int FOR_SURVIVORS = 1;
	static final int FOR_SHAPE = 2;
	static final int FOR_ALL = 3;

	/** A marker on the screens: where, what (HwNet.EventMark kinds), who sees it, until when (0: while the event runs). */
	record Mark(Vec3 pos, int kind, int viewers, int until) {
	}

	/** Someone shown through walls: whom, to whom, until when. */
	record Glow(int entityId, int viewers, int until) {
	}

	/** Something for a bot to go and do: where, how near, hold use or not, what, how much it matters. */
	public record BotJob(BlockPos pos, double within, boolean hold, String key, double value) {
	}

	/** One kind of event: what it needs, what it does, and what it shows. */
	abstract static class Event {
		final String id;
		final Side side;

		Event(String id, Side side) {
			this.id = id;
			this.side = side;
		}

		abstract int seconds();

		/** Picks its spot before the announcement; false if it can't happen now. */
		boolean prepare(ServerLevel level) {
			return true;
		}

		/** Where it happens, for the announcement (null: everywhere). */
		Vec3 where() {
			return null;
		}

		void start(ServerLevel level, int now) {
		}

		void tick(ServerLevel level, int now) {
		}

		void end(ServerLevel level) {
		}

		/** Over before its time (the bag is empty, the power is back). */
		boolean over() {
			return false;
		}

		Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
			return null;
		}

		void objects(List<BlockPos> out) {
		}

		void marks(List<Mark> out) {
		}

		void jobs(BotPlayer bot, List<BotJob> out) {
		}
	}

	private static final List<Event> EVENTS = new ArrayList<>();
	private static final List<String> USED = new ArrayList<>();
	/** Announced and about to start (at {@link #startAt}), or running (until {@link #endAt}). */
	private static Event pending;
	private static int startAt;
	private static Event current;
	private static int endAt;
	private static int beganAt;
	/** Match ticks (since it began) when the next random event is due. */
	private static int nextAt;
	private static int count;
	private static boolean midnight;
	private static int nextReveal;
	private static final List<Mark> LINGER = new ArrayList<>();
	private static final List<Glow> GLOWS = new ArrayList<>();
	/** Officers the patrol left behind and when they go back to it. */
	private static final Map<UUID, Integer> LENT = new LinkedHashMap<>();
	/** For tests: the next event to come, whatever the dice say. */
	public static String forced;

	private Events() {
	}

	// ---------------------------------------------------------------- the match

	/** A fresh set for a new match. */
	public static void setup(ServerLevel level) {
		clear(level.getServer());
		HwConfig cfg = HwConfig.get();
		EVENTS.add(new LoomisCall());
		EVENTS.add(new ForgottenBag());
		EVENTS.add(new Patrol());
		EVENTS.add(new Blackout());
		EVENTS.add(new Samhain());
		EVENTS.add(new Party());
		EVENTS.add(new Fire());
		nextAt = (cfg.eventFirstMinSeconds + level.getRandom().nextInt(Math.max(1, cfg.eventFirstMaxSeconds - cfg.eventFirstMinSeconds + 1))) * 20;
	}

	public static void clear(MinecraftServer server) {
		ServerLevel level = server.overworld();
		if (current != null) {
			current.end(level);
		}
		current = null;
		pending = null;
		EVENTS.clear();
		USED.clear();
		count = 0;
		midnight = false;
		LINGER.clear();
		GLOWS.clear();
		for (UUID id : LENT.keySet()) {
			Police.dismiss(id);
		}
		LENT.clear();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(p, HwNet.EventSync.NONE);
		}
	}

	private static int elapsed() {
		return HwConfig.get().matchSeconds * 20 - Match.ticksLeft();
	}

	public static void tick(MinecraftServer server) {
		if (!Match.running() || EVENTS.isEmpty()) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		int elapsed = elapsed();
		if (pending != null && now >= startAt) {
			current = pending;
			pending = null;
			beganAt = now;
			endAt = now + current.seconds() * 20;
			current.start(level, now);
			Match.chronicle(server, Component.translatable("halloween.event." + current.id + ".title").withStyle(ChatFormatting.LIGHT_PURPLE));
			AiStats.add("*total", "event." + current.id);
		}
		if (current != null) {
			current.tick(level, now);
			if (now >= endAt || current.over()) {
				current.end(level);
				Match.announce(server, Component.translatable("halloween.event.over", Component.translatable("halloween.event." + current.id + ".title"))
					.withStyle(ChatFormatting.GRAY));
				current = null;
				sync(server);
			}
		}
		if (cfg.eventsEnabled && pending == null && current == null && (forced != null || elapsed >= nextAt && count < cfg.eventMaxPerMatch
			&& Match.ticksLeft() >= (cfg.eventQuietEndSeconds + cfg.eventAnnounceSeconds) * 20)) {
			Event next = choose(level);
			nextAt = elapsed + (cfg.eventGapMinSeconds + level.getRandom().nextInt(Math.max(1, cfg.eventGapMaxSeconds - cfg.eventGapMinSeconds + 1))) * 20;
			if (next != null) {
				announce(level, next, now);
			}
		}
		if (cfg.midnightEnabled && !midnight && Match.ticksLeft() <= cfg.midnightSeconds * 20) {
			midnight(level, now);
		}
		if (midnight && now >= nextReveal) {
			nextReveal = now + cfg.midnightRevealEverySeconds * 20;
			for (ServerPlayer p : level.players()) {
				if (MatchManager.role(p).survivor() && !p.isSpectator() && !Exits.isSeated(p)) {
					GLOWS.add(new Glow(p.getId(), FOR_SHAPE, now + cfg.midnightRevealTicks));
				}
			}
			for (ServerPlayer p : level.players()) {
				if (MatchManager.role(p) == Role.SHAPE) {
					p.connection.send(sound(p, SoundEvents.BELL_RESONATE, 0.8f, 0.6f));
				}
			}
		}
		LINGER.removeIf(m -> m.until > 0 && m.until < now);
		GLOWS.removeIf(g -> g.until < now);
		Iterator<Map.Entry<UUID, Integer>> it = LENT.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Integer> e = it.next();
			if (now >= e.getValue()) {
				Police.dismiss(e.getKey());
				it.remove();
			}
		}
		if (now % 10 == 0) {
			sync(server);
		}
	}

	/** Which one comes: by weight, the side that is behind getting its own more often; not one twice a match. */
	private static Event choose(ServerLevel level) {
		HwConfig cfg = HwConfig.get();
		List<Event> pool = new ArrayList<>();
		for (Event e : EVENTS) {
			if (forced != null ? e.id.equals(forced) : !USED.contains(e.id) && cfg.eventWeights.getOrDefault(e.id, 0) > 0) {
				pool.add(e);
			}
		}
		forced = null;
		int lead = lead();
		while (!pool.isEmpty()) {
			double total = 0;
			double[] w = new double[pool.size()];
			for (int i = 0; i < pool.size(); i++) {
				Event e = pool.get(i);
				double weight = Math.max(1, cfg.eventWeights.getOrDefault(e.id, 1));
				if (lead >= 2 && e.side == Side.SURVIVORS || lead <= -2 && e.side == Side.SHAPE) {
					weight *= cfg.eventCatchUpFactor;
				}
				w[i] = weight;
				total += weight;
			}
			double roll = level.getRandom().nextDouble() * total;
			int pick = pool.size() - 1;
			for (int i = 0; i < w.length; i++) {
				roll -= w[i];
				if (roll < 0) {
					pick = i;
					break;
				}
			}
			Event e = pool.remove(pick);
			if (e.prepare(level)) {
				return e;
			}
		}
		return null;
	}

	/**
	 * Who is ahead: Michael by the survivors he has killed, the survivors by the ones who got away and each step
	 * towards a way out (a cellar opened, the car fuelled, mended or started, the gate's fuse in or the gate open,
	 * each call to the police). Positive: Michael is ahead.
	 */
	public static int lead() {
		int steps = Match.escaped() * 2 + Police.wanted();
		for (HwNet.ExitInfo e : Exits.infos()) {
			switch (e.type()) {
				case Exits.CELLAR -> steps += e.state() == Exits.OPEN || e.state() == Exits.USED ? 1 : 0;
				case Exits.CAR -> steps += Integer.bitCount(e.state() & (Exits.CAR_FUEL | Exits.CAR_REPAIRED | Exits.CAR_RUNNING));
				case Exits.GATE -> steps += (e.extra() != 0 ? 1 : 0) + (e.state() == Exits.OPEN || e.state() == Exits.USED ? 1 : 0);
				default -> {
				}
			}
		}
		return Match.deadSurvivors() * 2 - steps;
	}

	private static void announce(ServerLevel level, Event e, int now) {
		HwConfig cfg = HwConfig.get();
		pending = e;
		startAt = now + cfg.eventAnnounceSeconds * 20;
		USED.add(e.id);
		count++;
		Vec3 where = e.where();
		Component place = where == null ? Component.empty() : Component.literal(" — ").append(Landmarks.describe(level, where));
		Match.announce(level.getServer(), Component.translatable("halloween.event." + e.id + ".title").withStyle(ChatFormatting.LIGHT_PURPLE).append(place));
		for (ServerPlayer p : level.players()) {
			p.connection.send(sound(p, SoundEvents.NOTE_BLOCK_BELL, 0.7f, 0.5f));
		}
		sync(level.getServer());
	}

	/** Starts an event now (the command, tests): announced as usual. False if it can't happen now. */
	public static boolean trigger(ServerLevel level, String id) {
		if (!Match.running() || EVENTS.isEmpty()) {
			return false;
		}
		int now = level.getServer().getTickCount();
		if (current != null) {
			current.end(level);
			current = null;
		}
		for (Event e : EVENTS) {
			if (e.id.equals(id)) {
				if (!e.prepare(level)) {
					return false;
				}
				announce(level, e, now);
				return true;
			}
		}
		return false;
	}

	public static List<String> ids() {
		return List.of("loomis", "bag", "patrol", "blackout", "samhain", "party", "fire");
	}

	/** What runs now (tests): the event's id, or null. */
	public static String running() {
		return current == null ? null : current.id;
	}

	public static String announced() {
		return pending == null ? null : pending.id;
	}

	/** Tests: what can be used now (the phone, the bag, the transformer, the crate). */
	public static List<BlockPos> objects() {
		List<BlockPos> out = new ArrayList<>();
		if (current != null) {
			current.objects(out);
		}
		return out;
	}

	/** Tests: whom the survivors (or Michael's side) see through walls now. */
	public static List<Integer> glows(boolean survivors) {
		List<Integer> out = new ArrayList<>();
		for (Glow g : GLOWS) {
			if ((g.viewers & (survivors ? FOR_SURVIVORS : FOR_SHAPE)) != 0) {
				out.add(g.entityId);
			}
		}
		return out;
	}

	/** Tests: the patrol car's position, or null. */
	public static Vec3 patrolPosition() {
		return current instanceof Patrol p && p.car != null ? p.car.position() : null;
	}

	/** Tests: which way the patrol car is going, or null. */
	public static Vec3 patrolHeading() {
		return current instanceof Patrol p && p.car != null ? p.car.heading() : null;
	}

	/** Tests: the burning house (its breaker), or null. */
	public static BlockPos fireHouse() {
		return current instanceof Fire f ? f.house : null;
	}

	/** Tests: what holding use on {@code pos} would do for this player, done at once. True if there was something. */
	public static boolean finishFor(ServerPlayer player, BlockPos pos) {
		Holds.Hold hold = resolve(player, pos);
		if (hold == null || !hold.valid().test(player)) {
			return false;
		}
		hold.done().run(player);
		return true;
	}

	// ---------------------------------------------------------------- midnight

	/** The church bell: twelve o'clock. The survivors see every way out; Michael sees everyone once a minute. */
	private static void midnight(ServerLevel level, int now) {
		midnight = true;
		nextReveal = now + 60;
		Match.announce(level.getServer(), Component.translatable("halloween.event.midnight.title").withStyle(ChatFormatting.DARK_RED));
		Match.chronicle(level.getServer(), Component.translatable("halloween.event.midnight.title").withStyle(ChatFormatting.DARK_RED));
		for (ServerPlayer p : level.players()) {
			for (int i = 0; i < 3; i++) {
				p.connection.send(sound(p, SoundEvents.BELL_BLOCK, 1.2f, 0.5f + i * 0.02f));
			}
			Notices.send(p, Component.translatable(MatchManager.role(p).survivor() ? "halloween.event.midnight.survivors" : "halloween.event.midnight.shape")
				.withStyle(ChatFormatting.DARK_RED));
		}
		AiStats.add("*total", "event.midnight");
	}

	/** The ways out still open to someone, for the survivors' screens at midnight. */
	private static void exitMarks(List<Mark> out) {
		for (HwNet.ExitInfo e : Exits.infos()) {
			boolean gone = switch (e.type()) {
				case Exits.CELLAR -> e.state() == Exits.USED;
				case Exits.CAR -> (e.state() & Exits.CAR_GONE) != 0;
				case Exits.POLICE -> e.state() != Exits.POLICE_HERE;
				default -> e.state() == Exits.USED;
			};
			if (!gone && e.pos() != null) {
				out.add(new Mark(Vec3.atCenterOf(e.pos()), HwNet.EventMark.EXIT, FOR_SURVIVORS, 0));
			}
		}
	}

	// ---------------------------------------------------------------- what the screens show

	public static boolean samhain() {
		return current instanceof Samhain;
	}

	public static boolean blackout() {
		return current instanceof Blackout;
	}

	private static void sync(MinecraftServer server) {
		int now = server.getTickCount();
		List<Mark> marks = new ArrayList<>(LINGER);
		List<BlockPos> objects = new ArrayList<>();
		if (current != null) {
			current.marks(marks);
			current.objects(objects);
		}
		if (midnight) {
			exitMarks(marks);
		}
		Event shown = pending != null ? pending : current;
		int phase = pending != null ? 1 : current != null ? 2 : 0;
		int left = pending != null ? startAt - now : current != null ? endAt - now : 0;
		int total = pending != null ? HwConfig.get().eventAnnounceSeconds * 20 : current != null ? endAt - beganAt : 0;
		int flags = (samhain() ? HwNet.EventSync.SAMHAIN : 0) | (midnight ? HwNet.EventSync.MIDNIGHT : 0);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Role role = MatchManager.role(p);
			int me = role.survivor() ? FOR_SURVIVORS : role == Role.SHAPE || role == Role.RUNT ? FOR_SHAPE : 0;
			if (me == 0) {
				ServerPlayNetworking.send(p, HwNet.EventSync.NONE);
				continue;
			}
			List<HwNet.EventMark> mine = new ArrayList<>();
			for (Mark m : marks) {
				if ((m.viewers & me) != 0) {
					mine.add(new HwNet.EventMark(m.pos, m.kind));
				}
			}
			List<Integer> glows = new ArrayList<>();
			for (Glow g : GLOWS) {
				if ((g.viewers & me) != 0) {
					glows.add(g.entityId);
				}
			}
			ServerPlayNetworking.send(p, new HwNet.EventSync(shown == null ? "" : shown.id, phase, Math.max(0, left), Math.max(1, total), mine, glows,
				role.survivor() ? objects : List.of(), flags));
		}
	}

	// ---------------------------------------------------------------- hooks from elsewhere

	/** Hold use on an event's object (a ringing phone, the bag, the transformer, the crate in the fire). */
	static Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
		return current == null ? null : current.resolve(player, pos);
	}

	/** The wires of a panel joined: the transformer's? True if it was (and handled). */
	static boolean wired(ServerPlayer player, BlockPos pos) {
		return current instanceof Blackout b && b.wired(player, pos);
	}

	/** Use on an entity: waving the patrol car down. */
	public static InteractionResult onUseEntity(net.minecraft.world.entity.player.Player player, Entity entity) {
		if (!(player instanceof ServerPlayer sp) || !(current instanceof Patrol patrol) || patrol.car == null || !patrol.car.isMine(entity)) {
			return InteractionResult.PASS;
		}
		patrol.waveDown(sp);
		return InteractionResult.SUCCESS;
	}

	/** Stalking goes this much faster (Samhain night). */
	public static float stalkFactor() {
		return samhain() ? HwConfig.get().samhainStalkFactor : 1f;
	}

	/** Killer Sense doesn't tire (Samhain night). */
	public static boolean noFatigue() {
		return samhain();
	}

	/** The Runt's pause between pounces is this much of its usual (Samhain night). */
	public static float pounceFactor() {
		return samhain() ? HwConfig.get().samhainPounceFactor : 1f;
	}

	/** The party's music drowns everything near it: footsteps, breathing, screams. */
	public static boolean muffled(Vec3 pos) {
		return current instanceof Party party && party.house != null && Vec3.atCenterOf(party.house).distanceTo(pos) <= HwConfig.get().partyMuffleRange;
	}

	/** Smoke from a burning house between two points: nothing seen through it (close up still). */
	public static boolean smokeBetween(Vec3 a, Vec3 b) {
		if (!(current instanceof Fire fire) || fire.smoke == null) {
			return false;
		}
		double len = a.distanceTo(b);
		if (len < 4) {
			return false;
		}
		Vec3 d = b.subtract(a).scale(1 / len);
		double t = Math.max(0, Math.min(len, fire.smoke.subtract(a).dot(d)));
		return a.add(d.scale(t)).distanceTo(fire.smoke) < HwConfig.get().fireSmokeRadius;
	}

	/** The fire hurts but never kills: whoever would die of it just gets out of it badly burnt. */
	public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!source.is(DamageTypeTags.IS_FIRE) || !MatchManager.isVictim(entity) && !(entity instanceof ServerPlayer)) {
			return true;
		}
		if (entity.getHealth() - amount < 2f) {
			entity.clearFire();
			return false;
		}
		return true;
	}

	/** Jobs for bots this event offers (the phone, the bag, the transformer, the crate in the fire). */
	public static List<BotJob> botJobs(BotPlayer bot) {
		List<BotJob> out = new ArrayList<>();
		if (current != null) {
			current.jobs(bot, out);
		}
		return out;
	}

	/** Townsfolk drift over to the party: where it is (null if none). */
	public static BlockPos partyHouse() {
		return current instanceof Party party ? party.house : null;
	}

	// ---------------------------------------------------------------- helpers

	private static ClientboundSoundPacket sound(ServerPlayer p, Holder<SoundEvent> s, float volume, float pitch) {
		return new ClientboundSoundPacket(s, SoundSource.MASTER, p.getX(), p.getEyeY(), p.getZ(), volume, pitch, p.getRandom().nextLong());
	}

	private static ClientboundSoundPacket sound(ServerPlayer p, SoundEvent s, float volume, float pitch) {
		return sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(s), volume, pitch);
	}

	private static List<ServerPlayer> shapes(ServerLevel level) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE && !p.isSpectator()) {
				out.add(p);
			}
		}
		return out;
	}

	private static double nearestShape(ServerLevel level, Vec3 at) {
		double d = 1000;
		for (ServerPlayer s : shapes(level)) {
			d = Math.min(d, s.position().distanceTo(at));
		}
		return d;
	}

	private static double nearestSurvivor(ServerLevel level, Vec3 at) {
		double d = 1000;
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator()) {
				d = Math.min(d, p.position().distanceTo(at));
			}
		}
		return d;
	}

	/** A spot out on the street or in a yard (open sky, room to stand), not too near Michael. */
	private static BlockPos outdoorSpot(ServerLevel level, double fromMichael) {
		BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
		if (center == null) {
			return null;
		}
		int r = (int) (Loot.radius() * 0.7);
		for (int i = 0; i < 80; i++) {
			BlockPos p = Nav.snap(level, center.offset(level.getRandom().nextInt(2 * r + 1) - r, 0, level.getRandom().nextInt(2 * r + 1) - r));
			if (Nav.standable(level, p) && level.canSeeSky(p) && level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
				&& nearestShape(level, Vec3.atBottomCenterOf(p)) >= fromMichael) {
				return p;
			}
		}
		return null;
	}

	private static List<BlockPos> breakers(ServerLevel level) {
		BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
		return center == null ? List.of() : LightSystem.breakers(level, center, Loot.radius());
	}

	private static int jobsNear(BotPlayer bot, String key) {
		return Bots.othersOn(key, bot) ? 1 : 0;
	}

	// ================================================================ the events

	/** Dr Loomis rings: a phone far from Michael, answered by a survivor, says where he is and where to look. */
	static final class LoomisCall extends Event {
		BlockPos phone;
		boolean answered;
		boolean dead;

		LoomisCall() {
			super("loomis", Side.SURVIVORS);
		}

		@Override
		int seconds() {
			return HwConfig.get().loomisSeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			List<BlockPos> phones = new ArrayList<>();
			for (BlockPos p : MapPoints.get(level).list(MapPoints.Type.PHONE)) {
				if (Exits.phoneWorks(p) && nearestShape(level, Vec3.atCenterOf(p)) >= HwConfig.get().loomisMinDistance) {
					phones.add(p);
				}
			}
			if (phones.isEmpty()) {
				return false;
			}
			this.phone = phones.get(level.getRandom().nextInt(phones.size()));
			this.answered = false;
			this.dead = false;
			return true;
		}

		@Override
		Vec3 where() {
			return Vec3.atCenterOf(this.phone);
		}

		@Override
		void tick(ServerLevel level, int now) {
			if (!Exits.phoneWorks(this.phone)) {
				// He cut the line: the ringing stops.
				this.dead = true;
				return;
			}
			int t = now % 40;
			if (t == 0 || t == 5) {
				// Brrring: a bell, twice, quick.
				level.playSound(null, this.phone, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 2f, 1.9f);
				level.playSound(null, this.phone, SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.BLOCKS, 2f, 1.6f);
			}
		}

		@Override
		boolean over() {
			return this.answered || this.dead;
		}

		@Override
		Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
			if (!pos.equals(this.phone) || !MatchManager.role(player).survivor() || this.answered) {
				return null;
			}
			HwConfig cfg = HwConfig.get();
			return new Holds.Hold(this.phone, "halloween.hud.progress.loomis", false, cfg.loomisAnswerTicks,
				p -> !this.answered && Exits.phoneWorks(this.phone), this::answer, true);
		}

		/** "He's near the ...": everyone on the survivors' side sees Michael for a while, and where to look. */
		private void answer(ServerPlayer p) {
			HwConfig cfg = HwConfig.get();
			ServerLevel level = p.level();
			int now = level.getServer().getTickCount();
			this.answered = true;
			for (ServerPlayer shape : shapes(level)) {
				GLOWS.add(new Glow(shape.getId(), FOR_SURVIVORS, now + cfg.loomisRevealTicks));
				Notices.send(shape, Component.translatable("halloween.event.loomis.shape").withStyle(ChatFormatting.DARK_RED));
			}
			// Something for getting out, nearest the phone, still hidden.
			BlockPos item = null;
			HwItems.Kind kind = null;
			for (Map.Entry<BlockPos, HwItems.Kind> e : Loot.hidden().entrySet()) {
				if (e.getValue().escape() && (item == null || e.getKey().distSqr(this.phone) < item.distSqr(this.phone))) {
					item = e.getKey();
					kind = e.getValue();
				}
			}
			if (item != null) {
				LINGER.add(new Mark(Vec3.atCenterOf(item), HwNet.EventMark.ESCAPE_ITEM, FOR_SURVIVORS, now + cfg.loomisItemMarkTicks));
			}
			Component told = item == null ? Component.translatable("halloween.event.loomis.nothing")
				: Component.translatable("halloween.event.loomis.item", Component.translatable("halloween.item." + kind.key()));
			for (ServerPlayer s : level.players()) {
				if (MatchManager.role(s).survivor()) {
					Notices.send(s, Component.translatable("halloween.event.loomis.answered", p.getDisplayName()).withStyle(ChatFormatting.AQUA));
					Notices.send(s, told.copy().withStyle(ChatFormatting.AQUA));
				}
			}
			Match.score(p, cfg.scoreEvent, Component.translatable("halloween.score.event.loomis"));
			AiStats.add(p, "event.loomis.answered");
		}

		@Override
		void objects(List<BlockPos> out) {
			out.add(this.phone);
		}

		@Override
		void marks(List<Mark> out) {
			out.add(new Mark(Vec3.atCenterOf(this.phone), HwNet.EventMark.PHONE, FOR_SURVIVORS, 0));
		}

		@Override
		void jobs(BotPlayer bot, List<BotJob> out) {
			double d = bot.position().distanceTo(Vec3.atCenterOf(this.phone));
			if (d < 70 && jobsNear(bot, "loomis") == 0) {
				out.add(new BotJob(this.phone, 2.0, true, "loomis", 75 - d * 0.3));
			}
		}
	}

	/** A bag somebody left in the street: three good finds in it, and everyone knows where. */
	static final class ForgottenBag extends Event {
		BlockPos spot;
		BlockState before;
		final List<HwItems.Kind> inside = new ArrayList<>();
		boolean taken;

		ForgottenBag() {
			super("bag", Side.SURVIVORS);
		}

		@Override
		int seconds() {
			return HwConfig.get().bagSeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			this.spot = outdoorSpot(level, HwConfig.get().bagMinDistance);
			return this.spot != null;
		}

		@Override
		Vec3 where() {
			return Vec3.atCenterOf(this.spot);
		}

		@Override
		void start(ServerLevel level, int now) {
			HwConfig cfg = HwConfig.get();
			java.util.Random rng = new java.util.Random(level.getRandom().nextLong());
			this.inside.clear();
			this.taken = false;
			this.inside.add(pick(cfg.bagRare, rng));
			for (int i = 0; i < 2; i++) {
				this.inside.add(pick(cfg.bagUncommon, rng));
			}
			this.inside.removeIf(java.util.Objects::isNull);
			this.before = level.getBlockState(this.spot);
			level.setBlock(this.spot, HwBlocks.CARDBOARD_BOX.defaultBlockState()
				.setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.getRandom())), 3);
			level.playSound(null, this.spot, SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.BLOCKS, 1f, 0.8f);
		}

		private static HwItems.Kind pick(String[] keys, java.util.Random rng) {
			if (keys == null || keys.length == 0) {
				return null;
			}
			String key = keys[rng.nextInt(keys.length)];
			for (HwItems.Kind k : HwItems.Kind.values()) {
				if (k.key().equals(key)) {
					return k;
				}
			}
			return null;
		}

		@Override
		void end(ServerLevel level) {
			if (this.spot != null && level.getBlockState(this.spot).is(HwBlocks.CARDBOARD_BOX)) {
				level.setBlock(this.spot, this.before == null ? Blocks.AIR.defaultBlockState() : this.before, 3);
			}
		}

		@Override
		boolean over() {
			return this.taken;
		}

		@Override
		Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
			if (!pos.equals(this.spot) || !MatchManager.role(player).survivor() || this.taken) {
				return null;
			}
			return new Holds.Hold(this.spot, "halloween.hud.progress.search", false, HwConfig.get().bagSearchTicks, p -> !this.taken, p -> {
				this.taken = true;
				List<Component> names = new ArrayList<>();
				for (HwItems.Kind k : this.inside) {
					Inventory.give(p, Loot.found(k));
					names.add(Component.translatable("halloween.item." + k.key()));
				}
				p.level().playSound(null, this.spot, SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.BLOCKS, 1f, 1.1f);
				Component list = names.isEmpty() ? Component.empty() : names.getFirst().copy();
				for (int i = 1; i < names.size(); i++) {
					list = list.copy().append(", ").append(names.get(i));
				}
				Notices.send(p, Component.translatable("halloween.event.bag.found", list).withStyle(ChatFormatting.GOLD));
				Match.score(p, HwConfig.get().scoreEvent, Component.translatable("halloween.score.event.bag"));
				AiStats.add(p, "event.bag.taken");
			});
		}

		@Override
		void objects(List<BlockPos> out) {
			out.add(this.spot);
		}

		@Override
		void marks(List<Mark> out) {
			out.add(new Mark(Vec3.atCenterOf(this.spot), HwNet.EventMark.BAG, FOR_ALL, 0));
		}

		@Override
		void jobs(BotPlayer bot, List<BotJob> out) {
			double d = bot.position().distanceTo(Vec3.atCenterOf(this.spot));
			if (d < 80 && Inventory.hasRoomForEscape(bot)) {
				out.add(new BotJob(this.spot, 2.0, true, "bag", 60 - d * 0.25));
			}
		}
	}

	/** Brackett's patrol: a police car on the streets (see PatrolCar). */
	static final class Patrol extends Event {
		PatrolCar.Roads roads;
		PatrolCar car;
		final List<UUID> waved = new ArrayList<>();

		Patrol() {
			super("patrol", Side.SURVIVORS);
		}

		@Override
		int seconds() {
			return HwConfig.get().patrolSeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			if (Exits.policePoint() == null) {
				return false;
			}
			if (this.roads == null) {
				this.roads = PatrolCar.find(level);
			}
			return this.roads != null;
		}

		@Override
		void start(ServerLevel level, int now) {
			this.waved.clear();
			this.car = PatrolCar.spawn(level, this.roads);
		}

		@Override
		void tick(ServerLevel level, int now) {
			HwConfig cfg = HwConfig.get();
			if (this.car == null) {
				return;
			}
			this.car.tick();
			if (!this.car.sawMichael && now % 5 == 0) {
				ServerPlayer michael = this.car.michaelInLights();
				if (michael != null) {
					this.car.sawMichael = true;
					level.playSound(null, michael.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2f, 1.4f);
					Notices.send(michael, Component.translatable("halloween.event.patrol.spotted_you").withStyle(ChatFormatting.DARK_RED));
					if (Police.raiseWanted(level, Component.translatable("halloween.event.patrol.spotted"))) {
						AiStats.add(michael, "event.patrol.spotted");
					}
				}
			}
			// A frightened bot by the road waves it down.
			if (now % 10 == 0) {
				for (ServerPlayer p : level.players()) {
					if (p instanceof BotPlayer bot && MatchManager.role(bot).survivor() && !bot.isSpectator() && Panic.value(bot) > cfg.patrolBotPanic
						&& bot.position().distanceTo(this.car.position()) < 5) {
						this.waveDown(bot);
					}
				}
			}
		}

		/** Waved down: it pulls up and an officer gets out to look after them for a while. */
		void waveDown(ServerPlayer p) {
			HwConfig cfg = HwConfig.get();
			if (this.car == null || !MatchManager.role(p).survivor() || p.isSpectator() || this.waved.contains(p.getUUID())
				|| this.waved.size() >= cfg.patrolMaxStops || p.position().distanceTo(this.car.position()) > 6) {
				return;
			}
			ServerLevel level = p.level();
			Vec3 out = this.car.pullUp();
			if (out == null) {
				return;
			}
			this.waved.add(p.getUUID());
			var officer = Police.spawnOfficer(level, out);
			if (officer == null) {
				return;
			}
			Police.guard(officer, p);
			LENT.put(officer.getUUID(), level.getServer().getTickCount() + cfg.patrolGuardSeconds * 20);
			level.playSound(null, out.x, out.y, out.z, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8f, 1.3f);
			Notices.send(p, Component.translatable("halloween.event.patrol.guarded", cfg.patrolGuardSeconds).withStyle(ChatFormatting.BLUE));
			Match.score(p, cfg.scoreEvent / 2, Component.translatable("halloween.score.event.patrol"));
			AiStats.add(p, "event.patrol.waved");
		}

		@Override
		void end(ServerLevel level) {
			if (this.car != null) {
				this.car.remove();
				this.car = null;
			}
		}

		@Override
		void marks(List<Mark> out) {
			if (this.car != null) {
				out.add(new Mark(this.car.position().add(0, 1, 0), HwNet.EventMark.PATROL, FOR_SURVIVORS, 0));
			}
		}
	}

	/** The whole town goes dark; a sparking transformer in the street, two sets of wires, brings the power back. */
	static final class Blackout extends Event {
		final List<BlockPos> wasOn = new ArrayList<>();
		final List<BlockPos> lamps = new ArrayList<>();
		BlockPos box;
		final Map<BlockPos, BlockState> before = new HashMap<>();
		int stage;
		boolean fixed;

		Blackout() {
			super("blackout", Side.SHAPE);
		}

		@Override
		int seconds() {
			return HwConfig.get().blackoutSeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			this.box = outdoorSpot(level, HwConfig.get().blackoutBoxMinDistance);
			return this.box != null;
		}

		@Override
		Vec3 where() {
			return Vec3.atCenterOf(this.box);
		}

		@Override
		void start(ServerLevel level, int now) {
			this.stage = 0;
			this.fixed = false;
			this.wasOn.clear();
			for (BlockPos b : breakers(level)) {
				if (LightSystem.isOn(level, b)) {
					this.wasOn.add(b);
					BlockState s = level.getBlockState(b);
					level.setBlock(b, s.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, false), Block.UPDATE_CLIENTS);
					LightSystem.setLights(level, b, false);
				}
			}
			this.lamps.clear();
			lanterns(level, this.lamps, true);
			this.before.clear();
			this.before.put(this.box, level.getBlockState(this.box));
			this.before.put(this.box.above(), level.getBlockState(this.box.above()));
			level.setBlock(this.box, Blocks.IRON_BLOCK.defaultBlockState(), 3);
			level.setBlock(this.box.above(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.withDefaultNamespace("lightning_rod")).defaultBlockState(), 3);
			for (ServerPlayer p : level.players()) {
				p.connection.send(sound(p, SoundEvents.BEACON_DEACTIVATE, 1f, 0.5f));
			}
		}

		/** Street lanterns out (dead ones in their place) or back on. */
		private static void lanterns(ServerLevel level, List<BlockPos> list, boolean off) {
			if (!off) {
				for (BlockPos p : list) {
					BlockState s = level.getBlockState(p);
					if (s.is(HwBlocks.DEAD_LANTERN)) {
						level.setBlock(p, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, s.getValue(LanternBlock.HANGING)), Block.UPDATE_CLIENTS);
					}
				}
				return;
			}
			BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
			if (center == null) {
				return;
			}
			int radius = Loot.radius();
			for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
				for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
					var chunk = level.getChunk(cx, cz);
					for (int i = 0; i < chunk.getSections().length; i++) {
						var section = chunk.getSections()[i];
						if (section.hasOnlyAir() || !section.maybeHas(s -> s.is(Blocks.LANTERN))) {
							continue;
						}
						int y0 = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
						for (int ly = 0; ly < 16; ly++) {
							for (int lz = 0; lz < 16; lz++) {
								for (int lx = 0; lx < 16; lx++) {
									BlockState s = section.getBlockState(lx, ly, lz);
									if (s.is(Blocks.LANTERN)) {
										BlockPos p = new BlockPos(chunk.getPos().getMinBlockX() + lx, y0 + ly, chunk.getPos().getMinBlockZ() + lz);
										level.setBlock(p, HwBlocks.DEAD_LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, s.getValue(LanternBlock.HANGING)),
											Block.UPDATE_CLIENTS);
										list.add(p);
									}
								}
							}
						}
					}
				}
			}
		}

		@Override
		void tick(ServerLevel level, int now) {
			if (now % 7 == 0) {
				Vec3 c = Vec3.atCenterOf(this.box.above());
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, c.x, c.y + 0.3, c.z, 6, 0.3, 0.3, 0.3, 0.2);
			}
			if (now % 23 == 0) {
				level.playSound(null, this.box, SoundEvents.REDSTONE_TORCH_BURNOUT, SoundSource.BLOCKS, 0.8f, 1.6f);
			}
		}

		@Override
		boolean over() {
			return this.fixed;
		}

		@Override
		Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
			if (!this.isBox(pos) || !MatchManager.role(player).survivor() || this.fixed) {
				return null;
			}
			if (!Bots.isBot(player)) {
				// The wires by hand: two panels, one after the other.
				Minigames.open(player, HwNet.Minigame.WIRES, this.stage == 0 ? this.box : this.box.above());
				return null;
			}
			return new Holds.Hold(this.box, "halloween.hud.progress.wires", false, HwConfig.get().botWiresTicks * 2, p -> !this.fixed, p -> {
				this.stage = 2;
				this.restored(p);
			});
		}

		private boolean isBox(BlockPos pos) {
			return pos.equals(this.box) || pos.equals(this.box.above());
		}

		boolean wired(ServerPlayer player, BlockPos pos) {
			if (!this.isBox(pos) || this.fixed) {
				return false;
			}
			if (this.stage == 0 && pos.equals(this.box)) {
				this.stage = 1;
				player.level().playSound(null, this.box, SoundEvents.COPPER_BULB_TURN_ON, SoundSource.BLOCKS, 1f, 0.8f);
				Notices.send(player, Component.translatable("halloween.event.blackout.half").withStyle(ChatFormatting.YELLOW));
				Minigames.open(player, HwNet.Minigame.WIRES, this.box.above());
			} else if (this.stage == 1 && pos.equals(this.box.above())) {
				this.stage = 2;
				this.restored(player);
			}
			return true;
		}

		private void restored(ServerPlayer p) {
			this.fixed = true;
			Match.announce(p.level().getServer(), Component.translatable("halloween.event.blackout.fixed", p.getDisplayName()).withStyle(ChatFormatting.YELLOW));
			Match.score(p, HwConfig.get().scoreEvent, Component.translatable("halloween.score.event.blackout"));
			AiStats.add(p, "event.blackout.fixed");
		}

		@Override
		void end(ServerLevel level) {
			for (BlockPos b : this.wasOn) {
				if (LightSystem.isBreaker(level, b) && !LightSystem.isOn(level, b)) {
					BlockState s = level.getBlockState(b);
					level.setBlock(b, s.setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true), Block.UPDATE_CLIENTS);
					LightSystem.setLights(level, b, true);
				}
			}
			this.wasOn.clear();
			lanterns(level, this.lamps, false);
			this.lamps.clear();
			this.before.forEach((pos, state) -> level.setBlock(pos, state, 3));
			this.before.clear();
			for (ServerPlayer p : level.players()) {
				p.connection.send(sound(p, SoundEvents.BEACON_ACTIVATE, 1f, 0.7f));
			}
		}

		@Override
		void objects(List<BlockPos> out) {
			out.add(this.box);
			out.add(this.box.above());
		}

		@Override
		void marks(List<Mark> out) {
			out.add(new Mark(Vec3.atCenterOf(this.box.above()), HwNet.EventMark.TRANSFORMER, FOR_ALL, 0));
		}

		@Override
		void jobs(BotPlayer bot, List<BotJob> out) {
			double d = bot.position().distanceTo(Vec3.atCenterOf(this.box));
			if (d < 90 && jobsNear(bot, "transformer") == 0) {
				out.add(new BotJob(this.box, 2.0, true, "transformer", 70 - d * 0.2));
			}
		}
	}

	/** Samhain night: a red night; Michael stalks twice as fast and his Sense doesn't tire; the Runt pounces more. */
	static final class Samhain extends Event {
		Samhain() {
			super("samhain", Side.SHAPE);
		}

		@Override
		int seconds() {
			return HwConfig.get().samhainSeconds;
		}

		@Override
		void start(ServerLevel level, int now) {
			for (ServerPlayer p : level.players()) {
				p.connection.send(sound(p, SoundEvents.WITHER_AMBIENT, 0.5f, 0.5f));
			}
		}
	}

	/** A party: music from a house drowns everything round it; the townsfolk drift over. */
	static final class Party extends Event {
		BlockPos house;
		Holder<SoundEvent> music;

		Party() {
			super("party", Side.NEUTRAL);
		}

		@Override
		int seconds() {
			return HwConfig.get().partySeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			List<BlockPos> houses = new ArrayList<>(breakers(level));
			BlockPos center = MapPoints.get(level).first(MapPoints.Type.CENTER);
			if (center != null) {
				houses.removeIf(b -> b.distSqr(center) > (double) Loot.radius() * Loot.radius() * 0.5);
			}
			houses.removeIf(b -> current instanceof Fire f && f.house != null && f.house.closerThan(b, 20));
			if (houses.isEmpty()) {
				return false;
			}
			this.house = houses.get(level.getRandom().nextInt(houses.size()));
			return true;
		}

		@Override
		Vec3 where() {
			return Vec3.atCenterOf(this.house);
		}

		@Override
		void start(ServerLevel level, int now) {
			if (LightSystem.isBreaker(level, this.house) && !LightSystem.isOn(level, this.house)) {
				LightSystem.flip(level, this.house, true);
			}
			List<Holder<SoundEvent>> songs = List.of(SoundEvents.MUSIC_DISC_CHIRP, SoundEvents.MUSIC_DISC_MALL, SoundEvents.MUSIC_DISC_CAT, SoundEvents.MUSIC_DISC_BLOCKS);
			this.music = songs.get(level.getRandom().nextInt(songs.size()));
			level.playSound(null, this.house.getX() + 0.5, this.house.getY() + 1.0, this.house.getZ() + 0.5, this.music, SoundSource.RECORDS, 4f, 1f);
			Townsfolk.gather(this.house, HwConfig.get().partyGatherRange, HwConfig.get().partyGatherShare, level.getRandom());
		}

		@Override
		void tick(ServerLevel level, int now) {
			if (now % 10 == 0) {
				Vec3 c = Vec3.atCenterOf(this.house);
				level.sendParticles(ParticleTypes.NOTE, c.x, c.y + 1.5, c.z, 3, 3, 1.5, 3, 1);
			}
		}

		@Override
		void end(ServerLevel level) {
			if (this.music != null) {
				var id = this.music.unwrapKey().map(k -> k.identifier()).orElse(null);
				for (ServerPlayer p : level.players()) {
					p.connection.send(new ClientboundStopSoundPacket(id, SoundSource.RECORDS));
				}
			}
			Townsfolk.disperse();
		}

		@Override
		void marks(List<Mark> out) {
			out.add(new Mark(Vec3.atCenterOf(this.house), HwNet.EventMark.PARTY, FOR_ALL, 0));
		}
	}

	/** A house on fire: light, smoke that hides what is inside, townsfolk running out, one more thing for getting out. */
	static final class Fire extends Event {
		BlockPos house;
		Vec3 smoke;
		final List<BlockPos> fires = new ArrayList<>();
		final List<BlockPos> roof = new ArrayList<>();
		BlockPos crate;
		BlockState crateBefore;
		HwItems.Kind prize;
		boolean prizeTaken;

		Fire() {
			super("fire", Side.NEUTRAL);
		}

		@Override
		int seconds() {
			return HwConfig.get().fireSeconds;
		}

		@Override
		boolean prepare(ServerLevel level) {
			List<BlockPos> houses = new ArrayList<>(breakers(level));
			houses.removeIf(b -> nearestSurvivor(level, Vec3.atCenterOf(b)) < 12 || nearestShape(level, Vec3.atCenterOf(b)) < 10);
			java.util.Collections.shuffle(houses, new java.util.Random(level.getRandom().nextLong()));
			for (BlockPos b : houses) {
				if (this.interior(level, b).size() >= 8) {
					this.house = b;
					return true;
				}
			}
			return false;
		}

		@Override
		Vec3 where() {
			return Vec3.atCenterOf(this.house);
		}

		/** Floor spots under the house's roof round its breaker: room to stand, no door beside. */
		private List<BlockPos> interior(ServerLevel level, BlockPos b) {
			List<BlockPos> out = new ArrayList<>();
			for (BlockPos p : BlockPos.betweenClosed(b.offset(-7, -3, -7), b.offset(7, 6, 7))) {
				if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir() || level.canSeeSky(p)
					|| !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
					continue;
				}
				boolean door = false;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					door |= level.getBlockState(p.relative(d)).getBlock() instanceof DoorBlock;
				}
				if (!door) {
					out.add(p.immutable());
				}
			}
			return out;
		}

		@Override
		void start(ServerLevel level, int now) {
			HwConfig cfg = HwConfig.get();
			var random = level.getRandom();
			this.fires.clear();
			this.roof.clear();
			this.prizeTaken = false;
			this.smoke = Vec3.atCenterOf(this.house).add(0, 2, 0);
			// Flames on the roof.
			List<BlockPos> tops = new ArrayList<>();
			for (int dx = -7; dx <= 7; dx++) {
				for (int dz = -7; dz <= 7; dz++) {
					BlockPos col = this.house.offset(dx, 1, dz);
					if (level.canSeeSky(col)) {
						continue;
					}
					for (int y = this.house.getY() + 16; y > this.house.getY(); y--) {
						BlockPos p = new BlockPos(col.getX(), y, col.getZ());
						if (!level.getBlockState(p).isAir()) {
							if (level.getBlockState(p.above()).isAir()) {
								tops.add(p.above());
							}
							break;
						}
					}
				}
			}
			java.util.Collections.shuffle(tops, new java.util.Random(random.nextLong()));
			for (BlockPos p : tops) {
				if (this.roof.size() >= cfg.fireRoofFlames) {
					break;
				}
				if (this.light(level, p)) {
					this.roof.add(p);
				}
			}
			// Flames inside, and the crate with something for getting out.
			List<BlockPos> floor = this.interior(level, this.house);
			java.util.Collections.shuffle(floor, new java.util.Random(random.nextLong()));
			this.crate = null;
			for (BlockPos p : floor) {
				if (this.crate == null && p.distSqr(this.house) >= 4) {
					this.crate = p;
					continue;
				}
				if (this.fires.size() - this.roof.size() >= cfg.fireInsideFlames) {
					break;
				}
				if (this.crate != null && p.closerThan(this.crate, 2)) {
					continue;
				}
				this.light(level, p);
			}
			this.prize = this.prize(level);
			if (this.crate != null && this.prize != null) {
				this.crateBefore = level.getBlockState(this.crate);
				level.setBlock(this.crate, HwBlocks.CRATE.defaultBlockState(), 3);
			} else {
				this.crate = null;
			}
			Townsfolk.flee(Vec3.atCenterOf(this.house), 16, now);
			level.playSound(null, this.house, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 2f, 0.6f);
		}

		private boolean light(ServerLevel level, BlockPos p) {
			BlockState fire = BaseFireBlock.getState(level, p);
			if (!level.getBlockState(p).isAir() || !fire.canSurvive(level, p)) {
				return false;
			}
			level.setBlock(p, fire, 3);
			this.fires.add(p);
			return true;
		}

		/** Something one of the ways out still needs (the gate, the car, a cellar), else anything for getting out. */
		private HwItems.Kind prize(ServerLevel level) {
			List<HwItems.Kind> useful = new ArrayList<>();
			for (HwNet.ExitInfo e : Exits.infos()) {
				switch (e.type()) {
					case Exits.CELLAR -> {
						if (e.state() == Exits.CLOSED) {
							useful.add(HwItems.Kind.PADLOCK_KEY);
							useful.add(HwItems.Kind.BOLT_CUTTERS);
						}
					}
					case Exits.CAR -> {
						if ((e.state() & Exits.CAR_GONE) == 0) {
							if ((e.state() & Exits.CAR_FUEL) == 0) {
								useful.add(HwItems.Kind.GAS_CAN);
							}
							if ((e.state() & Exits.CAR_REPAIRED) == 0) {
								useful.add(HwItems.Kind.REPAIR_KIT);
							}
							useful.add(HwItems.Kind.SEDAN_KEYS);
						}
					}
					case Exits.GATE -> {
						if (e.state() == Exits.CLOSED) {
							useful.add(e.extra() == 0 ? HwItems.Kind.FUSE : HwItems.Kind.GATE_KEY);
						}
					}
					default -> {
					}
				}
			}
			if (useful.isEmpty()) {
				useful.add(HwItems.Kind.REPAIR_KIT);
			}
			return useful.get(level.getRandom().nextInt(useful.size()));
		}

		@Override
		void tick(ServerLevel level, int now) {
			var random = level.getRandom();
			if (now % 4 == 0 && !this.roof.isEmpty()) {
				BlockPos p = this.roof.get(random.nextInt(this.roof.size()));
				level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, p.getX() + 0.5, p.getY() + 0.8, p.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0.02);
			}
			if (now % 3 == 0) {
				level.sendParticles(ParticleTypes.LARGE_SMOKE, this.smoke.x, this.smoke.y, this.smoke.z, 6, 4, 1.5, 4, 0.01);
			}
			if (now % 40 == 0) {
				Townsfolk.flee(Vec3.atCenterOf(this.house), 10, now);
			}
		}

		@Override
		void end(ServerLevel level) {
			for (BlockPos p : this.fires) {
				if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
				}
			}
			this.fires.clear();
			this.roof.clear();
			if (this.crate != null && level.getBlockState(this.crate).is(HwBlocks.CRATE)) {
				level.setBlock(this.crate, this.crateBefore == null ? Blocks.AIR.defaultBlockState() : this.crateBefore, 3);
			}
			this.smoke = null;
			level.playSound(null, this.house, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 2f, 0.8f);
		}

		@Override
		Holds.Hold resolve(ServerPlayer player, BlockPos pos) {
			if (this.crate == null || !pos.equals(this.crate) || !MatchManager.role(player).survivor() || this.prizeTaken) {
				return null;
			}
			return new Holds.Hold(this.crate, "halloween.hud.progress.search", false, HwConfig.get().searchTicks * 2, p -> !this.prizeTaken, p -> {
				this.prizeTaken = true;
				Inventory.give(p, Loot.found(this.prize));
				Component name = Component.translatable("halloween.item." + this.prize.key());
				p.sendOverlayMessage(Component.translatable("halloween.hint.found", name));
				Match.score(p, HwConfig.get().scoreEvent, Component.translatable("halloween.score.event.fire", name));
				AiStats.add(p, "event.fire.prize");
				BlockState s = p.level().getBlockState(this.crate);
				if (s.getBlock() instanceof SearchBlock) {
					p.level().setBlock(this.crate, s.setValue(SearchBlock.OPEN, true), Block.UPDATE_CLIENTS);
				}
			});
		}

		@Override
		void objects(List<BlockPos> out) {
			if (this.crate != null && !this.prizeTaken) {
				out.add(this.crate);
			}
		}

		@Override
		void marks(List<Mark> out) {
			out.add(new Mark(Vec3.atCenterOf(this.house).add(0, 3, 0), HwNet.EventMark.FIRE, FOR_ALL, 0));
		}

		@Override
		void jobs(BotPlayer bot, List<BotJob> out) {
			if (this.crate != null && !this.prizeTaken && bot.getHealth() > 10 && jobsNear(bot, "fire") == 0) {
				double d = bot.position().distanceTo(Vec3.atCenterOf(this.crate));
				if (d < 70) {
					out.add(new BotJob(this.crate, 2.0, true, "fire", 50 - d * 0.3));
				}
			}
		}
	}
}
