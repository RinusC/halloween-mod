package dev.halloween.game;

import dev.halloween.Role;
import dev.halloween.net.HwNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarvedPumpkinBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Each survivor plays a little differently by who they are (Skins). Most have a trait that works like a perk they
 * always have (on top of their build); four have something to do on their own key; two have a trait of their own.
 * <ul>
 * <li>Laurie — he is after her: stalked more slowly (Elusory); <b>I feel him</b>: for a few seconds he shows through
 * walls to her.</li>
 * <li>Annie — always on the phone: talks townsfolk round and calls the police faster (Smooth Talker).</li>
 * <li>Lynda — "totally": panics more slowly (Unphased).</li>
 * <li>Rachel — the scariest pumpkin: <b>pumpkin decoy</b> — a lit jack-o'-lantern set down that rattles and creaks
 * for a while, drawing him to it.</li>
 * <li>Tanya — the trick-or-treat bag: searches faster (Quick Search).</li>
 * <li>Jennifer — one more chapter: <b>keen eye</b> — what can get them out, hidden nearby, shows for a few seconds.</li>
 * <li>Alexis — someone in the yard: feels him near when he cannot be seen (a sign on her screen).</li>
 * <li>Bob — big and stubborn: more health (Tough).</li>
 * <li>Eric — fixes the old car: the repair kit often lasts (Repair Expert).</li>
 * <li>Marcus — the flashlight under the chin: his flashlight reaches further and blinds from wider.</li>
 * <li>Thomas — raking leaves: his footsteps carry half as far (Quiet).</li>
 * <li>Richard — read about the escaped patient: <b>warn</b> — Michael in his sight is shown to every survivor.</li>
 * </ul>
 */
public final class Heroes {
	public enum Skill {
		NONE, FEEL, DECOY, KEEN_EYE, WARN,
		// (Added after the first four: the client knows them by number.)
		GOSSIP, CALM, FULL_BAG, LULLABY, RAM, TINKER, FLASH, LIE_LOW
	}

	private static final int FEEL_TICKS = 100;
	private static final int DECOY_TICKS = 300;
	private static final int KEEN_TICKS = 160;
	private static final double KEEN_RANGE = 32;
	private static final int WARN_TICKS = 120;
	private static final int GOSSIP_TICKS = 120;
	private static final int CALM_TICKS = 100;
	/** Tanya's full bag: the next search is near instant, if done within this long. */
	private static final int FULL_BAG_TICKS = 600;
	private static final int FULL_BAG_SEARCH_TICKS = 6;
	private static final int LULLABY_TICKS = 160;
	private static final int RAM_TICKS = 8;
	private static final double RAM_SPEED = 0.9;
	private static final int TINKER_TICKS = 300;
	private static final int FLASH_TICKS = 60;
	private static final double FLASH_RANGE = 12;
	private static final int LIE_LOW_TICKS = 200;
	/** Until when each one's skill is at work (the ones that last), and Bob's way while he rams. */
	private static final Map<UUID, Integer> ACTIVE_UNTIL = new HashMap<>();
	private static final Map<UUID, Vec3> RAM_DIR = new HashMap<>();
	private static final Map<UUID, Vec3> LIE_AT = new HashMap<>();

	/** Until when each can use their skill again, and what the client was last told. */
	private static final Map<UUID, Integer> READY_AT = new HashMap<>();
	private static final Map<UUID, String> SENT = new HashMap<>();
	/** Rachel's pumpkins: where, until when, the next rattle. */
	private static final Map<BlockPos, int[]> DECOYS = new HashMap<>();

	private Heroes() {
	}

	public static String hero(ServerPlayer player) {
		return Skins.heroOf(player);
	}

	public static boolean is(ServerPlayer player, String name) {
		return name.equals(hero(player));
	}

	/** The perk this survivor always has by who they are, or null. */
	public static Perks.Perk innate(ServerPlayer player) {
		return innate(hero(player));
	}

