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
		NONE, FEEL, DECOY, KEEN_EYE, WARN
	}

	private static final int FEEL_TICKS = 100;
	private static final int DECOY_TICKS = 300;
	private static final int KEEN_TICKS = 160;
	private static final double KEEN_RANGE = 32;
	private static final int WARN_TICKS = 120;

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
		String h = hero(player);
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
			default -> Skill.NONE;
		};
	}

	private static int cooldown(Skill skill) {
		return switch (skill) {
			case FEEL -> 20 * 75;
			case DECOY -> 20 * 90;
			case KEEN_EYE -> 20 * 60;
			case WARN -> 20 * 60;
			default -> 0;
		};
	}

	public static void clear(MinecraftServer server) {
		READY_AT.clear();
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

	/** Marcus: how much further his flashlight reaches and how much wider it blinds. */
	public static double flashlightReach(ServerPlayer player) {
		return is(player, "marcus") ? 1.35 : 1.0;
	}
}
