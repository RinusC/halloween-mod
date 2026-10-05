package dev.halloween.game;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.halloween.HwConfig;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * The police, as in the game: nobody can call them about nothing. Each body someone finds (a civilian,
 * a bot or a townsperson, who screams) lets one call through (at most {@link HwConfig#policeMaxCalls}).
 * Every call raises Michael's wanted level by one, and after {@link HwConfig#policeArriveSeconds} the
 * police for that level arrive: officers on levels 1-3, and the van one survivor can leave in on levels 1
 * and 3. Officers guard the survivors (and townsfolk once no survivor is left), and fight the Shape with
 * their batons when he comes near the one they guard. He can kill them like anyone else; their bodies
 * count like anyone's.
 */
public final class Police {
	public static final String TAG = "hw_police";

	private static final class Body {
		final Vec3 pos;
		final Component name;
		boolean found;

		Body(Vec3 pos, Component name) {
			this.pos = pos;
			this.name = name;
		}
	}

	private static final class Officer {
		final Mannequin body;
		final Walker walker;
		UUID ward;
		int wardCheckAt;
		Vec3 lastSeen;
		int lastSeenTick = -100000;
		int nextHit;
		int repathAt;

		Officer(Mannequin body) {
			this.body = body;
			this.walker = new Walker(body);
			this.walker.closeDoors = false;
		}
	}

	private static final List<Body> BODIES = new ArrayList<>();
	/** Calls on their way: {ticks until they arrive, wanted level they bring}. */
	private static final List<int[]> ARRIVALS = new ArrayList<>();
	private static final Map<UUID, Officer> OFFICERS = new LinkedHashMap<>();
	private static int calls;
	private static int named;

	private Police() {
	}

	// ---------------------------------------------------------------- state

	public static void clear(MinecraftServer server) {
		for (Officer officer : OFFICERS.values()) {
			officer.body.discard();
		}
		OFFICERS.clear();
		BODIES.clear();
		ARRIVALS.clear();
		calls = 0;
		named = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Mannequin body : level.getEntities(EntityTypes.MANNEQUIN, Police::isOfficer)) {
				body.discard();
			}
		}
	}

	public static boolean isOfficer(Entity entity) {
		return entity != null && entity.entityTags().contains(TAG);
	}

	/** Michael's wanted level: how many calls got through (0-3). */
	public static int wanted() {
		return calls;
	}

	/** Bodies found that nobody has reported yet (each one is worth a call). */
	public static int reportable() {
		int found = 0;
		for (Body body : BODIES) {
			found += body.found ? 1 : 0;
		}
		return Math.max(0, Math.min(found - calls, HwConfig.get().policeMaxCalls - calls));
	}

	/** Bodies lying about, found or not (tests, debugging). */
	public static int bodies() {
		return BODIES.size();
	}

	/** Someone could call now: a found body nobody reported and calls left. */
	public static boolean callable() {
		return calls < HwConfig.get().policeMaxCalls && reportable() > 0;
	}

	/** Ticks until the next police arrive, or -1 if nobody is on the way. */
	public static int nextArrival() {
		int best = -1;
		for (int[] a : ARRIVALS) {
			best = best < 0 ? a[0] : Math.min(best, a[0]);
		}
		return best;
	}

	/** Could a van still come this match (one on its way, or a level that brings one not reached yet)? */
	public static boolean vanStillPossible() {
		HwConfig cfg = HwConfig.get();
		for (int[] a : ARRIVALS) {
			if (bringsVan(a[1])) {
				return true;
			}
		}
		for (int level = calls + 1; level <= cfg.policeMaxCalls; level++) {
			if (bringsVan(level)) {
				return true;
			}
		}
		return false;
	}

	private static boolean bringsVan(int level) {
		int[] vans = HwConfig.get().policeVanLevels;
		for (int v : vans) {
			if (v == level) {
				return true;
			}
		}
		return false;
	}

	public static int officers() {
		return (int) OFFICERS.values().stream().filter(o -> !o.body.isRemoved() && o.body.isAlive()).count();
	}

	/** A victim was killed here: the body waits for someone to find it. */
	public static void addBody(Vec3 pos, Component name) {
		BODIES.add(new Body(pos, name));
	}

	/** A call got through: the wanted level goes up and the police for it set off. */
	public static void reportCall(ServerLevel level, Component announcement) {
		if (!callable()) {
			return;
		}
		calls++;
		ARRIVALS.add(new int[]{HwConfig.get().policeArriveSeconds * 20, calls});
		Match.announce(level.getServer(), announcement.copy().withStyle(ChatFormatting.BLUE));
		Match.announce(level.getServer(), Component.translatable("halloween.match.wanted", calls).withStyle(ChatFormatting.BLUE));
		Match.markDirty();
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		if (!Match.running()) {
			for (Officer officer : OFFICERS.values()) {
				officer.walker.halt();
			}
			return;
		}
		ServerLevel level = server.overworld();
		int now = server.getTickCount();
		Iterator<int[]> it = ARRIVALS.iterator();
		List<Integer> arriving = new ArrayList<>();
		while (it.hasNext()) {
			int[] a = it.next();
			if (--a[0] <= 0) {
				it.remove();
				arriving.add(a[1]);
			}
		}
		for (int wantedLevel : arriving) {
			arrive(level, wantedLevel);
		}
		if (now % 10 == 0) {
			discover(level, now);
		}
		OFFICERS.values().removeIf(o -> o.body.isRemoved() || !o.body.isAlive());
		for (Officer officer : new ArrayList<>(OFFICERS.values())) {
			tickOfficer(level, officer, now);
		}
	}

	private static void arrive(ServerLevel level, int wantedLevel) {
		HwConfig cfg = HwConfig.get();
		BlockPos at = Exits.policePoint();
		if (at == null) {
			return;
		}
		if (bringsVan(wantedLevel)) {
			Exits.vanArrives(level);
		}
		int count = HwConfig.at(cfg.policeOfficersByLevel, wantedLevel - 1);
		int spawned = 0;
		for (int i = 0; i < count; i++) {
			if (spawnOfficer(level, spot(level, at, i)) != null) {
				spawned++;
			}
		}
		level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 3f, 0.7f);
		Match.announce(level.getServer(), Component.translatable("halloween.match.police_officers", spawned, wantedLevel).withStyle(ChatFormatting.BLUE));
		Match.markDirty();
	}

	/** Where the {@code i}-th officer steps out: beside the van spot, clear of the van itself. */
	private static Vec3 spot(ServerLevel level, BlockPos at, int i) {
		int[][] offsets = {{3, 0}, {-3, 0}, {3, 2}, {-3, 2}, {3, -2}, {-3, -2}, {0, 4}, {0, -4}};
		for (int k = 0; k < offsets.length; k++) {
			int[] o = offsets[(i + k) % offsets.length];
			BlockPos p = Nav.snap(level, at.offset(o[0], 0, o[1]));
			if (Nav.standable(level, p)) {
				return Vec3.atBottomCenterOf(p);
			}
		}
		return Vec3.atBottomCenterOf(at);
	}

	/** Spawns an officer with a baton (tests can call it directly). */
	public static Mannequin spawnOfficer(ServerLevel level, Vec3 pos) {
		HwConfig cfg = HwConfig.get();
		String name = cfg.policeNames.length == 0 ? "Officer" : cfg.policeNames[named++ % cfg.policeNames.length];
		String snbt = String.format(Locale.ROOT,
			"{Tags:[\"%s\",\"%s\"],hide_description:1b,CustomName:\"%s\",profile:{texture:\"halloween:entity/police\",model:\"wide\"},Rotation:[%d.0f,0f]}",
			MatchManager.VICTIM_TAG, TAG, name.replace("\"", ""), level.getRandom().nextInt(360));
		CommandSourceStack console = level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
		Entity entity;
		try {
			entity = SummonCommand.createEntity(console, EntityTypes.MANNEQUIN.builtInRegistryHolder(), pos, TagParser.parseCompoundFully(snbt), false);
		} catch (CommandSyntaxException e) {
			dev.halloween.Halloween.LOGGER.error("Could not spawn police officer {}", name, e);
			return null;
		}
		if (!(entity instanceof Mannequin body)) {
			return null;
		}
		body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(cfg.policeWalkSpeed);
		body.getAttribute(Attributes.MAX_HEALTH).setBaseValue(cfg.policeHealth);
		body.setHealth(body.getMaxHealth());
		body.setItemSlot(EquipmentSlot.MAINHAND, HwItems.create(HwItems.Kind.BATON));
		MatchManager.noJumping(body, true);
		OFFICERS.put(body.getUUID(), new Officer(body));
		return body;
	}

	// ---------------------------------------------------------------- finding bodies

	private static void discover(ServerLevel level, int now) {
		HwConfig cfg = HwConfig.get();
		for (Body body : BODIES) {
			if (body.found) {
				continue;
			}
			Vec3 target = body.pos.add(0, 0.3, 0);
			LivingEntity finder = null;
			for (ServerPlayer player : level.players()) {
				if (MatchManager.role(player).survivor() && !player.isSpectator() && !Lockers.isInside(player)
					&& sees(level, player, target, cfg.bodyFovDeg)) {
					finder = player;
					break;
				}
			}
			if (finder == null) {
				for (int id : Townsfolk.ids()) {
					Entity npc = level.getEntity(id);
					if (npc instanceof LivingEntity living && !Lockers.isInside(living) && !MatchManager.isHeld(living)
						&& sees(level, living, target, cfg.npcFovDeg)) {
						finder = living;
						break;
					}
				}
			}
			if (finder != null) {
				found(level, body, finder, now);
			}
		}
	}

	private static boolean sees(ServerLevel level, LivingEntity viewer, Vec3 target, double fovDeg) {
		HwConfig cfg = HwConfig.get();
		Vec3 eye = viewer.getEyePosition();
		double dist = eye.distanceTo(target);
		boolean lit = level.getBrightness(LightLayer.BLOCK, net.minecraft.core.BlockPos.containing(target)) >= 6;
		if (dist > (lit ? cfg.bodySightRange : cfg.bodyDarkSightRange)) {
			return false;
		}
		if (dist > 2.5 && Sight.angleTo(eye, viewer.getViewVector(1f), target) > fovDeg / 2) {
			return false;
		}
		return Sight.canSee(level, eye, target, viewer);
	}

	private static void found(ServerLevel level, Body body, LivingEntity finder, int now) {
		HwConfig cfg = HwConfig.get();
		body.found = true;
		AiStats.add(finder, "body.found");
		Component who = finder.getDisplayName();
		HwNet.Alert alert = new HwNet.Alert(body.pos, cfg.alertTicks);
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player).survivor() && !Bots.isBot(player)) {
				Notices.send(player, Component.translatable("halloween.msg.body_found", body.name, who).withStyle(ChatFormatting.GOLD));
				ServerPlayNetworking.send(player, alert);
			}
		}
		if (finder instanceof ServerPlayer player && !Bots.isBot(player)) {
			Match.score(player, cfg.scoreFindBody, Component.translatable("halloween.score.body_found", body.name));
		}
		if (Townsfolk.isNpc(finder)) {
			Townsfolk.sawBody(finder, body.pos, now);
		}
		Match.markDirty();
	}

	// ---------------------------------------------------------------- officers

	private static void tickOfficer(ServerLevel level, Officer officer, int now) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = officer.body;
		if (MatchManager.isHeld(body)) {
			officer.walker.halt();
			return;
		}
		if (now % 4 == 0) {
			ServerPlayer seen = Senses.seenShape(body, cfg.policeSightRange, cfg.policeDarkSightRange, cfg.policeFovDeg);
			if (seen != null) {
				if (now - officer.lastSeenTick > 100) {
					// "There he is!": the bots hear it.
					Bots.alert(seen.position(), now);
					AiStats.add(body, "police.spotted");
				}
				officer.lastSeen = seen.position();
				officer.lastSeenTick = now;
			}
		}
		LivingEntity ward = ward(level, officer, now);
		Vec3 home = ward != null ? ward.position() : Exits.policePoint() == null ? body.position() : Vec3.atBottomCenterOf(Exits.policePoint());
		boolean fresh = officer.lastSeen != null && now - officer.lastSeenTick < 60;
		boolean engage = fresh && officer.lastSeen.distanceTo(home) <= cfg.policeLeash
			&& (officer.lastSeen.distanceTo(body.position()) <= cfg.policeEngageRange || officer.lastSeen.distanceTo(home) <= cfg.policeGuardAlarm);
		if (engage) {
			ServerPlayer shape = Senses.shape(level);
			boolean inSight = shape != null && now - officer.lastSeenTick < 8;
			Vec3 target = inSight ? shape.position() : officer.lastSeen;
			body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(cfg.policeRunSpeed);
			if (inSight && body.distanceTo(shape) <= cfg.policeHitRange) {
				officer.walker.stop();
				officer.walker.face(shape.getEyePosition());
				if (now >= officer.nextHit) {
					officer.nextHit = now + cfg.policeHitCooldownTicks;
					body.swing(InteractionHand.MAIN_HAND, true);
					level.playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1.2f, 0.8f);
					Fight.stun(shape, cfg.policeBatonStun, null);
					AiStats.add(body, "police.hit");
				}
				return;
			}
			if (now >= officer.repathAt || officer.walker.idle()) {
				officer.repathAt = now + 10;
				officer.walker.speed = 1f;
				officer.walker.follow(Nav.to(level, body.blockPosition(), BlockPos.containing(target), 1.5, Nav.Options.walker()));
			}
			officer.walker.tick();
			return;
		}
		// Guarding: keep a few steps from the one we look after.
		double dist = body.position().distanceTo(home);
		if (dist <= cfg.policeGuardDistance) {
			officer.walker.stop();
			if (ward != null && now % 40 == 0) {
				// Look about, not at the back of their head.
				float yaw = level.getRandom().nextFloat() * 360f;
				officer.walker.face(body.getEyePosition().add(Math.sin(Math.toRadians(yaw)) * 4, 0, Math.cos(Math.toRadians(yaw)) * 4));
			}
			return;
		}
		body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(dist > cfg.policeGuardDistance * 3 ? cfg.policeRunSpeed : cfg.policeWalkSpeed);
		if (now >= officer.repathAt || officer.walker.idle()) {
			officer.repathAt = now + 20;
			officer.walker.speed = 1f;
			officer.walker.follow(Nav.to(level, body.blockPosition(), BlockPos.containing(home), cfg.policeGuardDistance - 1, Nav.Options.walker()));
		}
		officer.walker.tick();
	}

	/**
	 * The one this officer looks after: a survivor first (the one with the fewest officers on them, then the
	 * nearest), townsfolk once no survivor is left.
	 */
	private static LivingEntity ward(ServerLevel level, Officer officer, int now) {
		LivingEntity current = officer.ward == null ? null : (LivingEntity) level.getEntity(officer.ward);
		boolean valid = current != null && MatchManager.isVictim(current);
		if (valid && now < officer.wardCheckAt) {
			return current;
		}
		officer.wardCheckAt = now + 100;
		List<LivingEntity> candidates = new ArrayList<>();
		for (ServerPlayer player : level.players()) {
			if (MatchManager.role(player).survivor() && !player.isSpectator()) {
				candidates.add(player);
			}
		}
		if (candidates.isEmpty()) {
			for (int id : Townsfolk.ids()) {
				if (level.getEntity(id) instanceof LivingEntity npc) {
					candidates.add(npc);
				}
			}
		}
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		for (LivingEntity c : candidates) {
			int guards = 0;
			for (Officer other : OFFICERS.values()) {
				if (other != officer && c.getUUID().equals(other.ward)) {
					guards++;
				}
			}
			double score = c.distanceTo(officer.body) + guards * 40 - (c == current ? 10 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = c;
			}
		}
		officer.ward = best == null ? null : best.getUUID();
		return best;
	}

	/** Whom each officer guards (tests, debugging). */
	public static List<String> wards(ServerLevel level) {
		List<String> list = new ArrayList<>();
		for (Officer officer : OFFICERS.values()) {
			Entity ward = officer.ward == null ? null : level.getEntity(officer.ward);
			list.add(officer.body.getPlainTextName() + " -> " + (ward == null ? "-" : ward.getPlainTextName()));
		}
		return list;
	}

	/** Officers' entity ids (tests). */
	public static List<Integer> ids() {
		List<Integer> ids = new ArrayList<>();
		for (Officer officer : OFFICERS.values()) {
			ids.add(officer.body.getId());
		}
		return ids;
	}

	/** Marks every body as found (tests, map checks). */
	public static void findAll() {
		BODIES.forEach(b -> b.found = true);
		Match.markDirty();
	}
}