	/** The perk this survivor always has, by name (see HeroInfo). */
	public static Perks.Perk innate(String h) {
		return h == null ? null : switch (h) {
			case "laurie" -> Perks.Perk.ELUSORY;
			case "annie" -> Perks.Perk.SMOOTH_TALKER;
			case "lynda" -> Perks.Perk.UNPHASED;
			case "tanya" -> Perks.Perk.QUICK_SEARCH;
			case "bob" -> Perks.Perk.TOUGH;
			case "eric" -> Perks.Perk.REPAIR_EXPERT;
			case "thomas" -> Perks.Perk.QUIET;
			default -> null;
		};
	}

	public static Skill skill(String hero) {
		return hero == null ? Skill.NONE : switch (hero) {
			case "laurie" -> Skill.FEEL;
			case "rachel" -> Skill.DECOY;
			case "jennifer" -> Skill.KEEN_EYE;
			case "richard" -> Skill.WARN;
			case "annie" -> Skill.GOSSIP;
			case "lynda" -> Skill.CALM;
			case "tanya" -> Skill.FULL_BAG;
			case "alexis" -> Skill.LULLABY;
			case "bob" -> Skill.RAM;
			case "eric" -> Skill.TINKER;
			case "marcus" -> Skill.FLASH;
			case "thomas" -> Skill.LIE_LOW;
			default -> Skill.NONE;
		};
	}

	/** How long the skill shows what it shows (ticks; 0 for none). */
	public static int duration(Skill skill) {
		return switch (skill) {
			case FEEL -> FEEL_TICKS;
			case DECOY -> DECOY_TICKS;
			case KEEN_EYE -> KEEN_TICKS;
			case WARN -> WARN_TICKS;
			case GOSSIP -> GOSSIP_TICKS;
			case CALM -> CALM_TICKS;
			case FULL_BAG -> FULL_BAG_TICKS;
			case LULLABY -> LULLABY_TICKS;
			case RAM -> RAM_TICKS;
			case TINKER -> TINKER_TICKS;
			case FLASH -> FLASH_TICKS;
			case LIE_LOW -> LIE_LOW_TICKS;
			default -> 0;
		};
	}

	public static int cooldown(Skill skill) {
		return switch (skill) {
			case FEEL -> 20 * 75;
			case DECOY -> 20 * 90;
			case KEEN_EYE -> 20 * 60;
			case WARN -> 20 * 60;
			case GOSSIP, FULL_BAG -> 20 * 60;
			case CALM, TINKER, FLASH -> 20 * 75;
			case LULLABY, RAM, LIE_LOW -> 20 * 90;
			default -> 0;
		};
	}

