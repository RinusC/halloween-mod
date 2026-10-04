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
		List<VictimInfo> victims, List<Vec3> beams, List<Component> objectives
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
			return new ShapeHud(bloodthirst, score, fatigue, sense, focus, jumpCooldown, jumpCooldownMax, shadowTicks, jumpBlock,
				dashCooldown, dashCooldownMax, dashTicks, impulseCooldown, impulseCooldownMax, pushCooldown, grabCooldown, attackCooldown,
				victims, beams, objectives);
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
		int points, int needed, int enemyPoints, int enemyNeeded, boolean window, boolean bloody
	) implements CustomPacketPayload {
		public static final Type<Qte> TYPE = payloadType("qte");
		public static final Qte INACTIVE = new Qte(false, false, 0, 0, 1, 0, 1, 0, 0, 0, 0, false, false);
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
		}, buf -> new Qte(
			buf.readBoolean(), buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean()
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

	/** A timed action of this player (locking, unlocking, climbing through a window); kind 0 = none. */
	public record Progress(int kind, float progress) implements CustomPacketPayload {
		public static final int NONE = 0;
		public static final int LOCK = 1;
		public static final int UNLOCK = 2;
		public static final int VAULT_SLOW = 3;
		public static final int VAULT_FAST = 4;
		public static final Type<Progress> TYPE = payloadType("progress");
		public static final StreamCodec<RegistryFriendlyByteBuf, Progress> CODEC = StreamCodec.of(
			(buf, v) -> {
				buf.writeVarInt(v.kind);
				buf.writeFloat(v.progress);
			}, buf -> new Progress(buf.readVarInt(), buf.readFloat())
		);

		@Override
		public Type<Progress> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- C2S
	/** A civilian's interaction with a door (lock/unlock, held) or a window (climb slowly or jump through). */
	public record UseAction(int kind, BlockPos pos) implements CustomPacketPayload {
		public static final int DOOR_LOCK = 0;
		public static final int VAULT_SLOW = 1;
		public static final int VAULT_FAST = 2;
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
		SHAPE_JUMP, DASH, PUSH, IMPULSE, QTE_PRESS;

		static Action byId(int id) {
			Action[] values = values();
			return id >= 0 && id < values.length ? values[id] : QTE_PRESS;
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
		PayloadTypeRegistry.clientboundPlay().register(Qte.TYPE, Qte.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScoreEvent.TYPE, ScoreEvent.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Impulse.TYPE, Impulse.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DoorSync.TYPE, DoorSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Progress.TYPE, Progress.CODEC);
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
	public static float needle(double elapsedTicks, int period) {
		return (float) ((elapsedTicks % period) / period);
	}
}
