package dev.halloween.net;

import dev.halloween.Halloween;
import dev.halloween.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** All custom packets. S2C = server to client, C2S = client to server. */
public final class HwNet {
	private HwNet() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
		return new CustomPacketPayload.Type<>(Halloween.id(path));
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
	 * Sent only to the Shape a few times per second.
	 * jumpBlock: 0 ready, 1 too much light, 2 seen by a civilian, 3 cooling down, 4 busy.
	 */
	public record ShapeHud(
		float bloodthirst, int tier, float fatigue, boolean sense, int jumpCooldown, int jumpCooldownMax,
		int shadowTicks, int grabCooldown, int jumpBlock, List<VictimInfo> victims
	) implements CustomPacketPayload {
		public static final Type<ShapeHud> TYPE = payloadType("shape_hud");
		public static final StreamCodec<RegistryFriendlyByteBuf, ShapeHud> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeFloat(v.bloodthirst);
			buf.writeVarInt(v.tier);
			buf.writeFloat(v.fatigue);
			buf.writeBoolean(v.sense);
			buf.writeVarInt(v.jumpCooldown);
			buf.writeVarInt(v.jumpCooldownMax);
			buf.writeVarInt(v.shadowTicks);
			buf.writeVarInt(v.grabCooldown);
			buf.writeVarInt(v.jumpBlock);
			buf.writeVarInt(v.victims.size());
			for (VictimInfo e : v.victims) {
				buf.writeVarInt(e.entityId());
				buf.writeFloat(e.stalk());
				buf.writeVarInt(e.focus());
				buf.writeBoolean(e.glow());
			}
		}, buf -> {
			float bloodthirst = buf.readFloat();
			int tier = buf.readVarInt();
			float fatigue = buf.readFloat();
			boolean sense = buf.readBoolean();
			int jumpCooldown = buf.readVarInt();
			int jumpCooldownMax = buf.readVarInt();
			int shadowTicks = buf.readVarInt();
			int grabCooldown = buf.readVarInt();
			int jumpBlock = buf.readVarInt();
			int n = buf.readVarInt();
			List<VictimInfo> victims = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				victims.add(new VictimInfo(buf.readVarInt(), buf.readFloat(), buf.readVarInt(), buf.readBoolean()));
			}
			return new ShapeHud(bloodthirst, tier, fatigue, sense, jumpCooldown, jumpCooldownMax, shadowTicks, grabCooldown, jumpBlock, victims);
		});

		@Override
		public Type<ShapeHud> type() {
			return TYPE;
		}
	}

	/** glow = marked and currently out of the Shape's sight, so the client outlines it. */
	public record VictimInfo(int entityId, float stalk, int focus, boolean glow) {
	}

	/** Grab QTE state for the grabbed player. roundStart is in level game time. */
	public record Qte(
		boolean active, float zoneStart, float zoneWidth, int period, long roundStart, int roundTicks,
		int successes, int needed, int misses, int maxMisses
	) implements CustomPacketPayload {
		public static final Type<Qte> TYPE = payloadType("qte");
		public static final Qte INACTIVE = new Qte(false, 0, 0, 1, 0, 1, 0, 0, 0, 0);
		public static final StreamCodec<RegistryFriendlyByteBuf, Qte> CODEC = StreamCodec.of((buf, v) -> {
			buf.writeBoolean(v.active);
			buf.writeFloat(v.zoneStart);
			buf.writeFloat(v.zoneWidth);
			buf.writeVarInt(v.period);
			buf.writeVarLong(v.roundStart);
			buf.writeVarInt(v.roundTicks);
			buf.writeVarInt(v.successes);
			buf.writeVarInt(v.needed);
			buf.writeVarInt(v.misses);
			buf.writeVarInt(v.maxMisses);
		}, buf -> new Qte(
			buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarLong(), buf.readVarInt(),
			buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()
		));

		@Override
		public Type<Qte> type() {
			return TYPE;
		}
	}

	// ---------------------------------------------------------------- C2S

	public record SenseToggle(boolean active) implements CustomPacketPayload {
		public static final Type<SenseToggle> TYPE = payloadType("sense_toggle");
		public static final StreamCodec<RegistryFriendlyByteBuf, SenseToggle> CODEC = StreamCodec.of(
			(buf, v) -> buf.writeBoolean(v.active), buf -> new SenseToggle(buf.readBoolean())
		);

		@Override
		public Type<SenseToggle> type() {
			return TYPE;
		}
	}

	public record ShapeJump() implements CustomPacketPayload {
		public static final Type<ShapeJump> TYPE = payloadType("shape_jump");
		public static final StreamCodec<RegistryFriendlyByteBuf, ShapeJump> CODEC = StreamCodec.of((buf, v) -> {
		}, buf -> new ShapeJump());

		@Override
		public Type<ShapeJump> type() {
			return TYPE;
		}
	}

	public record QtePress() implements CustomPacketPayload {
		public static final Type<QtePress> TYPE = payloadType("qte_press");
		public static final StreamCodec<RegistryFriendlyByteBuf, QtePress> CODEC = StreamCodec.of((buf, v) -> {
		}, buf -> new QtePress());

		@Override
		public Type<QtePress> type() {
			return TYPE;
		}
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(WorldSync.TYPE, WorldSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShapeHud.TYPE, ShapeHud.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Qte.TYPE, Qte.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SenseToggle.TYPE, SenseToggle.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ShapeJump.TYPE, ShapeJump.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(QtePress.TYPE, QtePress.CODEC);
	}

	/** Position of the QTE cursor in [0, 1]: a triangle wave bouncing across the bar. */
	public static float qteCursor(double elapsedTicks, int period) {
		double t = (elapsedTicks % period) / period;
		return (float) (t < 0.5 ? t * 2 : 2 - t * 2);
	}
}