	public static void clear(MinecraftServer server) {
		READY_AT.clear();
		ACTIVE_UNTIL.clear();
		RAM_DIR.clear();
		for (UUID id : LIE_AT.keySet()) {
			if (server.getPlayerList().getPlayer(id) instanceof ServerPlayer p) {
				p.setInvisible(false);
			}
		}
		LIE_AT.clear();
		SENT.clear();
		ServerLevel level = server.overworld();
		for (BlockPos pos : DECOYS.keySet()) {
			if (level.getBlockState(pos).is(Blocks.JACK_O_LANTERN)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
		DECOYS.clear();
	}

	/** The skill key. */
	public static void use(ServerPlayer player) {
		if (!Match.running() || Match.frozen() || player.isSpectator() || MatchManager.isGrabbed(player) || Lockers.isInside(player)) {
			return;
		}
		Skill skill = skill(hero(player));
		int now = player.level().getServer().getTickCount();
		if (skill == Skill.NONE || now < READY_AT.getOrDefault(player.getUUID(), 0)) {
			return;
		}
		boolean done = switch (skill) {
			case FEEL -> feel(player);
			case DECOY -> decoy(player, now);
			case KEEN_EYE -> keenEye(player);
			case WARN -> warn(player);
			case GOSSIP -> gossip(player);
			case CALM -> calm(player, now);
			case FULL_BAG, LULLABY, TINKER -> start(player, skill, now);
			case RAM -> ram(player, now);
			case FLASH -> flash(player);
			case LIE_LOW -> lieLow(player, now);
			default -> false;
		};
		if (done) {
			READY_AT.put(player.getUUID(), now + cooldown(skill));
			AiStats.add(player, "hero." + skill.name().toLowerCase(java.util.Locale.ROOT));
		}
	}

	private static boolean feel(ServerPlayer player) {
		ServerPlayer shape = Senses.shape(player.level());
		if (shape == null) {
			return false;
		}
		ServerPlayNetworking.send(player, new HwNet.HeroReveal(HwNet.HeroReveal.FEEL, FEEL_TICKS, shape.getId(), List.of()));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 0.6f, 0.8f);
		return true;
	}

	private static boolean decoy(ServerPlayer player, int now) {
		ServerLevel level = player.level();
		Vec3 look = player.getLookAngle().multiply(1, 0, 1);
		look = look.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : look.normalize();
		BlockPos at = BlockPos.containing(player.position().add(look.scale(1.2)));
		if (!level.getBlockState(at).isAir() || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) {
			at = player.blockPosition();
			if (!level.getBlockState(at).isAir()) {
				return false;
			}
		}
		level.setBlock(at, Blocks.JACK_O_LANTERN.defaultBlockState().setValue(CarvedPumpkinBlock.FACING, player.getDirection().getOpposite()), Block.UPDATE_ALL);
		DECOYS.put(at.immutable(), new int[]{now + DECOY_TICKS, now + 20});
		level.playSound(null, at, SoundEvents.PUMPKIN_CARVE, SoundSource.BLOCKS, 1f, 0.9f);
		return true;
	}

	private static boolean keenEye(ServerPlayer player) {
		List<Vec3> spots = new ArrayList<>();
		for (Map.Entry<BlockPos, HwItems.Kind> e : Loot.hidden().entrySet()) {
			if (!Loot.isSearched(e.getKey()) && Loot.escapeItem(e.getValue()) && Vec3.atCenterOf(e.getKey()).distanceTo(player.position()) <= KEEN_RANGE) {
				spots.add(Vec3.atCenterOf(e.getKey()));
			}
		}
		ServerPlayNetworking.send(player, new HwNet.HeroReveal(HwNet.HeroReveal.KEEN, KEEN_TICKS, -1, spots));
		if (spots.isEmpty()) {
			player.sendOverlayMessage(Component.translatable("halloween.hero.keen.none").withStyle(ChatFormatting.GRAY));
		}
		return true;
	}

	private static boolean warn(ServerPlayer player) {
		ServerPlayer shape = Senses.shape(player.level());
		if (shape == null || shape.isSpectator() || player.distanceTo(shape) > 48
			|| !Sight.canSee(player.level(), player.getEyePosition(), FocusPoints.bodyCenter(shape), player)
			|| Sight.angleTo(player.getEyePosition(), player.getViewVector(1f), FocusPoints.bodyCenter(shape)) > 60) {
			player.sendOverlayMessage(Component.translatable("halloween.hero.warn.unseen").withStyle(ChatFormatting.GRAY));
			return false;
		}
		Pings.killer(player, shape, WARN_TICKS);
		return true;
	}

	/** Every tick: Rachel's pumpkins rattling, Alexis feeling him near, what each client shows of their skill. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		if (!Match.running()) {
			if (!DECOYS.isEmpty() || !READY_AT.isEmpty()) {
				clear(server);
			}
			return;
		}
		ServerLevel level = server.overworld();
		var it = DECOYS.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			BlockPos pos = e.getKey();
			int[] t = e.getValue();
			if (now >= t[0] || !level.getBlockState(pos).is(Blocks.JACK_O_LANTERN)) {
				if (level.getBlockState(pos).is(Blocks.JACK_O_LANTERN)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6f, 1f);
				}
				it.remove();
				continue;
			}
			if (now >= t[1]) {
				// A rattle and a creak, as if someone were there: he hears it (the bot by ear, a player by the mark).
				t[1] = now + 40 + level.getRandom().nextInt(20);
				Vec3 at = Vec3.atCenterOf(pos);
				level.playSound(null, at.x, at.y, at.z, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.4f, 0.7f + level.getRandom().nextFloat() * 0.3f);
				HwNet.NoiseBurst burst = new HwNet.NoiseBurst(at, true);
				for (ServerPlayer p : level.players()) {
					if (MatchManager.role(p) == Role.SHAPE && p.position().distanceTo(at) <= 40) {
						ServerPlayNetworking.send(p, burst);
					}
				}
			}
		}
		tickActive(server, now);
		if (now % 10 != 0) {
			return;
		}
		ServerPlayer shape = Senses.shape(level);
		ShapeState st = shape == null ? null : MatchManager.shapeState(shape);
		for (ServerPlayer p : level.players()) {
			if (Bots.isBot(p)) {
				continue;
			}
			String hero = hero(p);
			boolean alert = false;
			if ("alexis".equals(hero) && st != null && st.shadowTicks == 0 && !shape.isSpectator() && !p.isSpectator() && p.distanceTo(shape) <= 20) {
				alert = !Sight.canSee(level, p.getEyePosition(), FocusPoints.bodyCenter(shape), p);
			}
			Skill skill = skill(hero);
			int left = Math.max(0, READY_AT.getOrDefault(p.getUUID(), 0) - now);
			String state = (hero == null ? "" : hero) + "|" + skill + "|" + (left > 0) + "|" + alert;
			if (!state.equals(SENT.get(p.getUUID()))) {
				SENT.put(p.getUUID(), state);
				ServerPlayNetworking.send(p, new HwNet.HeroSync(hero == null ? "" : hero, skill.ordinal(), left, cooldown(skill), alert));
			}
		}
	}

	/** A skill that works for a while (Tanya's bag, Alexis's lullaby, Eric's tinkering): from now on. */
	private static boolean start(ServerPlayer player, Skill skill, int now) {
		ACTIVE_UNTIL.put(player.getUUID(), now + duration(skill));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7f, 1.2f);
		return true;
	}

