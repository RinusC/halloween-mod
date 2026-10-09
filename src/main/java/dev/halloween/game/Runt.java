package dev.halloween.game;

import dev.halloween.Halloween;
import dev.halloween.HwBlocks;
import dev.halloween.HwConfig;
import dev.halloween.HwSounds;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Runt: Michael's little horror, a second killer in the same match who can't kill. He frightens
 * (panic, a scream that tells Michael where), and grows nastier as he feeds on fear: at each of three levels he
 * picks one of two gifts (cling on to a survivor and hold them for Michael, or hex them: no breath, no healing;
 * smell out everyone near, or play tricks: unlock doors, cut phones, throw breakers; a dread that hangs round
 * him, or trading places with Michael). A shove knocks him off his feet, a blow too, a shot or a flare for longer;
 * nothing sends him out of the game. A player plays him when one was picked (/hw runt), otherwise a bot.
 */
public final class Runt {
	public static final String BOT_NAME = "Runt";
	/** The gifts: [level][option]. */
	public static final String[][] GIFTS = {{"clinger", "hex"}, {"scent", "mischief"}, {"dread", "swap"}};
	private static final net.minecraft.resources.Identifier ROOT_ID = Halloween.id("runt_root");

	/** Per-match state of whoever plays him. */
	public static final class State {
		public float fear;
		/** Levels reached, 0..3. */
		public int level;
		public final int[] picks = {-1, -1, -1};
		public int scareCooldown;
		public int scareCooldownMax = 1;
		/** Trading places with Michael. */
		public int specialCooldown;
		public int specialCooldownMax = 1;
		/** Mischief (doors, phones, breakers). */
		public int trickCooldown;
		public int trickCooldownMax = 1;
		public int stunTicks;
		public int trappedTicks;
		public UUID holding;
		/** A bot he holds gets its hands free at this tick (it plays the hands game too, after its fashion). */
		int botFreeAt;
		/** Crouched, winding up a pounce (ticks); in the air (ticks since he leapt, 0 = not); a second go after a miss (clinger). */
		public int crouch;
		public int leapTicks;
		boolean secondChance;
		int secondChanceUntil;
		/** Between pounces (from the leap; the clinger's second go after a miss doesn't wait for it). */
		public int pounceCooldown;
		public int pounceCooldownMax = 1;
		public int knockCooldown;

		/** How wound up the pounce is, 0..1. */
		public float charge() {
			return Math.min(1f, this.crouch / (float) Math.max(1, HwConfig.get().runtPounceChargeTicks));
		}
		public int score;
		int passiveAt;
		RuntBrain brain;

		public boolean has(String gift) {
			for (int i = 0; i < GIFTS.length; i++) {
				if (this.picks[i] >= 0 && GIFTS[i][this.picks[i]].equals(gift)) {
					return true;
				}
			}
			return false;
		}

		/** The first level reached whose gift isn't picked yet (0..2), or -1. */
		public int pending() {
			for (int i = 0; i < this.level; i++) {
				if (this.picks[i] < 0) {
					return i;
				}
			}
			return -1;
		}

		/** Knocked down, trapped, in the air, or busy holding someone: no scaring now. */
		public boolean busy() {
			return this.stunTicks > 0 || this.trappedTicks > 0 || this.holding != null || this.leapTicks > 0;
		}
	}

	private static final Map<UUID, State> RUNTS = new LinkedHashMap<>();
	/** Hexed survivors and until when. */
	private static final Map<UUID, Integer> HEXED = new HashMap<>();
	/** Who plays him next match: a player, or null for a bot; off: no Runt at all. */
	private static UUID chosen;
	private static boolean off;
	private static BotPlayer bot;

	private Runt() {
	}

	// ---------------------------------------------------------------- who plays him

	public static void choose(UUID player) {
		chosen = player;
		off = false;
	}

	public static void chooseBot() {
		chosen = null;
		off = false;
	}

	public static void disable() {
		off = true;
	}

	public static boolean enabled() {
		return !off && HwConfig.get().runtEnabled;
	}

	/** The person picked to play him this match (/hw runt), if they are here and aren't Michael. */
	public static ServerPlayer humanFor(List<ServerPlayer> players, ServerPlayer shape) {
		if (!enabled() || chosen == null) {
			return null;
		}
		for (ServerPlayer p : players) {
			if (p.getUUID().equals(chosen) && p != shape && !(p instanceof BotPlayer)) {
				return p;
			}
		}
		return null;
	}

	/** Nobody picked: a bot plays him (none if he is switched off). */
	public static ServerPlayer botFor(MinecraftServer server, BlockPos spawn) {
		if (!enabled()) {
			return null;
		}
		bot = Bots.spawnLoose(server, BOT_NAME, spawn);
		return bot;
	}

	public static State state(Entity who) {
		return who == null ? null : RUNTS.get(who.getUUID());
	}

