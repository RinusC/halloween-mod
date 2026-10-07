package dev.halloween.test;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.halloween.HwConfig;
import dev.halloween.client.ClientState;
import dev.halloween.client.ShapeBreath;
import dev.halloween.client.ShapeJumpAudio;
import dev.halloween.client.mixin.SoundEngineAccessor;
import dev.halloween.client.mixin.SoundManagerAccessor;
import dev.halloween.game.MatchManager;
import java.lang.reflect.Field;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shape Jump's sounds load, the drone and wind play inside it, the world is muffled, and it clears on the way out. */
public class ShapeJumpAudioClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-shapejump-audio-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("shapeaudio")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			// Music on (the test options have it at zero), to hear what happens to it.
			ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MUSIC).set(1.0));
			String bad = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				try {
					SoundEngine engine = ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine();
					SoundBufferLibrary library = null;
					for (Field f : SoundEngine.class.getDeclaredFields()) {
						if (f.getType() == SoundBufferLibrary.class) {
							f.setAccessible(true);
							library = (SoundBufferLibrary) f.get(engine);
						}
					}
					for (String name : new String[]{"enter", "exit", "drone", "wind"}) {
						SoundBuffer b = library.getCompleteBuffer(Identifier.fromNamespaceAndPath("halloween", "sounds/shape/" + name + ".ogg")).get();
						if (!b.isValid() || b.format().getChannels() != 1) {
							sb.append(name).append(' ');
						}
					}
				} catch (Exception e) {
					return "error " + e;
				}
				return sb.toString();
			});
			check(bad.isEmpty(), "the four Shape Jump sounds load (" + bad + ")");
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runCommand("hw point clear npc");
			server.runOnServer(s -> HwConfig.get().countdownSeconds = 2);
			server.runCommand("hw start @p");
			ctx.waitTicks(60);
			// Something in the world to hear muffled.
			ctx.runOnClient(mc -> mc.getSoundManager().play(new dev.halloween.client.LoopSound(net.minecraft.sounds.SoundEvents.PORTAL_AMBIENT, net.minecraft.sounds.SoundSource.BLOCKS, 1f, () -> 1)));
			check(ctx.computeOnClient(mc -> ShapeBreath.playingFor() == mc.player.getId()), "Michael breathes, and hears it himself");
			int[] before = musicAl(ctx);
			LOG.info("Music before Shape Jump: state {} filter {} gain {}", before[0], before.length > 1 ? before[1] : -1, before.length > 2 ? before[2] : -1);
			check(before[0] == AL10.AL_PLAYING, "Michael's music plays");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).shadowTicks = 1);
			ctx.waitTicks(30);
			check(ctx.computeOnClient(mc -> ClientState.shadowActive()), "the Shape is in Shape Jump");
			String playing = ctx.computeOnClient(mc -> {
				var channels = ((SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine()).halloween$channels();
				StringBuilder sb = new StringBuilder();
				for (var sound : channels.keySet()) {
					sb.append(sound.getIdentifier().getPath()).append("/").append(sound.getSource()).append(' ');
				}
				return sb.toString();
			});
			LOG.info("Playing: {}", playing);
			check(playing.contains("shape.shadow_drone") && playing.contains("shape.shadow_wind"), "the drone and the wind play (" + playing + ")");
			float muffle = ctx.computeOnClient(mc -> ShapeJumpAudio.muffle());
			check(muffle > 0.95f && ctx.computeOnClient(mc -> ShapeJumpAudio.filterReady()), "the world is muffled through the low-pass (" + muffle + ")");
			ctx.waitTicks(60);
			int[] inside = musicAl(ctx);
			LOG.info("Music in Shape Jump: state {} filter {} gain {}", inside[0], inside.length > 1 ? inside[1] : -1, inside.length > 2 ? inside[2] : -1);
			check(inside[0] == AL10.AL_PLAYING && inside[1] == 0 && inside[2] > 0, "the music keeps playing in Shape Jump, clear and at its volume");
			server.runOnServer(s -> MatchManager.shapeState(player(s)).shadowTicks = 0);
			ctx.waitTicks(40);
			check(ctx.computeOnClient(mc -> ShapeJumpAudio.muffle()) == 0f, "coming out, the world is clear again");
			int stillFiltered = filteredSources(ctx);
			check(stillFiltered == 0, "no sound playing carries the filter any more (" + stillFiltered + ")");
			check(musicAl(ctx)[0] == AL10.AL_PLAYING, "the music still plays after it");
			ctx.runOnClient(mc -> mc.getSoundManager().stop());
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds);
		}
		LOG.info("Shape Jump audio client test finished OK");
	}

	/** The OpenAL state, direct filter and gain (x1000) of the match music's source; {-1} with no music. */
	private static int[] musicAl(ClientGameTestContext ctx) {
		return al(ctx, null);
	}

	/** How many playing sounds still have a filter on their source. */
	private static int filteredSources(ClientGameTestContext ctx) {
		java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();
		ctx.runOnClient(mc -> {
			var channels = ((SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine()).halloween$channels();
			for (var handle : channels.values()) {
				handle.execute(channel -> {
					int source = ((dev.halloween.client.mixin.ChannelAccessor) channel).halloween$source();
					if (AL10.alGetSourcei(source, org.lwjgl.openal.EXTEfx.AL_DIRECT_FILTER) != 0) {
						count.incrementAndGet();
					}
				});
			}
		});
		ctx.waitTicks(4);
		return count.get();
	}

	private static int[] al(ClientGameTestContext ctx, String path) {
		java.util.concurrent.atomic.AtomicReference<int[]> out = new java.util.concurrent.atomic.AtomicReference<>(new int[]{-1, -1, -1});
		ctx.runOnClient(mc -> {
			var channels = ((SoundEngineAccessor) ((SoundManagerAccessor) mc.getSoundManager()).halloween$soundEngine()).halloween$channels();
			net.minecraft.client.resources.sounds.SoundInstance sound = null;
			if (path == null) {
				sound = dev.halloween.client.ClientMusic.track();
			} else {
				for (var s : channels.keySet()) {
					if (s.getIdentifier().getPath().equals(path)) {
						sound = s;
					}
				}
			}
			var handle = sound == null ? null : channels.get(sound);
			if (handle != null) {
				handle.execute(channel -> {
					int source = ((dev.halloween.client.mixin.ChannelAccessor) channel).halloween$source();
					out.set(new int[]{AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE), AL10.alGetSourcei(source, org.lwjgl.openal.EXTEfx.AL_DIRECT_FILTER),
						Math.round(AL10.alGetSourcef(source, AL10.AL_GAIN) * 1000)});
				});
			}
		});
		ctx.waitTicks(4);
		return out.get();
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof dev.halloween.game.BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}

	private static void check(boolean condition, String what) {
		if (!condition) {
			throw new AssertionError("FAILED: " + what);
		}
		LOG.info("PASS: {}", what);
	}
}
