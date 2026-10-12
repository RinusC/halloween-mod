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
		Mannequin body;
		/** His baton broke on Michael: no new one comes out (bare fists from then on). */
		boolean batonBroken;
		Walker walker;
		UUID ward;
		int wardCheckAt;
		Vec3 lastSeen;
		int lastSeenTick = -100000;
		int nextHit;
		int repathAt;
		/** Revolver: rounds in the cylinder and in the belt pouch; aiming and reloading progress. */
		int cylinder;
		int spare;
		int aimTicks;
		int reloadTicks = -1;
		/** Last tick it heard the Shape struggling with someone close by, and its work on a locked door. */
		int helpTick = -100000;
		int unlockTicks;

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
	/** Of those, the ones a patrol made on seeing him (no body behind them: they don't use one up). */
	private static int sightings;
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
		sightings = 0;
		named = 0;
		for (ServerLevel level : server.getAllLevels()) {
			for (Mannequin body : level.getEntities(EntityTypes.MANNEQUIN, Police::isOfficer)) {
				body.discard();
			}
		}
	}

	/** Is this officer one the police here are running (not one left over from before)? */
	/** Its chunk loaded again: the same officer in a new body object. */
	public static void rebind(Entity entity) {
		Officer o = OFFICERS.get(entity.getUUID());
		if (o != null && o.body != entity && entity instanceof Mannequin m) {
			o.body = m;
			o.walker = new Walker(m);
		}
	}

	public static boolean registered(Entity entity) {
		return OFFICERS.containsKey(entity.getUUID());
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
		return Math.max(0, Math.min(found - (calls - sightings), HwConfig.get().policeMaxCalls - calls));
	}

	/** Bodies lying about, found or not (tests, debugging). */
	/** Is there a body (found or not) this close? */
	public static boolean bodyNear(Vec3 at, double radius) {
		for (Body b : BODIES) {
			if (b.pos.distanceTo(at) <= radius) {
				return true;
			}
		}
		return false;
	}

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

	/**
	 * A patrol saw him (see Events): the wanted level goes up without a body, and the police for it set off.
	 * Not past the last level, and only where there are police. True if it went up.
	 */
	public static boolean raiseWanted(ServerLevel level, Component announcement) {
		if (Exits.policePoint() == null || calls >= HwConfig.get().policeMaxCalls) {
			return false;
		}
		calls++;
		sightings++;
		ARRIVALS.add(new int[]{HwConfig.get().policeArriveSeconds * 20, calls});
		Match.announce(level.getServer(), announcement.copy().withStyle(ChatFormatting.BLUE));
		Match.announce(level.getServer(), Component.translatable("halloween.match.wanted", calls).withStyle(ChatFormatting.BLUE));
		Match.markDirty();
		return true;
	}

	/** An officer goes back to the patrol car: gone from town (see Events). */
	public static void dismiss(UUID id) {
		Officer o = OFFICERS.remove(id);
		if (o != null && !o.body.isRemoved()) {
			o.body.discard();
		}
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
		OFFICERS.values().removeIf(o -> Townsfolk.destroyed(o.body));
		for (Officer officer : new ArrayList<>(OFFICERS.values())) {
			if (!officer.body.isRemoved()) {
				tickOfficer(level, officer, now);
			}
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
			"{Tags:[\"%s\",\"%s\",\"%s\"],hide_description:1b,CustomName:\"%s\",profile:{texture:\"halloween:entity/police\",model:\"wide\"},Rotation:[%d.0f,0f]}",
			MatchManager.VICTIM_TAG, TAG, Leftovers.stamp(), name.replace("\"", ""), level.getRandom().nextInt(360));
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
		body.setItemSlot(EquipmentSlot.MAINHAND, HwItems.create(HwItems.Kind.REVOLVER));
		MatchManager.noJumping(body, true);
		Officer officer = new Officer(body);
		officer.cylinder = Guns.capacity(HwItems.Kind.REVOLVER);
		officer.spare = cfg.policeSpareRounds;
		OFFICERS.put(body.getUUID(), officer);
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
		Panic.add(finder, cfg.panicOnBody);
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
		ServerPlayer holder = Senses.shape(level);
		ShapeState holderState = holder == null ? null : MatchManager.shapeState(holder);
		if (holderState != null && holderState.grab != null && holderState.grab.struggling()
			&& holder.distanceTo(body) <= cfg.policeHelpRange) {
			// He has someone in his hands: whoever it is, the officer runs to pull them out (it hears the struggle).
			officer.lastSeen = holder.position();
			officer.lastSeenTick = Math.max(officer.lastSeenTick, now - 7);
			if (now - officer.helpTick > 100) {
				AiStats.add(body, "police.help");
			}
			officer.helpTick = now;
		}
		boolean helping = now - officer.helpTick < 10;
		LivingEntity ward = ward(level, officer, now);
		Vec3 home = ward != null ? ward.position() : Exits.policePoint() == null ? body.position() : Vec3.atBottomCenterOf(Exits.policePoint());
		boolean fresh = officer.lastSeen != null && now - officer.lastSeenTick < 60;
		// The Shape close to the officer itself: it stands its ground. Close to the one it guards: it goes for him,
		// but not further than the leash from them.
		boolean engage = helping || fresh && (officer.lastSeen.distanceTo(body.position()) <= cfg.policeEngageRange
			|| officer.lastSeen.distanceTo(home) <= cfg.policeGuardAlarm && officer.lastSeen.distanceTo(home) <= cfg.policeLeash);
		tickReload(level, officer);
		if (engage) {
			ServerPlayer shape = Senses.shape(level);
			boolean inSight = shape != null && (now - officer.lastSeenTick < 8 || helping);
			Vec3 target = inSight ? shape.position() : officer.lastSeen;
			body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(cfg.policeRunSpeed);
			if (inSight && shootAt(level, officer, shape, now)) {
				return;
			}
			officer.aimTicks = 0;
			if (inSight && body.distanceTo(shape) <= cfg.policeHitRange) {
				officer.walker.stop();
				officer.walker.face(shape.getEyePosition());
				// A man already knocked down isn't beaten while he lies there.
				if (now >= officer.nextHit && !stunned(shape)) {
					officer.nextHit = now + cfg.policeHitCooldownTicks;
					body.swing(InteractionHand.MAIN_HAND, true);
					level.playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.NEUTRAL, 1.2f, 0.8f);
					// The baton wears out like anyone's (its hits are counted); with it broken, bare fists, half as hard.
					net.minecraft.world.item.ItemStack held = body.getMainHandItem();
					boolean baton = HwItems.is(held, HwItems.Kind.BATON);
					Fight.stun(shape, baton ? cfg.policeBatonStun : cfg.policeBatonStun * 0.5f, null);
					if (baton && HwItems.wear(held)) {
						body.setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
						level.playSound(null, body.getX(), body.getY(), body.getZ(), SoundEvents.ITEM_BREAK.value(), SoundSource.NEUTRAL, 1f, 0.9f);
						officer.batonBroken = true;
						AiStats.add(body, "police.baton_broke");
					}
					AiStats.add(body, "police.hit");
				}
				return;
			}
			if (now >= officer.repathAt || officer.walker.idle()) {
				officer.repathAt = now + 10;
				officer.walker.speed = 1f;
				officer.walker.follow(Nav.to(level, body.blockPosition(), BlockPos.containing(target), 1.5, doors()));
			}
			walk(level, officer);
			return;
		}
		officer.aimTicks = 0;
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
			officer.walker.follow(Nav.to(level, body.blockPosition(), BlockPos.containing(home), cfg.policeGuardDistance - 1, doors()));
		}
		walk(level, officer);
	}

	/** Officers walk through doors, locked ones too: they get them open (they don't break them). */
	private static Nav.Options doors() {
		return Nav.Options.walker().lockedDoors(6);
	}

	private static void walk(ServerLevel level, Officer officer) {
		Walker.Status status = officer.walker.tick();
		if (status != Walker.Status.LOCKED) {
			officer.unlockTicks = 0;
			return;
		}
		Nav.Node node = officer.walker.current();
		BlockPos door = node == null ? null : DoorLocks.base(level, node.pos());
		if (door != null && ++officer.unlockTicks >= HwConfig.get().policeUnlockTicks) {
			officer.unlockTicks = 0;
			DoorLocks.forceUnlock(level, door);
			AiStats.add(officer.body, "police.unlocked");
		}
	}

	private static boolean stunned(ServerPlayer shape) {
		ShapeState state = MatchManager.shapeState(shape);
		return state != null && state.stunnedTicks > 0;
	}

	/**
	 * Stands, aims and fires at the Shape when there is a round in the cylinder, he is in pistol range but
	 * not at arm's length, and nobody else is in the line of fire. True while busy with it.
	 */
	private static boolean shootAt(ServerLevel level, Officer officer, ServerPlayer shape, int now) {
		HwConfig cfg = HwConfig.get();
		Mannequin body = officer.body;
		double dist = body.distanceTo(shape);
		if (officer.cylinder <= 0 || officer.reloadTicks >= 0 || dist <= cfg.policeHitRange || dist > cfg.policeShootRange) {
			return false;
		}
		if (stunned(shape)) {
			// He is down: no shots at a man on the ground; keep him covered until he gets up.
			officer.walker.stop();
			officer.walker.face(FocusPoints.bodyCenter(shape));
			officer.aimTicks = 0;
			return true;
		}
		Vec3 eye = body.getEyePosition();
		Vec3 target = FocusPoints.bodyCenter(shape);
		if (!Guns.lineClear(level, body, eye, target, shape)) {
			return false;
		}
		officer.walker.stop();
		officer.walker.face(target);
		if (++officer.aimTicks < cfg.policeAimTicks || now < officer.nextHit) {
			return true;
		}
		officer.aimTicks = cfg.policeAimTicks / 2;
		officer.nextHit = now + cfg.revolverShotTicks + 6;
		officer.cylinder--;
		body.swing(InteractionHand.MAIN_HAND, true);
		Guns.shoot(body, HwItems.Kind.REVOLVER, eye, target.subtract(eye), cfg.policeSpread, null);
		AiStats.add(body, "police.shot");
		if (officer.cylinder <= 0 && officer.spare > 0) {
			officer.reloadTicks = 0;
		}
		return true;
	}

	/** Reloading on the move, one round at a time; out of rounds for good, the baton comes out. */
	private static void tickReload(ServerLevel level, Officer officer) {
		HwConfig cfg = HwConfig.get();
		if (officer.reloadTicks < 0) {
			if (officer.cylinder <= 0 && officer.spare <= 0 && !HwItems.is(officer.body.getMainHandItem(), HwItems.Kind.BATON) && !officer.batonBroken) {
				officer.body.setItemSlot(EquipmentSlot.MAINHAND, HwItems.create(HwItems.Kind.BATON));
			}
			return;
		}
		if (++officer.reloadTicks < cfg.revolverRoundTicks) {
			return;
		}
		officer.reloadTicks = 0;
		officer.cylinder++;
		officer.spare--;
		level.playSound(null, officer.body.getX(), officer.body.getEyeY(), officer.body.getZ(), dev.halloween.HwSounds.GUN_LOAD_ROUND, SoundSource.NEUTRAL, 0.7f, 1f);
		if (officer.cylinder >= Guns.capacity(HwItems.Kind.REVOLVER) || officer.spare <= 0) {
			officer.reloadTicks = -1;
		}
	}

	/** An officer goes down: the revolver (with what is left in it) and the spare rounds fall where it lay. */
	public static void dropGear(LivingEntity victim) {
		Officer officer = OFFICERS.get(victim.getUUID());
		if (officer == null) {
			return;
		}
		ServerLevel level = (ServerLevel) victim.level();
		if (officer.cylinder > 0 || officer.spare > 0) {
			net.minecraft.world.item.ItemStack gun = HwItems.create(HwItems.Kind.REVOLVER);
			Guns.setLoaded(gun, officer.cylinder);
			level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, victim.getX(), victim.getY() + 0.3, victim.getZ(), gun));
		}
		if (officer.spare > 0) {
			net.minecraft.world.item.ItemStack rounds = HwItems.create(HwItems.Kind.REVOLVER_AMMO);
			rounds.setCount(officer.spare);
			level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level, victim.getX(), victim.getY() + 0.3, victim.getZ(), rounds));
		}
		officer.cylinder = 0;
		officer.spare = 0;
	}

	/** Rounds an officer has left, cylinder and pouch (tests). */
	public static int rounds(Entity entity) {
		Officer officer = entity == null ? null : OFFICERS.get(entity.getUUID());
		return officer == null ? 0 : officer.cylinder + officer.spare;
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

	/** Makes an officer look after this one for good (tests). */
	public static void guard(Entity officer, LivingEntity ward) {
		Officer o = officer == null ? null : OFFICERS.get(officer.getUUID());
		if (o != null) {
			o.ward = ward.getUUID();
			o.wardCheckAt = Integer.MAX_VALUE;
		}
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