	public static boolean isRunt(Entity who) {
		return who instanceof ServerPlayer p && MatchManager.role(p) == Role.RUNT;
	}

	public static List<ServerPlayer> all(MinecraftServer server) {
		List<ServerPlayer> out = new ArrayList<>();
		for (UUID id : RUNTS.keySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				out.add(p);
			}
		}
		return out;
	}

	/** He takes the role: small, quick on his feet, can't be hurt. */
	static void become(ServerPlayer player) {
		HwConfig cfg = HwConfig.get();
		State s = new State();
		s.passiveAt = player.level().getServer().getTickCount() + cfg.runtFearPassiveTicks;
		if (player instanceof BotPlayer b) {
			s.brain = new RuntBrain(b);
		}
		RUNTS.put(player.getUUID(), s);
		player.setGameMode(GameType.ADVENTURE);
		player.getInventory().clearContent();
		player.getAttribute(Attributes.SCALE).setBaseValue(cfg.runtScale);
		player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(cfg.runtSpeed);
		player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
		player.setHealth(player.getMaxHealth());
	}

	/** Back to himself after the match. */
	static void leave(ServerPlayer player) {
		RUNTS.remove(player.getUUID());
		player.getAttribute(Attributes.SCALE).setBaseValue(1.0);
		player.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.1);
		root(player, false);
	}

	public static void clear(MinecraftServer server) {
		HEXED.clear();
		RuntFear.clear();
		for (Map.Entry<UUID, State> e : RUNTS.entrySet()) {
			if (e.getValue().holding != null) {
				ServerPlayer victim = server.getPlayerList().getPlayer(e.getValue().holding);
				if (victim != null) {
					root(victim, false);
					MatchManager.noJumping(victim, false);
				}
			}
		}
		if (bot != null) {
			if (server.getPlayerList().getPlayer(bot.getUUID()) == bot) {
				server.getPlayerList().remove(bot);
			}
			RUNTS.remove(bot.getUUID());
			bot = null;
		}
	}

	// ---------------------------------------------------------------- tick

	public static void tick(MinecraftServer server) {
		if (RUNTS.isEmpty()) {
			HEXED.clear();
			return;
		}
		HwConfig cfg = HwConfig.get();
		int now = server.getTickCount();
		HEXED.values().removeIf(until -> until < now);
		boolean running = Match.running();
		for (Map.Entry<UUID, State> e : new ArrayList<>(RUNTS.entrySet())) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			State s = e.getValue();
			if (p == null || !running) {
				continue;
			}
			p.setSprinting(false);
			s.scareCooldown = Math.max(0, s.scareCooldown - 1);
			s.specialCooldown = Math.max(0, s.specialCooldown - 1);
			s.trickCooldown = Math.max(0, s.trickCooldown - 1);
			s.pounceCooldown = Math.max(0, s.pounceCooldown - 1);
			s.knockCooldown = Math.max(0, s.knockCooldown - 1);
			if (s.stunTicks > 0 && --s.stunTicks == HwConfig.get().stunRiseTicks) {
				GrabSession.anim(p, HwNet.Anim.RISE);
			}
			s.trappedTicks = Math.max(0, s.trappedTicks - 1);

			pounceTick(p, s, now);
			if (s.holding != null) {
				tickHold(p, s, now);
			}

			if (s.has("dread") && now % 20 == 0) {
				for (ServerPlayer v : p.level().players()) {
					if (MatchManager.role(v).survivor() && !v.isSpectator() && v.distanceTo(p) < cfg.runtDreadRange) {
						Panic.add(v, cfg.runtDreadPanic);
					}
				}
			}
			root(p, s.stunTicks > 0 || s.trappedTicks > 0 || s.holding != null);
			if (s.brain != null) {
				s.brain.tick(now, s);
			}
			if (now % 5 == 0) {
				sync(p, s);
			}
		}
	}

	/** Can't take a step (knocked down, trapped, holding on). */
	static void root(net.minecraft.world.entity.LivingEntity p, boolean on) {
		AttributeInstance speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
		if (on && speed.getModifier(ROOT_ID) == null) {
			speed.addTransientModifier(new AttributeModifier(ROOT_ID, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} else if (!on && speed.getModifier(ROOT_ID) != null) {
			speed.removeModifier(ROOT_ID);
		}
	}

	// ---------------------------------------------------------------- fear and gifts

	private static final int[] NO_LEVELS = {};

	static void gain(ServerPlayer runt, State s, float amount) {
		HwConfig cfg = HwConfig.get();
		s.fear += amount;
		s.score += Math.round(amount * cfg.runtScorePerFear);
		int[] at = cfg.runtLevelFear == null ? NO_LEVELS : cfg.runtLevelFear;
		while (s.level < Math.min(3, at.length) && s.fear >= at[s.level]) {
			s.level++;
			runt.level().playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 0.8f, 1.6f);
			if (s.brain != null) {
				pick(runt, s.level - 1, runt.getRandom().nextInt(2));
			} else {
				Notices.send(runt, Component.translatable("halloween.runt.level_up", s.level).withStyle(ChatFormatting.DARK_PURPLE));
			}
		}
	}

	/** What his Runt is doing as Michael's screen shows it: 0 by Michael, 1 searching, 2 after someone, 3 tricks, 4 on his own, 5 holding, 6 down. */
	public static int statusOf(Entity who) {
		State s = state(who);
		return s == null ? -1 : status(s);
	}

	private static int status(State s) {
		if (s.holding != null) {
			return 5;
		}
		if (s.stunTicks > 0 || s.trappedTicks > 0) {
			return 6;
		}
		return s.brain == null ? 4 : s.brain.status();
	}

	// ---------------------------------------------------------------- the pounce

	/**
	 * Crouch to wind up, let go to leap the way he looks: the longer the wind-up, the further. Touch a survivor in
	 * the air and he is on their back; land on nothing and he lies stunned (with the clinger gift, not the first time).
	 * Then a pause before the next (the clinger's second go after a miss comes at once).
	 */
	private static void pounceTick(ServerPlayer p, State s, int now) {
		HwConfig cfg = HwConfig.get();
		if (s.secondChance && now > s.secondChanceUntil) {
			s.secondChance = false;
		}
		if (s.leapTicks > 0) {
			leapTick(p, s, now);
			return;
		}
		boolean free = s.stunTicks == 0 && s.trappedTicks == 0 && s.holding == null && (s.pounceCooldown == 0 || s.secondChance);
		if (free && p.isShiftKeyDown() && p.onGround()) {
			s.crouch = Math.min(cfg.runtPounceChargeTicks, s.crouch + 1);
			return;
		}
		if (free && s.crouch >= cfg.runtPounceMinChargeTicks) {
			leap(p, s);
		}
		s.crouch = 0;
	}

	private static void leap(ServerPlayer p, State s) {
		HwConfig cfg = HwConfig.get();
		double dist = cfg.runtPounceMinDistance + (cfg.runtPounceMaxDistance - cfg.runtPounceMinDistance) * s.charge();
		Vec3 dir = p.getLookAngle().multiply(1, 0, 1);
		if (dir.lengthSqr() < 1.0E-4) {
			dir = Vec3.directionFromRotation(0, p.getYRot());
		}
		// In the air for about a dozen ticks, the drag of the air taking off a little each one: about seven ticks' worth.
		Vec3 v = dir.normalize().scale(dist / 7.0);
		p.setDeltaMovement(v.x, 0.42, v.z);
		p.hurtMarked = true;
		s.leapTicks = 1;
		s.crouch = 0;
		s.pounceCooldown = s.pounceCooldownMax = cfg.runtPounceCooldownTicks;
		p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.2f, 0.8f);
		AiStats.add(p, "runt.pounce");
	}

	private static void leapTick(ServerPlayer p, State s, int now) {
		HwConfig cfg = HwConfig.get();
		s.leapTicks++;
		net.minecraft.world.phys.AABB reach = p.getBoundingBox().inflate(0.35);
		for (ServerPlayer v : p.level().players()) {
			if (MatchManager.role(v).survivor() && !v.isSpectator() && !Lockers.isInside(v) && !MatchManager.isGrabbed(v) && heldBy(v) == null
				&& reach.intersects(v.getBoundingBox())) {
				// Right on them: onto their back, hands at their throat.
				s.leapTicks = 0;
				s.secondChance = false;
				Panic.add(v, cfg.runtScarePanic);
				Voices.play(v, Voices.Kind.SCREAM);
				p.level().playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2f, 0.6f);
				gain(p, s, cfg.runtFearPounce);
				startHold(p, s, v);
				AiStats.add(p, "runt.pounce_hit");
				return;
			}
		}
		for (net.minecraft.world.entity.decoration.Mannequin npc : Townsfolk.bodies()) {
			if (!Lockers.isInside(npc) && !MatchManager.isHeld(npc) && heldBy(npc) == null && reach.intersects(npc.getBoundingBox())) {
				// A townsperson: on their back too.
				s.leapTicks = 0;
				s.secondChance = false;
				p.level().playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 1.2f, 0.6f);
				gain(p, s, cfg.runtFearPounceNpc);
				startHold(p, s, npc);
				AiStats.add(p, "runt.pounce_npc");
				return;
			}
		}
		if (s.leapTicks > 3 && p.onGround() || s.leapTicks > 40) {
			s.leapTicks = 0;
			p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.HOSTILE, 1f, 0.6f);
			if (s.has("clinger") && !s.secondChance) {
				// The clinger gets a second go before the fall takes him.
				s.secondChance = true;
				s.secondChanceUntil = now + 60;
				Notices.send(p, Component.translatable("halloween.runt.second_pounce").withStyle(ChatFormatting.DARK_PURPLE));
			} else {
				s.secondChance = false;
				floor(p, s, cfg.runtPounceMissStunTicks);
				AiStats.add(p, "runt.pounce_miss");
			}
		}
	}

	// ---------------------------------------------------------------- frightening for real, and Michael's orders

	/** Knocking on a door or a window. */
	public static boolean knock(ServerPlayer runt, BlockPos pos) {
		State s = RUNTS.get(runt.getUUID());
		return s != null && Match.running() && RuntFear.knock(runt, s, pos);
	}

	/** Michael tells his Runt (a bot) what to do: search there, go for that one, play tricks there, come back. */
	public static void order(ServerPlayer michael, int kind, int targetId, BlockPos pos) {
		if (MatchManager.role(michael) != Role.SHAPE || !Match.running()) {
			return;
		}
		ServerLevel level = michael.level();
		Entity target = targetId < 0 ? null : level.getEntity(targetId);
		// A survivor or a townsperson he points it at.
		net.minecraft.world.entity.LivingEntity survivor = target instanceof ServerPlayer sp && MatchManager.role(sp).survivor() && !sp.isSpectator() ? sp
			: Townsfolk.isNpc(target) && target instanceof net.minecraft.world.entity.LivingEntity l ? l : null;
		for (State s : RUNTS.values()) {
			if (s.brain != null) {
				s.brain.order(kind, survivor, pos, level.getServer().getTickCount());
				Notices.send(michael, Component.translatable("halloween.runt.order." + kind).withStyle(ChatFormatting.DARK_PURPLE));
			}
		}
	}

	/** He picks a gift for a level he has reached. */
	public static void pick(ServerPlayer runt, int level, int option) {
		State s = RUNTS.get(runt.getUUID());
		if (s == null || level < 0 || level >= GIFTS.length || level >= s.level || s.picks[level] >= 0 || option < 0 || option > 1) {
			return;
		}
		s.picks[level] = option;
		String gift = GIFTS[level][option];
		AiStats.add(runt, "runt.gift." + gift);
		Notices.send(runt, Component.translatable("halloween.runt.gift_taken", Component.translatable("halloween.runt.gift." + gift))
			.withStyle(ChatFormatting.DARK_PURPLE));
		if (gift.equals("dread")) {
			s.scareCooldown = Math.min(s.scareCooldown, scareCooldown(s));
		}
	}

	private static int scareCooldown(State s) {
		HwConfig cfg = HwConfig.get();
		return Math.round(cfg.runtScareCooldownTicks * (s.has("dread") ? cfg.runtDreadCooldownFactor : 1f));
	}

	// ---------------------------------------------------------------- scaring and holding

	/** He goes for someone within reach: a survivor screams and panics (and Michael learns where); a townsperson too. */
	public static boolean scare(ServerPlayer runt, Entity target) {
		State s = RUNTS.get(runt.getUUID());
		HwConfig cfg = HwConfig.get();
		if (s == null || !Match.running() || s.busy() || s.scareCooldown > 0 || target == null || runt.distanceTo(target) > cfg.runtScareReach
			|| !Sight.canSee(runt.level(), runt.getEyePosition(), target.getEyePosition(), runt)) {
			return false;
		}
		ServerLevel level = runt.level();
		int now = level.getServer().getTickCount();
		if (target instanceof ServerPlayer v && MatchManager.role(v).survivor() && !v.isSpectator() && !Lockers.isInside(v) && !MatchManager.isGrabbed(v)
			&& heldBy(v) == null) {
			frighten(runt, s, v);
			return true;
		}
		if (Townsfolk.isNpc(target)) {
			frightenNpc(runt, s, target, now);
			return true;
		}
		return false;
	}

	/** The fright itself: a scream, panic, a stagger, Michael told where; a hex or a grip on their back with those gifts. */
	private static void frighten(ServerPlayer runt, State s, ServerPlayer v) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		{
			Panic.add(v, cfg.runtScarePanic);
			Voices.play(v, Voices.Kind.SCREAM);
			level.playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.4f, 0.6f);
			level.playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.PHANTOM_BITE, SoundSource.HOSTILE, 0.9f, 0.6f);
			v.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, cfg.runtStaggerTicks, 2, false, false, false));
			reveal(level, v, cfg.runtScareRevealTicks);
			Notices.send(v, Component.translatable("halloween.runt.scared_you").withStyle(ChatFormatting.DARK_RED));
			if (s.has("hex")) {
				hex(v);
			}
			s.scareCooldown = s.scareCooldownMax = scareCooldown(s);
			AiStats.add(runt, "runt.scare");
		}
	}

	private static void frightenNpc(ServerPlayer runt, State s, Entity target, int now) {
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		{
			Townsfolk.frighten(target, runt, now);
			level.playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.VEX_CHARGE, SoundSource.HOSTILE, 1.2f, 0.7f);
			for (ServerPlayer shape : level.players()) {
				if (MatchManager.role(shape) == Role.SHAPE) {
					ServerPlayNetworking.send(shape, new HwNet.Impulse(List.of(target.getId()), List.of(target.position()), cfg.runtScareRevealTicks));
				}
			}
			s.scareCooldown = s.scareCooldownMax = scareCooldown(s) / 2;
			AiStats.add(runt, "runt.scare_npc");
		}
	}

	/**
	 * He throws a locker open: whoever is hiding in it tumbles out, frightened (Michael learns where), and with
	 * the clinger gift he is on their back at once. True if somebody was inside.
	 */
	public static boolean openLocker(ServerPlayer runt, BlockPos clicked) {
		State s = RUNTS.get(runt.getUUID());
		if (s == null || !Match.running() || s.busy()) {
			return false;
		}
		ServerLevel level = runt.level();
		net.minecraft.world.entity.LivingEntity hider = Lockers.throwOpen(runt, clicked);
		if (hider == null) {
			return false;
		}
		int now = level.getServer().getTickCount();
		if (hider instanceof ServerPlayer v && MatchManager.role(v).survivor()) {
			frighten(runt, s, v);
		} else if (Townsfolk.isNpc(hider)) {
			frightenNpc(runt, s, hider, now);
		}
		AiStats.add(runt, "runt.locker_pulled");
		return true;
	}

	/** Michael sees where someone is for a while. */
	private static void reveal(ServerLevel level, ServerPlayer v, int ticks) {
		for (ServerPlayer shape : level.players()) {
			if (MatchManager.role(shape) == Role.SHAPE) {
				ServerPlayNetworking.send(shape, new HwNet.Impulse(List.of(v.getId()), List.of(v.position()), ticks));
			}
		}
	}

	/** No breath to run with, no healing, for a while. */
	public static void hex(ServerPlayer v) {
		HwConfig cfg = HwConfig.get();
		HEXED.put(v.getUUID(), v.level().getServer().getTickCount() + cfg.runtHexTicks);
		Stamina.set(v, Math.min(Stamina.value(v), 0.15f));
		Notices.send(v, Component.translatable("halloween.runt.hexed", cfg.runtHexTicks / 20).withStyle(ChatFormatting.DARK_PURPLE));
		v.level().sendParticles(ParticleTypes.WITCH, v.getX(), v.getY() + 1, v.getZ(), 20, 0.3, 0.5, 0.3, 0.02);
	}

	public static boolean hexed(Entity who) {
		Integer until = who == null ? null : HEXED.get(who.getUUID());
		return until != null && who.level().getServer() != null && who.level().getServer().getTickCount() < until;
	}

	private static void startHold(ServerPlayer runt, State s, net.minecraft.world.entity.LivingEntity v) {
		HwConfig cfg = HwConfig.get();
		int now = runt.level().getServer().getTickCount();
		s.holding = v.getUUID();
		// A bot or a townsperson gets its hands off his in about the time a person would, give or take.
		s.botFreeAt = now + cfg.runtBotFreeTicks * 2 / 3 + runt.getRandom().nextInt(cfg.runtBotFreeTicks * 2 / 3 + 1);
		root(v, true);
		MatchManager.noJumping(v, true);
		// Hands round their throat, them clawing at his.
		GrabSession.anim(runt, HwNet.Anim.STRANGLE);
		GrabSession.anim(v, HwNet.Anim.STRUGGLE);
		if (v instanceof ServerPlayer p) {
			Notices.send(p, Component.translatable("halloween.runt.holding_you").withStyle(ChatFormatting.DARK_RED));
			if (!(p instanceof BotPlayer)) {
				Minigames.open(p, HwNet.Minigame.HANDS, p.blockPosition());
			}
		} else {
			Townsfolk.frighten(v, runt, now);
		}
		revealAny(runt.level(), v, cfg.runtScareRevealTicks * 3);
		for (ServerPlayer shape : runt.level().players()) {
			if (MatchManager.role(shape) == Role.SHAPE) {
				Notices.send(shape, Component.translatable("halloween.runt.holding", v.getDisplayName(), Landmarks.describe(runt.level(), v.position()))
					.withStyle(ChatFormatting.GOLD));
			}
		}
		AiStats.add(runt, "runt.hold");
	}

	/** Whoever he is clinging to: a survivor or a townsperson. */
	private static net.minecraft.world.entity.LivingEntity victim(ServerPlayer runt, State s) {
		if (s.holding == null) {
			return null;
		}
		ServerPlayer p = runt.level().getServer().getPlayerList().getPlayer(s.holding);
		if (p != null) {
			return p;
		}
		return runt.level().getEntity(s.holding) instanceof net.minecraft.world.entity.LivingEntity l ? l : null;
	}

	/** Michael sees where somebody is for a while (a survivor or a townsperson). */
	private static void revealAny(ServerLevel level, net.minecraft.world.entity.LivingEntity v, int ticks) {
		for (ServerPlayer shape : level.players()) {
			if (MatchManager.role(shape) == Role.SHAPE) {
				ServerPlayNetworking.send(shape, new HwNet.Impulse(List.of(v.getId()), List.of(v.position()), ticks));
			}
		}
	}

	private static void tickHold(ServerPlayer runt, State s, int now) {
		HwConfig cfg = HwConfig.get();
		net.minecraft.world.entity.LivingEntity v = victim(runt, s);
		boolean gone = v == null || v.isRemoved() || !v.isAlive() || v.isSpectator()
			|| v instanceof ServerPlayer p && !MatchManager.role(p).survivor();
		if (gone || s.stunTicks > 0) {
			release(runt, s, true);
			return;
		}
		if (MatchManager.isHeld(v) || v instanceof ServerPlayer p && MatchManager.isGrabbed(p)) {
			// Michael has them now: the job is done.
			gain(runt, s, cfg.runtFearHeldForMichael);
			release(runt, s, false);
			return;
		}
		if (!(v instanceof ServerPlayer) || v instanceof BotPlayer) {
			if (now >= s.botFreeAt) {
				freed(v);
				return;
			}
		}
		if (now % 100 == 0) {
			// Michael keeps knowing where they are while they are pinned.
			revealAny(runt.level(), v, 110);
		}
		// On their back, clinging.
		Vec3 back = v.position().subtract(v.getLookAngle().multiply(1, 0, 1).normalize().scale(0.45));
		runt.teleportTo(runt.level(), back.x, v.getY() + 0.8, back.z, java.util.Set.of(), v.getYRot(), 20f, false);
		if (now % 20 == 0 && v instanceof ServerPlayer p) {
			Panic.add(p, 0.03f);
		}
		if (v instanceof ServerPlayer p && !(p instanceof BotPlayer) && !Minigames.active(p)) {
			// Esc doesn't get rid of him: the hands are still there.
			Minigames.open(p, HwNet.Minigame.HANDS, p.blockPosition());
		}
	}

	/** Lets go: knocked off, thrown off, or Michael took over. */
	private static void release(ServerPlayer runt, State s, boolean cooldown) {
		if (s.holding == null) {
			return;
		}
		net.minecraft.world.entity.LivingEntity v = victim(runt, s);
		s.holding = null;
		GrabSession.anim(runt, HwNet.Anim.NONE);
		if (v != null) {
			root(v, false);
			if (v instanceof ServerPlayer p) {
				// (Townsfolk never jump: they keep their no-jumping.)
				MatchManager.noJumping(p, false);
				Minigames.close(p);
			}
			if (!MatchManager.isHeld(v)) {
				GrabSession.anim(v, HwNet.Anim.NONE);
			}
			// Off their back onto the ground behind.
			Vec3 behind = v.position().subtract(v.getLookAngle().multiply(1, 0, 1).normalize().scale(0.9));
			runt.teleportTo(runt.level(), behind.x, v.getY(), behind.z, java.util.Set.of(), runt.getYRot(), 0f, false);
		}
		if (cooldown) {
			s.scareCooldown = s.scareCooldownMax = Math.max(s.scareCooldown, HwConfig.get().runtHoldCooldownTicks);
		}
	}

	/** Who is clinging on to this survivor (or townsperson), if anyone. */
	public static ServerPlayer heldBy(net.minecraft.world.entity.LivingEntity victim) {
		for (Map.Entry<UUID, State> e : RUNTS.entrySet()) {
			if (victim.getUUID().equals(e.getValue().holding)) {
				return victim.level().getServer().getPlayerList().getPlayer(e.getKey());
			}
		}
		return null;
	}

	/**
	 * The one he holds can't shove him off: only getting his hands off their throat does (the hands game). A
	 * shove from them goes to nothing. True if they are held.
	 */
	public static boolean struggle(ServerPlayer victim) {
		return heldBy(victim) != null;
	}

	/** The hands game won (or a bot's or a townsperson's hands got free): he is thrown off and lands on his back. */
	public static void freed(net.minecraft.world.entity.LivingEntity victim) {
		ServerPlayer runt = heldBy(victim);
		State s = runt == null ? null : RUNTS.get(runt.getUUID());
		if (s == null) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		release(runt, s, true);
		knock(runt, victim, cfg.runtShoveStrength);
		floor(runt, s, cfg.runtShoveStunTicks);
		if (victim instanceof ServerPlayer p) {
			Match.score(p, cfg.scoreShakeRunt, Component.translatable("halloween.score.shake_runt"));
		}
		AiStats.add(victim, "runt.shaken_off");
	}

	// ---------------------------------------------------------------- fighting him off

	private static void knock(ServerPlayer runt, Entity from, double strength) {
		Vec3 away = runt.position().subtract(from.position()).multiply(1, 0, 1);
		away = away.lengthSqr() < 1.0E-4 ? from.getLookAngle().multiply(1, 0, 1) : away;
		away = away.normalize().scale(strength);
		runt.setDeltaMovement(away.x, 0.35, away.z);
		runt.hurtMarked = true;
	}

	/** Down on his back for a while (and up again at the end, see tick). */
	private static void floor(ServerPlayer runt, State s, int ticks) {
		root(runt, false);
		s.stunTicks = ticks;
		GrabSession.anim(runt, HwNet.Anim.FALL);
	}

	/** The one he is clinging to: they can't do anything to him themselves (only the hands game). */
	private static boolean heldVictim(State s, ServerPlayer by) {
		return s.holding != null && by != null && s.holding.equals(by.getUUID());
	}

	/** A shove: thrown back and off his feet for a moment (off a friend's back too). Not in mid-pounce. */
	public static void shove(ServerPlayer runt, ServerPlayer by) {
		State s = RUNTS.get(runt.getUUID());
		if (s == null || s.leapTicks > 0 || heldVictim(s, by)) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		release(runt, s, true);
		knock(runt, by, cfg.runtShoveStrength);
		floor(runt, s, cfg.runtShoveStunTicks);
		runt.level().playSound(null, runt.getX(), runt.getY(), runt.getZ(), HwSounds.PUSH, SoundSource.PLAYERS, 1.2f, 1.3f);
		runt.level().playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.VEX_HURT, SoundSource.HOSTILE, 1f, 0.7f);
		AiStats.add(by, "runt.shoved");
	}

	/**
	 * A blow with something (or a thrown brick): down for longer than a shove, off a friend's back too. Not in
	 * mid-pounce, and never out of the game: he gets up and goes on.
	 */
	public static void hit(ServerPlayer runt, ServerPlayer by, float force) {
		State s = RUNTS.get(runt.getUUID());
		if (s == null || s.leapTicks > 0 || heldVictim(s, by)) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		release(runt, s, true);
		if (by != null) {
			knock(runt, by, cfg.runtShoveStrength * 0.7);
		}
		floor(runt, s, cfg.runtHitStunTicks);
		runt.level().playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.2f, 1.2f);
		runt.level().playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.VEX_HURT, SoundSource.HOSTILE, 1.2f, 0.6f);
		if (by != null) {
			Match.score(by, cfg.scoreHitRunt, Component.translatable("halloween.score.hit_runt"));
		}
		AiStats.add(runt, "runt.hit");
	}

	/** Shot or burnt by a flare: down for a good while (off a friend's back too), then up and on again. */
	public static void shot(ServerPlayer runt, ServerPlayer by) {
		State s = RUNTS.get(runt.getUUID());
		if (s == null || heldVictim(s, by)) {
			return;
		}
		HwConfig cfg = HwConfig.get();
		ServerLevel level = runt.level();
		release(runt, s, true);
		s.leapTicks = 0;
		floor(runt, s, cfg.runtShotStunTicks);
		level.sendParticles(ParticleTypes.SMOKE, runt.getX(), runt.getY() + 0.5, runt.getZ(), 12, 0.2, 0.2, 0.2, 0.02);
		level.playSound(null, runt.getX(), runt.getY(), runt.getZ(), SoundEvents.VEX_HURT, SoundSource.HOSTILE, 1.5f, 0.5f);
		if (by != null) {
			Match.score(by, cfg.scoreHitRunt, Component.translatable("halloween.score.hit_runt"));
		}
		AiStats.add(runt, "runt.shot");
	}

	/** Snapped in a bear trap. */
	public static void trap(ServerPlayer runt) {
		State s = RUNTS.get(runt.getUUID());
		if (s != null) {
			release(runt, s, true);
			s.trappedTicks = HwConfig.get().runtTrappedTicks;
		}
	}

	// ---------------------------------------------------------------- tricks and trading places

	/** Mischief: unlock a locked door, cut a phone, throw a breaker. True if he did something. */
	public static boolean mischief(ServerPlayer runt, BlockPos pos) {
		State s = RUNTS.get(runt.getUUID());
		HwConfig cfg = HwConfig.get();
		if (s == null || !Match.running() || !s.has("mischief") || s.busy() || s.trickCooldown > 0
			|| Vec3.atCenterOf(pos).distanceTo(runt.getEyePosition()) > 4.5) {
			return false;
		}
		ServerLevel level = runt.level();
		BlockState st = level.getBlockState(pos);
		String did = null;
		if (DoorLocks.isDoor(st) && DoorLocks.isLocked(level, pos)) {
			DoorLocks.forceUnlock(level, pos);
			did = "door";
		} else if (st.is(HwBlocks.PHONE) && Exits.phoneWorks(pos.immutable())) {
			Exits.cutPhone(level, pos.immutable());
			did = "phone";
		} else if (LightSystem.isOn(level, pos)) {
			LightSystem.flip(level, pos, false);
			did = "breaker";
		}
		if (did == null) {
			return false;
		}
		level.playSound(null, pos, SoundEvents.VEX_AMBIENT, SoundSource.HOSTILE, 1f, 0.8f);
		s.trickCooldown = s.trickCooldownMax = cfg.runtMischiefCooldownTicks;

		Notices.send(runt, Component.translatable("halloween.runt.mischief." + did).withStyle(ChatFormatting.DARK_PURPLE));
		AiStats.add(runt, "runt.mischief." + did);
		return true;
	}

	/** His own key: trading places with Michael (that gift only). */
	public static boolean special(ServerPlayer runt) {
		State s = RUNTS.get(runt.getUUID());
		HwConfig cfg = HwConfig.get();
		if (s == null || !Match.running() || !s.has("swap") || s.busy() || s.specialCooldown > 0) {
			return false;
		}
		ServerLevel level = runt.level();
		ServerPlayer shape = null;
		for (ServerPlayer p : level.players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				shape = p;
			}
		}
		ShapeState st = shape == null ? null : MatchManager.shapeState(shape);
		if (st == null || st.busy() || st.shadowTicks > 0) {
			Notices.send(runt, Component.translatable("halloween.runt.swap_not_now").withStyle(ChatFormatting.GRAY));
			return false;
		}
		Vec3 a = runt.position();
		Vec3 b = shape.position();
		float ya = runt.getYRot();
		float yb = shape.getYRot();
		runt.teleportTo(level, b.x, b.y, b.z, java.util.Set.of(), yb, 0f, false);
		shape.teleportTo(level, a.x, a.y, a.z, java.util.Set.of(), ya, 0f, false);
		for (Vec3 at : new Vec3[]{a, b}) {
			level.sendParticles(ParticleTypes.SMOKE, at.x, at.y + 1, at.z, 30, 0.3, 0.6, 0.3, 0.01);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.ILLUSIONER_MIRROR_MOVE, SoundSource.HOSTILE, 0.7f, 0.6f);
		}
		s.specialCooldown = s.specialCooldownMax = cfg.runtSwapCooldownTicks;
		Notices.send(shape, Component.translatable("halloween.runt.swapped_shape").withStyle(ChatFormatting.DARK_PURPLE));
		AiStats.add(runt, "runt.swap");
		return true;
	}

	// ---------------------------------------------------------------- what the screens show

	/** Survivors he can smell (that gift): everyone near him, through walls. */
	static List<Integer> scent(ServerPlayer runt, State s) {
		List<Integer> out = new ArrayList<>();
		if (!s.has("scent")) {
			return out;
		}
		for (ServerPlayer v : runt.level().players()) {
			if (MatchManager.role(v).survivor() && !v.isSpectator() && v.distanceTo(runt) < HwConfig.get().runtScentRange) {
				out.add(v.getId());
			}
		}
		return out;
	}

	private static void sync(ServerPlayer runt, State s) {
		HwConfig cfg = HwConfig.get();
		int[] at = cfg.runtLevelFear;
		float prev = s.level == 0 ? 0 : at[Math.min(s.level, at.length) - 1];
		float next = s.level >= at.length ? prev : at[s.level];
		int michael = -1;
		List<Integer> scent = scent(runt, s);
		int held = -1;
		if (s.holding != null) {
			ServerPlayer v = runt.level().getServer().getPlayerList().getPlayer(s.holding);
			held = v == null ? -1 : v.getId();
		}
		for (ServerPlayer p : runt.level().players()) {
			if (MatchManager.role(p) == Role.SHAPE) {
				michael = p.getId();
				ServerPlayNetworking.send(p, new HwNet.RuntInfo(runt.getId(), scent, held, s.brain != null, status(s)));
			}
		}
		if (!(runt instanceof BotPlayer)) {
			ServerPlayNetworking.send(runt, new HwNet.RuntHud(s.fear, s.level, prev, next, s.scareCooldown, s.scareCooldownMax, s.specialCooldown,
				s.specialCooldownMax, s.trickCooldown, s.trickCooldownMax, List.of(s.picks[0], s.picks[1], s.picks[2]), s.pending(), Math.max(s.stunTicks, s.trappedTicks),
				s.holding == null ? 0 : 1, michael, scent, s.charge(), s.pounceCooldown, s.pounceCooldownMax, s.knockCooldown));
		}
	}

	/** End of the match: his share of the points (they go to whoever played him). */
	static Map<UUID, Integer> scores() {
		Map<UUID, Integer> out = new HashMap<>();
		RUNTS.forEach((id, s) -> out.put(id, s.score));
		return out;
	}

	static int totalScore() {
		int n = 0;
		for (State s : RUNTS.values()) {
			n += s.score;
		}
		return n;
	}

	static Iterator<Map.Entry<UUID, State>> entries() {
		return RUNTS.entrySet().iterator();
	}
}