	/** Is this one's skill at work now? */
	public static boolean active(net.minecraft.world.entity.Entity who, Skill skill) {
		if (!(who instanceof ServerPlayer p) || skill(hero(p)) != skill) {
			return false;
		}
		Integer until = ACTIVE_UNTIL.get(p.getUUID());
		return until != null && p.level().getServer().getTickCount() < until;
	}

	/** Used up before its time (Tanya's bag on the search it sped up). */
	static void spend(ServerPlayer player) {
		ACTIVE_UNTIL.remove(player.getUUID());
	}

	/** Tanya's search while the bag is full: near instant (null: as usual). */
	static Integer searchTicks(ServerPlayer player) {
		return active(player, Skill.FULL_BAG) ? FULL_BAG_SEARCH_TICKS : null;
	}

	/** Thomas lying low: not there for anyone hunting (see MatchManager.exposed). */
	public static boolean lyingLow(net.minecraft.world.entity.Entity who) {
		return LIE_AT.containsKey(who.getUUID()) && active(who, Skill.LIE_LOW);
	}

	/** Annie's gossip: every survivor sees every other through walls for a while. */
	private static boolean gossip(ServerPlayer player) {
		List<ServerPlayer> team = new ArrayList<>();
		for (ServerPlayer p : player.level().players()) {
			if (MatchManager.role(p).survivor() && !p.isSpectator()) {
				team.add(p);
			}
		}
		for (ServerPlayer viewer : team) {
			if (Bots.isBot(viewer)) {
				continue;
			}
			for (ServerPlayer other : team) {
				if (other != viewer) {
					ServerPlayNetworking.send(viewer, new HwNet.HeroReveal(HwNet.HeroReveal.ALLY, GOSSIP_TICKS, other.getId(), List.of()));
				}
			}
			if (viewer != player) {
				Notices.send(viewer, Component.translatable("halloween.hero.gossip.heard", player.getDisplayName()).withStyle(ChatFormatting.AQUA));
			}
		}
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.8f, 1.4f);
		return true;
	}

	/** Lynda: "totally fine" — no panic, full breath, running free for a few seconds. */
	private static boolean calm(ServerPlayer player, int now) {
		Panic.set(player, 0f);
		Stamina.set(player, 1f);
		return start(player, Skill.CALM, now);
	}

	/** Bob: a charge with his shoulder the way he looks; Michael in the way goes down, a door in the way gives. */
	private static boolean ram(ServerPlayer player, int now) {
		Vec3 look = player.getLookAngle().multiply(1, 0, 1);
		if (look.lengthSqr() < 1e-4 || !player.onGround()) {
			return false;
		}
		RAM_DIR.put(player.getUUID(), look.normalize());
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.PLAYERS, 0.35f, 1.6f);
		return start(player, Skill.RAM, now);
	}

	/** Marcus: his flashlight full on under his chin, in Michael's face: blinds him in a cone ahead, a light in hand or not. */
	private static boolean flash(ServerPlayer player) {
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		for (ServerPlayer p : level.players()) {
			ShapeState st = MatchManager.shapeState(p);
			if (st == null || p.isSpectator() || p.distanceTo(player) > FLASH_RANGE
				|| Sight.angleTo(eye, player.getViewVector(1f), p.getEyePosition()) > 30 || !Sight.canSee(level, eye, p.getEyePosition(), player)) {
				continue;
			}
			st.blindTicks = Math.max(st.blindTicks, FLASH_TICKS);
			st.litTicks = Math.max(st.litTicks, FLASH_TICKS);
			st.senseHeld = false;
		}
		Vec3 at = eye.add(player.getLookAngle().scale(1.5));
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.15);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.6f, 1.8f);
		return true;
	}

	/** Thomas: down among the leaves, still — gone for Michael and the bots while he doesn't move. */
	private static boolean lieLow(ServerPlayer player, int now) {
		if (!player.onGround()) {
			return false;
		}
		LIE_AT.put(player.getUUID(), player.position());
		player.setInvisible(true);
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.PLAYERS, 0.8f, 0.8f);
		return start(player, Skill.LIE_LOW, now);
	}

	/** Every tick: Bob's charge carrying him on, Thomas giving himself away by moving, skills running out. */
	private static void tickActive(MinecraftServer server, int now) {
		var rams = RAM_DIR.entrySet().iterator();
		while (rams.hasNext()) {
			var e = rams.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null || !active(p, Skill.RAM) || MatchManager.isGrabbed(p)) {
				rams.remove();
				continue;
			}
			Vec3 dir = e.getValue();
			p.setDeltaMovement(dir.x * RAM_SPEED, p.getDeltaMovement().y, dir.z * RAM_SPEED);
			p.hurtMarked = true;
			ServerLevel level = p.level();
			boolean hit = false;
			for (ServerPlayer shape : level.players()) {
				if (MatchManager.shapeState(shape) != null && !shape.isSpectator() && shape.distanceTo(p) < 1.6) {
					Fight.stun(shape, 1.5f, p);
					level.playSound(null, shape.getX(), shape.getY(), shape.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.2f, 0.7f);
					hit = true;
				}
			}
			BlockPos ahead = BlockPos.containing(p.position().add(dir.scale(0.9)));
			if (!hit && (DoorLocks.ram(level, ahead) || DoorLocks.ram(level, ahead.above()))) {
				hit = true;
			}
			if (hit) {
				rams.remove();
				spend(p);
			}
		}
		var lows = LIE_AT.entrySet().iterator();
		while (lows.hasNext()) {
			var e = lows.next();
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p == null) {
				lows.remove();
				continue;
			}
			Vec3 at = e.getValue();
			if (!active(p, Skill.LIE_LOW) || Math.hypot(p.getX() - at.x, p.getZ() - at.z) > 0.25 || p.isSpectator()) {
				p.setInvisible(false);
				lows.remove();
			}
		}
	}

	/** Marcus: how much further his flashlight reaches and how much wider it blinds. */
	public static double flashlightReach(ServerPlayer player) {
		return is(player, "marcus") ? 1.35 : 1.0;
	}
}
