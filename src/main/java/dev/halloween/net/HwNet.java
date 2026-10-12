package dev.halloween.net;

import dev.halloween.Halloween;
import dev.halloween.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** All custom packets. S2C = server to client, C2S = client to server. */
public final class HwNet {
	private HwNet() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
		return new CustomPacketPayload.Type<>(Halloween.id(path));
	}

	private static void writeVec(RegistryFriendlyByteBuf buf, Vec3 v) {
		buf.writeDouble(v.x);
		buf.writeDouble(v.y);
		buf.writeDouble(v.z);
	}

	private static Vec3 readVec(RegistryFriendlyByteBuf buf) {
		return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
	}

	// ---------------------------------------------------------------- S2C

	/** Broadcast to everyone: who plays what, and whether the Shape is currently hidden in Shape Jump. */
	public record WorldSync(List<RoleEntry> roles, int shapeEntityId, boolean shadow) implements CustomPacketPayload {
		public static final Type<WorldSync> TYPE = payloadType("world_sync");
		public static final StreamCodec<RegistryFriendlyByteBuf, WorldSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.roles.size());
			for (RoleEntry e : v.roles) {
				buf.writeUUID(e.player());
				buf.writeVarInt(e.role().ordinal());
			}
			buf.writeVarInt(v.shapeEntityId);
			buf.writeBoolean(v.shadow);
		}, buf -> {
			int n = buf.readVarInt();
			List<RoleEntry> roles = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				roles.add(new RoleEntry(buf.readUUID(), Role.byId(buf.readVarInt())));
			}
			return new WorldSync(roles, buf.readVarInt(), buf.readBoolean());
		});

		@Override
		public Type<WorldSync> type() {
			return TYPE;
		}
	}

	public record RoleEntry(UUID player, Role role) {
	}

	/**
	 * Sent only to the Shape several times per second.
	 * jumpBlock: 0 ready, 1 in light, 2 watched, 3 cooling down, 4 busy. While in Shape Jump it tells whether
	 * leaving is allowed right now; shadowTicks then counts how long the jump has lasted.
	 */
	public record ShapeHud(
		float bloodthirst, int score, float fatigue, boolean sense, boolean focus,
		int jumpCooldown, int jumpCooldownMax, int shadowTicks, int jumpBlock,
		int dashCooldown, int dashCooldownMax, int dashTicks,
		int impulseCooldown, int impulseCooldownMax,
		int pushCooldown, int grabCooldown, int attackCooldown,
		List<VictimInfo> victims, List<Vec3> beams, List<Component> objectives,
		float stun, int stunnedTicks, boolean blind
	) implements CustomPacketPayload {
		public static final Type<ShapeHud> TYPE = payloadType("shape_hud");
		public static final StreamCodec<RegistryFriendlyByteBuf, ShapeHud> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeFloat(v.bloodthirst);
			buf.writeVarInt(v.score);
			buf.writeFloat(v.fatigue);
			buf.writeBoolean(v.sense);
			buf.writeBoolean(v.focus);
			buf.writeVarInt(v.jumpCooldown);
			buf.writeVarInt(v.jumpCooldownMax);
			buf.writeVarInt(v.shadowTicks);
			buf.writeVarInt(v.jumpBlock);
			buf.writeVarInt(v.dashCooldown);
			buf.writeVarInt(v.dashCooldownMax);
			buf.writeVarInt(v.dashTicks);
			buf.writeVarInt(v.impulseCooldown);
			buf.writeVarInt(v.impulseCooldownMax);
			buf.writeVarInt(v.pushCooldown);
			buf.writeVarInt(v.grabCooldown);
			buf.writeVarInt(v.attackCooldown);
			buf.writeVarInt(v.victims.size());
			for (VictimInfo e : v.victims) {
				buf.writeVarInt(e.entityId());
				buf.writeFloat(e.stalk());
				buf.writeBoolean(e.marked());
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, e.name());
			}
			buf.writeVarInt(v.beams.size());
			for (Vec3 b : v.beams) {
				writeVec(buf, b);
			}
			buf.writeVarInt(v.objectives.size());
			for (Component c : v.objectives) {
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c);
			}
			buf.writeFloat(v.stun);
			buf.writeVarInt(v.stunnedTicks);
			buf.writeBoolean(v.blind);
		}, buf -> {
			float bloodthirst = buf.readFloat();
			int score = buf.readVarInt();
			float fatigue = buf.readFloat();
			boolean sense = buf.readBoolean();
			boolean focus = buf.readBoolean();
			int jumpCooldown = buf.readVarInt();
			int jumpCooldownMax = buf.readVarInt();
			int shadowTicks = buf.readVarInt();
			int jumpBlock = buf.readVarInt();
			int dashCooldown = buf.readVarInt();
			int dashCooldownMax = buf.readVarInt();
			int dashTicks = buf.readVarInt();
			int impulseCooldown = buf.readVarInt();
			int impulseCooldownMax = buf.readVarInt();
			int pushCooldown = buf.readVarInt();
			int grabCooldown = buf.readVarInt();
			int attackCooldown = buf.readVarInt();
			int n = buf.readVarInt();
			List<VictimInfo> victims = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				victims.add(new VictimInfo(buf.readVarInt(), buf.readFloat(), buf.readBoolean(), ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));
			}
			int nb = buf.readVarInt();
			List<Vec3> beams = new ArrayList<>(nb);
			for (int i = 0; i < nb; i++) {
				beams.add(readVec(buf));
			}
			int no = buf.readVarInt();
			List<Component> objectives = new ArrayList<>(no);
			for (int i = 0; i < no; i++) {
				objectives.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
			}
			float stun = buf.readFloat();
			int stunnedTicks = buf.readVarInt();
			boolean blind = buf.readBoolean();
			return new ShapeHud(bloodthirst, score, fatigue, sense, focus, jumpCooldown, jumpCooldownMax, shadowTicks, jumpBlock,
				dashCooldown, dashCooldownMax, dashTicks, impulseCooldown, impulseCooldownMax, pushCooldown, grabCooldown, attackCooldown,
				victims, beams, objectives, stun, stunnedTicks, blind);
		});

		@Override
		public Type<ShapeHud> type() {
			return TYPE;
		}
	}

	/** stalk is 0..3 (level = integer part); marked victims get a red outline. */
	public record VictimInfo(int entityId, float stalk, boolean marked, Component name) {
	}

	/**
	 * One reaction check of a grab, for either side. For the victim it is a horizontal bar with a
	 * bouncing cursor; for the Shape it is a ring with a rotating needle. roundStart is level game time.
	 */
	public record Qte(
		boolean active, boolean forShape, float zoneStart, float zoneWidth, int period, long roundStart, int roundTicks,
		int points, int needed, int enemyPoints, int enemyNeeded, boolean window, boolean bloody,
		int kind, float zone2, int need, int stage
	) implements CustomPacketPayload {
		public static final Type<Qte> TYPE = payloadType("qte");
		public static final Qte INACTIVE = new Qte(false, false, 0, 0, 1, 0, 1, 0, 0, 0, 0, false, false);

		/** The plain check: the bar for the victim, the ring for the Shape. */
		public Qte(boolean active, boolean forShape, float zoneStart, float zoneWidth, int period, long roundStart, int roundTicks,
			int points, int needed, int enemyPoints, int enemyNeeded, boolean window, boolean bloody) {
			this(active, forShape, zoneStart, zoneWidth, period, roundStart, roundTicks, points, needed, enemyPoints, enemyNeeded, window, bloody,
				forShape ? Check.RING : Check.BAR, 0, 0, 0);
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, Qte> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeBoolean(v.active);
			buf.writeBoolean(v.forShape);
			buf.writeFloat(v.zoneStart);
			buf.writeFloat(v.zoneWidth);
			buf.writeVarInt(v.period);
			buf.writeVarLong(v.roundStart);
			buf.writeVarInt(v.roundTicks);
			buf.writeVarInt(v.points);
			buf.writeVarInt(v.needed);
			buf.writeVarInt(v.enemyPoints);
			buf.writeVarInt(v.enemyNeeded);
			buf.writeBoolean(v.window);
			buf.writeBoolean(v.bloody);
			buf.writeVarInt(v.kind);
			buf.writeFloat(v.zone2);
			buf.writeVarInt(v.need);
			buf.writeVarInt(v.stage);
		}, buf -> new Qte(
			buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
			buf.readVarInt(), buf.readFloat(), buf.readVarInt(), buf.readVarInt()
		));

		@Override
		public Type<Qte> type() {
			return TYPE;
		}
	}

	/** Score popup for the XP feed: "+075 XP" with reason lines. */
	public record ScoreEvent(int points, List<Component> lines) implements CustomPacketPayload {
		public static final Type<ScoreEvent> TYPE = payloadType("score_event");
		public static final StreamCodec<RegistryFriendlyByteBuf, ScoreEvent> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.points);
			buf.writeVarInt(v.lines.size());
			for (Component c : v.lines) {
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c);
			}
		}, buf -> {
			int points = buf.readVarInt();
			int n = buf.readVarInt();
			List<Component> lines = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				lines.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
			}
			return new ScoreEvent(points, lines);
		});

		@Override
		public Type<ScoreEvent> type() {
			return TYPE;
		}
	}

	/** Detection Impulse result: victims outdoors are outlined, victims indoors only reveal their building. */
	public record Impulse(List<Integer> outdoorIds, List<Vec3> indoorSpots, int durationTicks) implements CustomPacketPayload {
		public static final Type<Impulse> TYPE = payloadType("impulse");
		public static final StreamCodec<RegistryFriendlyByteBuf, Impulse> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.outdoorIds.size());
			for (int id : v.outdoorIds) {
				buf.writeVarInt(id);
			}
			buf.writeVarInt(v.indoorSpots.size());
			for (Vec3 p : v.indoorSpots) {
				writeVec(buf, p);
			}
			buf.writeVarInt(v.durationTicks);
		}, buf -> {
			int n = buf.readVarInt();
			List<Integer> ids = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				ids.add(buf.readVarInt());
			}
			int m = buf.readVarInt();
			List<Vec3> spots = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				spots.add(readVec(buf));
			}
			return new Impulse(ids, spots, buf.readVarInt());
		});

		@Override
		public Type<Impulse> type() {
			return TYPE;
		}
	}

	/** Locked doors (lower halves) and doors whose lock the Shape has broken. */
	/** The town's plan for the minimap: one byte per column (game.Minimap kinds), row by row from (x0, z0). */
	public record MinimapSync(int x0, int z0, int width, int height, byte[] plan) implements CustomPacketPayload {
		public static final MinimapSync NONE = new MinimapSync(0, 0, 0, 0, new byte[0]);
		public static final Type<MinimapSync> TYPE = payloadType("minimap");
		public static final StreamCodec<RegistryFriendlyByteBuf, MinimapSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.x0);
			buf.writeVarInt(v.z0);
			buf.writeVarInt(v.width);
			buf.writeVarInt(v.height);
			buf.writeByteArray(v.plan);
		}, buf -> new MinimapSync(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));

		@Override
		public Type<MinimapSync> type() {
			return TYPE;
		}
	}

	/** The music this player should hear now: a mood of game.Music and which part of it (1-based). */
	/** A grab animation for one entity (see client.ClientAnims): held poses until NONE, the rest play once. */
	public record Anim(int entityId, int kind) implements CustomPacketPayload {
		public static final int NONE = 0;
		/** The Shape lunges for someone (also when he misses). */
		public static final int REACH = 1;
		/** The Shape holds someone by the throat, the knife raised. */
		public static final int HOLD = 2;
		/** Held: clawing at his arm, kicking, choking. */
		public static final int STRUGGLE = 3;
		/** Tearing free with a shove. */
		public static final int BREAK_FREE = 4;
		/** The Shape thrown back a step. */
		public static final int STAGGER = 5;
		/** Stabbing him to get free. */
		public static final int STAB = 6;
		/** The Shape stabbed: a hand to the wound. */
		public static final int STABBED = 7;
		/** The Shape's knife comes down (the kill). */
		public static final int KNIFE_DOWN = 8;
		/** Knocked down: goes over backwards and stays down. */
		public static final int FALL = 9;
		/** Gets back up after a fall. */
		public static final int RISE = 10;
		/** The Runt on somebody's back, strangling them. */
		public static final int STRANGLE = 11;

		public static final Type<Anim> TYPE = payloadType("anim");
		public static final StreamCodec<RegistryFriendlyByteBuf, Anim> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.entityId);
			buf.writeVarInt(v.kind);
		}, buf -> new Anim(buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<Anim> type() {
			return TYPE;
		}
	}

	/** This survivor's stamina (0..1) and whether they are out of breath (see game.Stamina). */
	public record StaminaSync(float value, boolean exhausted, float panic) implements CustomPacketPayload {
		public static final Type<StaminaSync> TYPE = payloadType("stamina");
		public static final StreamCodec<RegistryFriendlyByteBuf, StaminaSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeFloat(v.value);
			buf.writeBoolean(v.exhausted);
			buf.writeFloat(v.panic);
		}, buf -> new StaminaSync(buf.readFloat(), buf.readBoolean(), buf.readFloat()));

		@Override
		public Type<StaminaSync> type() {
			return TYPE;
		}
	}

	/** The survivors of this match and how each is doing: 0 still in it, 1 got out, 2 dead (for the goals panel). */
	public record Roster(List<String> names, List<Integer> states, int npcAlive, int npcRescued, int npcDead) implements CustomPacketPayload {
		public static final Roster EMPTY = new Roster(List.of(), List.of(), 0, 0, 0);
		public static final Type<Roster> TYPE = payloadType("roster");
		public static final StreamCodec<RegistryFriendlyByteBuf, Roster> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.names.size());
			for (int i = 0; i < v.names.size(); i++) {
				buf.writeUtf(v.names.get(i));
				buf.writeVarInt(v.states.get(i));
			}
			buf.writeVarInt(v.npcAlive);
			buf.writeVarInt(v.npcRescued);
			buf.writeVarInt(v.npcDead);
		}, buf -> {
			int n = buf.readVarInt();
			List<String> names = new ArrayList<>(n);
			List<Integer> states = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				names.add(buf.readUtf());
				states.add(buf.readVarInt());
			}
			return new Roster(names, states, buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		});

		@Override
		public Type<Roster> type() {
			return TYPE;
		}
	}

	/** In the lobby (see game.Lobby): the line at the top (ready, countdown, parkour clock) and what the stand at hand says. */
	public record LobbyInfo(boolean inLobby, Component status, List<Component> panel) implements CustomPacketPayload {
		public static final LobbyInfo NONE = new LobbyInfo(false, Component.empty(), List.of());
		public static final Type<LobbyInfo> TYPE = payloadType("lobby");
		public static final StreamCodec<RegistryFriendlyByteBuf, LobbyInfo> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeBoolean(v.inLobby);
			ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, v.status);
			buf.writeVarInt(v.panel.size());
			for (Component c : v.panel) {
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c);
			}
		}, buf -> {
			boolean in = buf.readBoolean();
			Component status = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
			int n = buf.readVarInt();
			List<Component> panel = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				panel.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
			}
			return new LobbyInfo(in, status, panel);
		});

		@Override
		public Type<LobbyInfo> type() {
			return TYPE;
		}
	}

	/** The opening scene to play while the match counts down (see game.Cutscenes; a, b, c depend on the kind). */
	public record Cutscene(int kind, int ticks, Vec3 a, Vec3 b, Vec3 c) implements CustomPacketPayload {
		public static final Type<Cutscene> TYPE = payloadType("cutscene");
		public static final StreamCodec<RegistryFriendlyByteBuf, Cutscene> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.kind);
			buf.writeVarInt(v.ticks);
			writeVec(buf, v.a);
			writeVec(buf, v.b);
			writeVec(buf, v.c);
		}, buf -> new Cutscene(buf.readVarInt(), buf.readVarInt(), readVec(buf), readVec(buf), readVec(buf)));

		@Override
		public Type<Cutscene> type() {
			return TYPE;
		}
	}

	/** Michael hears footsteps: where they came from, and whether off a loud surface (see game.Noise). */
	/** A tremor of the midnight earthquake: the screen shakes this long, this hard (0..1). */
	public record Quake(int ticks, float strength) implements CustomPacketPayload {
		public static final Type<Quake> TYPE = payloadType("quake");
		public static final StreamCodec<RegistryFriendlyByteBuf, Quake> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.ticks);
			buf.writeFloat(v.strength);
		}, buf -> new Quake(buf.readVarInt(), buf.readFloat()));

		@Override
		public Type<Quake> type() {
			return TYPE;
		}
	}

	public record NoiseBurst(Vec3 pos, boolean loud) implements CustomPacketPayload {
		public static final Type<NoiseBurst> TYPE = payloadType("noise");
		public static final StreamCodec<RegistryFriendlyByteBuf, NoiseBurst> CODEC = StreamCodec.of((buf, v) -> {
			writeVec(buf, v.pos);
			buf.writeBoolean(v.loud);
		}, buf -> new NoiseBurst(readVec(buf), buf.readBoolean()));

		@Override
		public Type<NoiseBurst> type() {
			return TYPE;
		}
	}

	/** This player's purse, the perks they own and the ones in their build, and how many the build holds (see game.Perks). */
	public record PerkSync(int points, List<String> owned, List<String> equipped, int slots) implements CustomPacketPayload {
		public static final Type<PerkSync> TYPE = payloadType("perk_sync");
		public static final StreamCodec<RegistryFriendlyByteBuf, PerkSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.points);
			buf.writeVarInt(v.owned.size());
			v.owned.forEach(buf::writeUtf);
			buf.writeVarInt(v.equipped.size());
			v.equipped.forEach(buf::writeUtf);
			buf.writeVarInt(v.slots);
		}, buf -> {
			int points = buf.readVarInt();
			int n = buf.readVarInt();
			List<String> owned = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				owned.add(buf.readUtf());
			}
			int m = buf.readVarInt();
			List<String> equipped = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				equipped.add(buf.readUtf());
			}
			return new PerkSync(points, owned, equipped, buf.readVarInt());
		});

		@Override
		public Type<PerkSync> type() {
			return TYPE;
		}
	}

	/** Client: buy a perk, put it into the build or take it out (game.Perks.BUY / EQUIP / UNEQUIP). */
	public record PerkAction(String perk, int action) implements CustomPacketPayload {
		public static final Type<PerkAction> TYPE = payloadType("perk_action");
		public static final StreamCodec<RegistryFriendlyByteBuf, PerkAction> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeUtf(v.perk);
			buf.writeVarInt(v.action);
		}, buf -> new PerkAction(buf.readUtf(), buf.readVarInt()));

		@Override
		public Type<PerkAction> type() {
			return TYPE;
		}
	}

	/** Someone is out of breath (on) or has caught it (off): clients play the panting (see client.ClientPanting). */
	public record Pant(int entityId, boolean on, boolean female, float volume) implements CustomPacketPayload {
		public static final Type<Pant> TYPE = payloadType("pant");
		public static final StreamCodec<RegistryFriendlyByteBuf, Pant> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.entityId);
			buf.writeBoolean(v.on);
			buf.writeBoolean(v.female);
			buf.writeFloat(v.volume);
		}, buf -> new Pant(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readFloat()));

		@Override
		public Type<Pant> type() {
			return TYPE;
		}
	}

	/** The match's weather (see game.Weather). */
	public record MatchWeather(int kind) implements CustomPacketPayload {
		public static final Type<MatchWeather> TYPE = payloadType("weather");
		public static final StreamCodec<RegistryFriendlyByteBuf, MatchWeather> CODEC = StreamCodec.of((buf, v) -> buf.writeVarInt(v.kind),
			buf -> new MatchWeather(buf.readVarInt()));

		@Override
		public Type<MatchWeather> type() {
			return TYPE;
		}
	}

	public record MusicMood(int mood, int variant) implements CustomPacketPayload {
		public static final Type<MusicMood> TYPE = payloadType("music");
		public static final StreamCodec<RegistryFriendlyByteBuf, MusicMood> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.mood + 1);
			buf.writeVarInt(v.variant);
		}, buf -> new MusicMood(buf.readVarInt() - 1, buf.readVarInt()));

		@Override
		public Type<MusicMood> type() {
			return TYPE;
		}
	}

	/** Match skins: these player entities wear these skins (Michael, a survivor, the police uniform). */
	public record Skins(List<Integer> ids, List<String> keys) implements CustomPacketPayload {
		public static final Type<Skins> TYPE = payloadType("skins");
		public static final StreamCodec<RegistryFriendlyByteBuf, Skins> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.ids.size());
			for (int i = 0; i < v.ids.size(); i++) {
				buf.writeVarInt(v.ids.get(i));
				buf.writeUtf(v.keys.get(i));
			}
		}, buf -> {
			int n = buf.readVarInt();
			List<Integer> ids = new ArrayList<>(n);
			List<String> keys = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				ids.add(buf.readVarInt());
				keys.add(buf.readUtf());
			}
			return new Skins(ids, keys);
		});

		@Override
		public Type<Skins> type() {
			return TYPE;
		}
	}

	public record DoorSync(List<BlockPos> locked, List<BlockPos> broken) implements CustomPacketPayload {
		public static final Type<DoorSync> TYPE = payloadType("door_sync");
		public static final StreamCodec<RegistryFriendlyByteBuf, DoorSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.locked.size());
			v.locked.forEach(buf::writeBlockPos);
			buf.writeVarInt(v.broken.size());
			v.broken.forEach(buf::writeBlockPos);
		}, buf -> {
			int n = buf.readVarInt();
			List<BlockPos> locked = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				locked.add(buf.readBlockPos());
			}
			int m = buf.readVarInt();
			List<BlockPos> broken = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				broken.add(buf.readBlockPos());
			}
			return new DoorSync(locked, broken);
		});

		@Override
		public Type<DoorSync> type() {
			return TYPE;
		}
	}

	/**
	 * A timed action of this player (locking, searching, repairing, climbing...). label is a translation
	 * key, empty when nothing is in progress; danger draws the bar red.
	 */
	public record Progress(String label, float progress, boolean danger) implements CustomPacketPayload {
		public static final Progress NONE = new Progress("", 0, false);
		public static final Type<Progress> TYPE = payloadType("progress");
		public static final StreamCodec<RegistryFriendlyByteBuf, Progress> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeUtf(v.label);
				buf.writeFloat(v.progress);
				buf.writeBoolean(v.danger);
			}, buf -> new Progress(buf.readUtf(), buf.readFloat(), buf.readBoolean())
		);

		@Override
		public Type<Progress> type() {
			return TYPE;
		}
	}

	/** One exit of the current match. type: 0 cellar, 1 car, 2 police, 3 gate; state and extra are per type (see Exits). */
	public record ExitInfo(int type, BlockPos pos, int state, int extra) {
	}

	/**
	 * Match state for one player. state: 0 idle, 1 countdown, 2 running, 3 ended. respawnTicks counts down to
	 * coming back as a deputy (0 = not waiting).
	 */
	public record MatchSync(int state, int ticksLeft, int countdown, List<ExitInfo> exits, List<BlockPos> containers,
		List<BlockPos> searched, List<BlockPos> phones, List<BlockPos> cutPhones, int respawnTicks, int myScore, List<Integer> npcs, List<Integer> followers,
		int wanted, int reportable, List<BlockPos> cases) implements CustomPacketPayload {
		public static final Type<MatchSync> TYPE = payloadType("match_sync");
		public static final MatchSync IDLE = new MatchSync(0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), 0, 0, List.of(), List.of(), 0, 0, List.of());
		private static void writePositions(RegistryFriendlyByteBuf buf, List<BlockPos> list) {
			buf.writeVarInt(list.size());
			list.forEach(buf::writeBlockPos);
		}

		private static List<BlockPos> readPositions(RegistryFriendlyByteBuf buf) {
			int n = buf.readVarInt();
			List<BlockPos> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				list.add(buf.readBlockPos());
			}
			return list;
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, MatchSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.state);
			buf.writeVarInt(v.ticksLeft);
			buf.writeVarInt(v.countdown);
			buf.writeVarInt(v.exits.size());
			for (ExitInfo e : v.exits) {
				buf.writeVarInt(e.type());
				buf.writeBlockPos(e.pos());
				buf.writeVarInt(e.state());
				buf.writeVarInt(e.extra());
			}
			writePositions(buf, v.containers);
			writePositions(buf, v.searched);
			writePositions(buf, v.phones);
			writePositions(buf, v.cutPhones);
			buf.writeVarInt(v.respawnTicks);
			buf.writeVarInt(v.myScore);
			buf.writeVarInt(v.npcs.size());
			v.npcs.forEach(buf::writeVarInt);
			buf.writeVarInt(v.followers.size());
			v.followers.forEach(buf::writeVarInt);
			buf.writeVarInt(v.wanted);
			buf.writeVarInt(v.reportable);
			writePositions(buf, v.cases);
		}, buf -> {
			int state = buf.readVarInt();
			int ticksLeft = buf.readVarInt();
			int countdown = buf.readVarInt();
			int n = buf.readVarInt();
			List<ExitInfo> exits = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				exits.add(new ExitInfo(buf.readVarInt(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt()));
			}
			List<BlockPos> containers = readPositions(buf);
			List<BlockPos> searched = readPositions(buf);
			List<BlockPos> phones = readPositions(buf);
			List<BlockPos> cut = readPositions(buf);
			int respawn = buf.readVarInt();
			int score = buf.readVarInt();
			int m = buf.readVarInt();
			List<Integer> npcs = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				npcs.add(buf.readVarInt());
			}
			int f = buf.readVarInt();
			List<Integer> followers = new ArrayList<>(f);
			for (int i = 0; i < f; i++) {
				followers.add(buf.readVarInt());
			}
			return new MatchSync(state, ticksLeft, countdown, exits, containers, searched, phones, cut, respawn, score, npcs, followers,
				buf.readVarInt(), buf.readVarInt(), readPositions(buf));
		});

		@Override
		public Type<MatchSync> type() {
			return TYPE;
		}
	}

	/** A townsperson screamed: survivors see for a while where the Shape was spotted. */
	public record Alert(Vec3 pos, int durationTicks) implements CustomPacketPayload {
		public static final Type<Alert> TYPE = payloadType("alert");
		public static final StreamCodec<RegistryFriendlyByteBuf, Alert> CODEC = StreamCodec.of((buf, v) -> {
			writeVec(buf, v.pos);
			buf.writeVarInt(v.durationTicks);
		}, buf -> new Alert(readVec(buf), buf.readVarInt()));

		@Override
		public Type<Alert> type() {
			return TYPE;
		}
	}

	/** Final scoreboard shown to everyone when the match ends. */
	/**
	 * A circular reaction check during a long action (a call, a talk): press when the needle crosses the
	 * zone to gain time. {@code start} is the game time the needle starts from the top.
	 */
	public record SkillCheck(boolean active, float zoneStart, float zoneWidth, int period, long start, int kind, float zone2, int need, int stage)
		implements CustomPacketPayload {
		public static final Type<SkillCheck> TYPE = payloadType("skill_check");
		public static final SkillCheck INACTIVE = new SkillCheck(false, 0, 0, 1, 0);

		/** The plain ring. */
		public SkillCheck(boolean active, float zoneStart, float zoneWidth, int period, long start) {
			this(active, zoneStart, zoneWidth, period, start, Check.RING, 0, 0, 0);
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, SkillCheck> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeBoolean(v.active);
			buf.writeFloat(v.zoneStart);
			buf.writeFloat(v.zoneWidth);
			buf.writeVarInt(v.period);
			buf.writeLong(v.start);
			buf.writeVarInt(v.kind);
			buf.writeFloat(v.zone2);
			buf.writeVarInt(v.need);
			buf.writeVarInt(v.stage);
		}, buf -> new SkillCheck(buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readLong(),
			buf.readVarInt(), buf.readFloat(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<SkillCheck> type() {
			return TYPE;
		}
	}

	/** Talk to a townsperson: {@code option} 0 follow, 1 hide, 2 call the police; -1 breaks off the talk. */
	public record NpcTalk(int entityId, int option) implements CustomPacketPayload {
		public static final Type<NpcTalk> TYPE = payloadType("npc_talk");
		public static final StreamCodec<RegistryFriendlyByteBuf, NpcTalk> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.entityId);
			buf.writeVarInt(v.option);
		}, buf -> new NpcTalk(buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<NpcTalk> type() {
			return TYPE;
		}
	}

	/** Opens a minigame panel on the client ({@link Minigame#WIRES}, {@link Minigame#CURVE}); kind -1 closes it. */
	public record Minigame(int kind, BlockPos pos, long seed) implements CustomPacketPayload {
		public static final int WIRES = 0;
		public static final int CURVE = 1;
		public static final int KEYPAD = 2;
		/** The Runt's hands at your throat: drag them off (see client.HandsScreen). */
		public static final int HANDS = 3;
		/** Sent back when a typed code was wrong (the keypad stays up). */
		public static final int WRONG_CODE = -2;
		public static final Type<Minigame> TYPE = payloadType("minigame");
		public static final StreamCodec<RegistryFriendlyByteBuf, Minigame> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.kind + 2);
			buf.writeBlockPos(v.pos);
			buf.writeLong(v.seed);
		}, buf -> new Minigame(buf.readVarInt() - 2, buf.readBlockPos(), buf.readLong()));

		public static Minigame close(BlockPos pos) {
			return new Minigame(-1, pos, 0);
		}

		@Override
		public Type<Minigame> type() {
			return TYPE;
		}
	}

	/** What the player did in a minigame: {@link #DONE}, {@link #SLIP} (off the line: noise), {@link #CLOSED}. */
	public record MinigameResult(int kind, BlockPos pos, int outcome) implements CustomPacketPayload {
		public static final int DONE = 0;
		public static final int SLIP = 1;
		public static final int CLOSED = 2;
		/** Keypad: outcome = CODE + the four digits typed. */
		public static final int CODE = 100000;
		public static final Type<MinigameResult> TYPE = payloadType("minigame_result");
		public static final StreamCodec<RegistryFriendlyByteBuf, MinigameResult> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.kind);
			buf.writeBlockPos(v.pos);
			buf.writeVarInt(v.outcome);
		}, buf -> new MinigameResult(buf.readVarInt(), buf.readBlockPos(), buf.readVarInt()));

		@Override
		public Type<MinigameResult> type() {
			return TYPE;
		}
	}

	/** The trigger pulled, with where the shooter was looking at that moment. */
	public record GunFire(float yaw, float pitch) implements CustomPacketPayload {
		public static final Type<GunFire> TYPE = payloadType("gun_fire");
		public static final StreamCodec<RegistryFriendlyByteBuf, GunFire> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeFloat(v.yaw);
			buf.writeFloat(v.pitch);
		}, buf -> new GunFire(buf.readFloat(), buf.readFloat()));

		@Override
		public Type<GunFire> type() {
			return TYPE;
		}
	}

	/** The gun raised (right mouse held) or lowered. */
	public record GunAim(boolean aiming) implements CustomPacketPayload {
		public static final Type<GunAim> TYPE = payloadType("gun_aim");
		public static final StreamCodec<RegistryFriendlyByteBuf, GunAim> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeBoolean(v.aiming), buf -> new GunAim(buf.readBoolean()));

		@Override
		public Type<GunAim> type() {
			return TYPE;
		}
	}

	/** A short notice in the corner of the screen (instead of a chat line). */
	public record Notice(Component text) implements CustomPacketPayload {
		public static final Type<Notice> TYPE = payloadType("notice");
		public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = StreamCodec.of(
			(buf, v) -> ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, v.text),
			buf -> new Notice(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf)));

		@Override
		public Type<Notice> type() {
			return TYPE;
		}
	}

	/** The match is over: the tally (who won, who got out and how, points, the best moment) and the chronicle. */
	public record MatchEnd(boolean shapeWon, List<Component> lines, List<Component> chronicle) implements CustomPacketPayload {
		public static final Type<MatchEnd> TYPE = payloadType("match_end");
		public static final StreamCodec<RegistryFriendlyByteBuf, MatchEnd> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeBoolean(v.shapeWon);
			writeLines(buf, v.lines);
			writeLines(buf, v.chronicle);
		}, buf -> new MatchEnd(buf.readBoolean(), readLines(buf), readLines(buf)));

		private static void writeLines(RegistryFriendlyByteBuf buf, List<Component> lines) {
			buf.writeVarInt(lines.size());
			for (Component c : lines) {
				ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, c);
			}
		}

		private static List<Component> readLines(RegistryFriendlyByteBuf buf) {
			int n = buf.readVarInt();
			List<Component> lines = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				lines.add(ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf));
			}
			return lines;
		}

		@Override
		public Type<MatchEnd> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- C2S
	/** A civilian's interaction with a door (lock/unlock, held) or a window (climb slowly or jump through). */
	public record UseAction(int kind, BlockPos pos) implements CustomPacketPayload {
		public static final int DOOR_LOCK = 0;
		public static final int VAULT_SLOW = 1;
		public static final int VAULT_FAST = 2;
		/** Hold use on a match object: container, exit, phone. The server works out what it means. */
		public static final int INTERACT = 3;
		/** A locker: survivors climb in or out, the Shape throws it open. */
		public static final int LOCKER = 4;
		/** The Runt's mischief on a door, a phone or a breaker. */
		public static final int RUNT_MISCHIEF = 5;
		/** The Runt knocks on a door or a window. */
		public static final int RUNT_KNOCK = 6;
		public static final Type<UseAction> TYPE = payloadType("use_action");
		public static final StreamCodec<RegistryFriendlyByteBuf, UseAction> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeVarInt(v.kind);
				buf.writeBlockPos(v.pos);
			}, buf -> new UseAction(buf.readVarInt(), buf.readBlockPos())
		);

		@Override
		public Type<UseAction> type() {
			return TYPE;
		}
	}


	/** Held state of Killer Sense and focusing. */
	public record SenseInput(boolean sense, boolean focus) implements CustomPacketPayload {
		public static final Type<SenseInput> TYPE = payloadType("sense_input");
		public static final StreamCodec<RegistryFriendlyByteBuf, SenseInput> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeBoolean(v.sense);
				buf.writeBoolean(v.focus);
			}, buf -> new SenseInput(buf.readBoolean(), buf.readBoolean())
		);

		@Override
		public Type<SenseInput> type() {
			return TYPE;
		}
	}

	/** The Shape's melee swing; targetId -1 means a swing at nothing. */
	public record ShapeAttack(int targetId, boolean heavy) implements CustomPacketPayload {
		public static final Type<ShapeAttack> TYPE = payloadType("shape_attack");
		public static final StreamCodec<RegistryFriendlyByteBuf, ShapeAttack> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeVarInt(v.targetId);
				buf.writeBoolean(v.heavy);
			}, buf -> new ShapeAttack(buf.readVarInt(), buf.readBoolean())
		);

		@Override
		public Type<ShapeAttack> type() {
			return TYPE;
		}
	}

	/** Where the Shape aims: the ray through the centre of his over-the-shoulder camera. */
	public record Aim(Vec3 origin, Vec3 direction) implements CustomPacketPayload {
		public static final Type<Aim> TYPE = payloadType("aim");
		public static final StreamCodec<RegistryFriendlyByteBuf, Aim> CODEC = StreamCodec.of(
			(buf, v) -> {
				writeVec(buf, v.origin);
				writeVec(buf, v.direction);
			}, buf -> new Aim(readVec(buf), readVec(buf))
		);

		@Override
		public Type<Aim> type() {
			return TYPE;
		}
	}

	/** The Shape grabs the victim under his reticle. */
	public record Grab(int targetId) implements CustomPacketPayload {
		public static final Type<Grab> TYPE = payloadType("grab");
		public static final StreamCodec<RegistryFriendlyByteBuf, Grab> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeVarInt(v.targetId), buf -> new Grab(buf.readVarInt())
		);

		@Override
		public Type<Grab> type() {
			return TYPE;
		}
	}

	/** Single-press abilities of the Shape. */
	public enum Action {
		SHAPE_JUMP, DASH, PUSH, IMPULSE, QTE_PRESS, FLASHLIGHT, RELOAD,
		/** Right click with an item in hand (throw it, spray, med kit, flare gun). */
		ITEM_USE,
		/** Held by the Shape: stab him with a knife and break free. */
		STAB,
		/** The Runt's own key (trading places with Michael). */
		RUNT_SPECIAL,
		/** The Runt giggles or whispers. */
		RUNT_LAUGH;

		static Action byId(int id) {
			Action[] values = values();
			return id >= 0 && id < values.length ? values[id] : QTE_PRESS;
		}
	}

	private static void writeIds(RegistryFriendlyByteBuf buf, List<Integer> ids) {
		buf.writeVarInt(ids.size());
		for (int id : ids) {
			buf.writeVarInt(id + 1);
		}
	}

	private static List<Integer> readIds(RegistryFriendlyByteBuf buf) {
		int n = buf.readVarInt();
		List<Integer> ids = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			ids.add(buf.readVarInt() - 1);
		}
		return ids;
	}

	/** To whoever plays the Runt: his fear and level, the gifts picked (-1 none) and the level waiting for one, cooldowns, state. */
	public record RuntHud(float fear, int level, float prevFear, float nextFear, int scareCooldown, int scareMax, int specialCooldown, int specialMax,
		int trickCooldown, int trickMax, List<Integer> picks, int pending, int stunned, int holding, int michaelId, List<Integer> scent,
		float charge, int pounceCooldown, int pounceMax, int knockCooldown) implements CustomPacketPayload {
		public static final Type<RuntHud> TYPE = payloadType("runt_hud");
		public static final StreamCodec<RegistryFriendlyByteBuf, RuntHud> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeFloat(v.fear);
			buf.writeVarInt(v.level);
			buf.writeFloat(v.prevFear);
			buf.writeFloat(v.nextFear);
			buf.writeVarInt(v.scareCooldown);
			buf.writeVarInt(v.scareMax);
			buf.writeVarInt(v.specialCooldown);
			buf.writeVarInt(v.specialMax);
			buf.writeVarInt(v.trickCooldown);
			buf.writeVarInt(v.trickMax);
			writeIds(buf, v.picks);
			buf.writeVarInt(v.pending + 1);
			buf.writeVarInt(v.stunned);
			buf.writeVarInt(v.holding);
			buf.writeVarInt(v.michaelId + 1);
			writeIds(buf, v.scent);
			buf.writeFloat(v.charge);
			buf.writeVarInt(v.pounceCooldown);
			buf.writeVarInt(v.pounceMax);
			buf.writeVarInt(v.knockCooldown);
		}, buf -> new RuntHud(buf.readFloat(), buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readIds(buf), buf.readVarInt() - 1, buf.readVarInt(), buf.readVarInt(), buf.readVarInt() - 1, readIds(buf),
			buf.readFloat(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<RuntHud> type() {
			return TYPE;
		}
	}

	/** A marker of a match event: where, and what (one of the kinds below). */
	public record EventMark(Vec3 pos, int kind) {
		public static final int PHONE = 0;
		public static final int BAG = 1;
		public static final int PATROL = 2;
		public static final int TRANSFORMER = 3;
		public static final int PARTY = 4;
		public static final int FIRE = 5;
		public static final int ESCAPE_ITEM = 6;
		public static final int EXIT = 7;
	}

	/**
	 * The match event as this player sees it (game.Events): which (empty: none), phase (0 none, 1 announced,
	 * 2 running), time left and the whole of it, markers, whom they see through walls, objects to use, flags.
	 */
	public record EventSync(String id, int phase, int ticksLeft, int total, List<EventMark> marks, List<Integer> glows, List<BlockPos> objects, int flags)
		implements CustomPacketPayload {
		public static final int SAMHAIN = 1;
		public static final int MIDNIGHT = 2;
		public static final Type<EventSync> TYPE = payloadType("event_sync");
		public static final EventSync NONE = new EventSync("", 0, 0, 1, List.of(), List.of(), List.of(), 0);
		public static final StreamCodec<RegistryFriendlyByteBuf, EventSync> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeUtf(v.id);
			buf.writeVarInt(v.phase);
			buf.writeVarInt(v.ticksLeft);
			buf.writeVarInt(v.total);
			buf.writeVarInt(v.marks.size());
			for (EventMark m : v.marks) {
				writeVec(buf, m.pos());
				buf.writeVarInt(m.kind());
			}
			writeIds(buf, v.glows);
			buf.writeVarInt(v.objects.size());
			v.objects.forEach(buf::writeBlockPos);
			buf.writeVarInt(v.flags);
		}, buf -> {
			String id = buf.readUtf();
			int phase = buf.readVarInt();
			int left = buf.readVarInt();
			int total = buf.readVarInt();
			int n = buf.readVarInt();
			List<EventMark> marks = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				marks.add(new EventMark(readVec(buf), buf.readVarInt()));
			}
			List<Integer> glows = readIds(buf);
			int m = buf.readVarInt();
			List<BlockPos> objects = new ArrayList<>(m);
			for (int i = 0; i < m; i++) {
				objects.add(buf.readBlockPos());
			}
			return new EventSync(id, phase, left, total, marks, glows, objects, buf.readVarInt());
		});

		@Override
		public Type<EventSync> type() {
			return TYPE;
		}
	}

	/** To Michael: his Runt (to see him through walls), who it smells near it, whom it is holding (-1 nobody). */
	public record RuntInfo(int runtId, List<Integer> scent, int holdingId, boolean bot, int status) implements CustomPacketPayload {
		public static final Type<RuntInfo> TYPE = payloadType("runt_info");
		public static final StreamCodec<RegistryFriendlyByteBuf, RuntInfo> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.runtId);
			writeIds(buf, v.scent);
			buf.writeVarInt(v.holdingId + 1);
			buf.writeBoolean(v.bot);
			buf.writeVarInt(v.status);
		}, buf -> new RuntInfo(buf.readVarInt(), readIds(buf), buf.readVarInt() - 1, buf.readBoolean(), buf.readVarInt()));

		@Override
		public Type<RuntInfo> type() {
			return TYPE;
		}
	}

	/** Client (Michael): an order for his Runt when a bot plays it: SEARCH, ATTACK, TRICKS or HEEL, at an entity (-1) or a place. */
	public record RuntOrder(int kind, int targetId, BlockPos pos) implements CustomPacketPayload {
		public static final int SEARCH = 0;
		public static final int ATTACK = 1;
		public static final int TRICKS = 2;
		public static final int HEEL = 3;
		public static final Type<RuntOrder> TYPE = payloadType("runt_order");
		public static final StreamCodec<RegistryFriendlyByteBuf, RuntOrder> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.kind);
			buf.writeVarInt(v.targetId + 1);
			buf.writeBlockPos(v.pos);
		}, buf -> new RuntOrder(buf.readVarInt(), buf.readVarInt() - 1, buf.readBlockPos()));

		@Override
		public Type<RuntOrder> type() {
			return TYPE;
		}
	}

	/** Client: the Runt picks one of the two gifts of a level he has reached. */
	public record RuntPick(int level, int option) implements CustomPacketPayload {
		public static final Type<RuntPick> TYPE = payloadType("runt_pick");
		public static final StreamCodec<RegistryFriendlyByteBuf, RuntPick> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeVarInt(v.level);
			buf.writeVarInt(v.option);
		}, buf -> new RuntPick(buf.readVarInt(), buf.readVarInt()));

		@Override
		public Type<RuntPick> type() {
			return TYPE;
		}
	}

	public record ActionPress(Action action) implements CustomPacketPayload {
		public static final Type<ActionPress> TYPE = payloadType("action_press");
		public static final StreamCodec<RegistryFriendlyByteBuf, ActionPress> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeVarInt(v.action.ordinal()), buf -> new ActionPress(Action.byId(buf.readVarInt()))
		);

		@Override
		public Type<ActionPress> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(WorldSync.TYPE, WorldSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShapeHud.TYPE, ShapeHud.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RuntHud.TYPE, RuntHud.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RuntInfo.TYPE, RuntInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EventSync.TYPE, EventSync.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RuntPick.TYPE, RuntPick.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RuntOrder.TYPE, RuntOrder.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Qte.TYPE, Qte.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScoreEvent.TYPE, ScoreEvent.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Impulse.TYPE, Impulse.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DoorSync.TYPE, DoorSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Skins.TYPE, Skins.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MusicMood.TYPE, MusicMood.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Anim.TYPE, Anim.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchWeather.TYPE, MatchWeather.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StaminaSync.TYPE, StaminaSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Pant.TYPE, Pant.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NoiseBurst.TYPE, NoiseBurst.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Quake.TYPE, Quake.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Cutscene.TYPE, Cutscene.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LobbyInfo.TYPE, LobbyInfo.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Roster.TYPE, Roster.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PerkSync.TYPE, PerkSync.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PerkAction.TYPE, PerkAction.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MinimapSync.TYPE, MinimapSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchSync.TYPE, MatchSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MatchEnd.TYPE, MatchEnd.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Progress.TYPE, Progress.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Alert.TYPE, Alert.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Notice.TYPE, Notice.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Minigame.TYPE, Minigame.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SkillCheck.TYPE, SkillCheck.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(NpcTalk.TYPE, NpcTalk.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MinigameResult.TYPE, MinigameResult.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GunFire.TYPE, GunFire.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(GunAim.TYPE, GunAim.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseAction.TYPE, UseAction.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SenseInput.TYPE, SenseInput.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShapeAttack.TYPE, ShapeAttack.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ActionPress.TYPE, ActionPress.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Aim.TYPE, Aim.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Grab.TYPE, Grab.CODEC);
	}

	/** Position of the victim's QTE cursor in [0, 1]: a triangle wave bouncing across the bar. */
	public static float qteCursor(double elapsedTicks, int period) {
		double t = (elapsedTicks % period) / period;
		return (float) (t < 0.5 ? t * 2 : 2 - t * 2);
	}

	/** Position of the Shape's needle in [0, 1) around the ring. */
	/**
	 * The kinds of reaction check (survivors' long actions, the victim in Michael's grab) and their timing, the same
	 * for the server that judges a press and the screen that draws it.
	 */
	public static final class Check {
		/** A needle round a ring: press as it crosses the arc. */
		public static final int RING = 0;
		/** A cursor sliding back and forth along a bar: press in the zone. */
		public static final int BAR = 1;
		/** Two arcs on the ring: press in the first, then in the second, as the needle goes round. */
		public static final int DOUBLE = 2;
		/** A circle closing in: press as it crosses the band. */
		public static final int PULSE = 3;
		/** Tap as fast as you can: {@code need} taps before the time runs out. */
		public static final int MASH = 4;

		private Check() {
		}

		/** The moving mark, 0..1: the needle, the cursor, the closing circle's size (1 open .. 0 shut); MASH: the time gone. */
		public static float mark(int kind, double elapsed, int period) {
			return switch (kind) {
				case BAR -> qteCursor(elapsed, period);
				case PULSE -> (float) Math.max(0, 1 - elapsed / period);
				case MASH -> (float) Math.min(1, elapsed / period);
				default -> needle(elapsed, period);
			};
		}

		public static boolean in(float mark, float start, float width) {
			return mark >= start && mark <= start + width;
		}
	}

	public static float needle(double elapsedTicks, int period) {
		return (float) ((elapsedTicks % period) / period);
	}
}
