package dev.halloween.game;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import java.util.function.Consumer;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * A civilian played by the server: a real player entity on a connection that goes nowhere, so every
 * mechanic (items, exits, windows, grabs, score) treats it exactly like a person. Its movement is
 * simulated on the server from the inputs its {@link BotBrain} sets.
 */
public final class BotPlayer extends ServerPlayer {
	BotBrain brain;

	public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	@Override
	public void tick() {
		super.tick();
		// A real client's movement packets drive this for people; a bot moves itself.
		this.doTick();
		if (this.tickCount % 5 == 0) {
			this.level().getChunkSource().move(this);
		}
	}

	/** Drops every packet: there is no client on the other end. */
	static final class NowhereConnection extends net.minecraft.network.Connection {
		NowhereConnection() {
			super(PacketFlow.SERVERBOUND);
		}

		@Override
		public void send(Packet<?> packet, @Nullable ChannelFutureListener listener, boolean flush) {
		}

		@Override
		public void runOnceConnected(Consumer<net.minecraft.network.Connection> action) {
		}

		@Override
		public void flushChannel() {
		}

		@Override
		public void setReadOnly() {
		}

		@Override
		public void handleDisconnection() {
		}

		@Override
		public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T packetListener) {
		}
	}
}
