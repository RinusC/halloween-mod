package dev.halloween.test;

import com.mojang.blaze3d.audio.SoundBuffer;
import dev.halloween.HwConfig;
import dev.halloween.game.AiStats;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.Bots;
import dev.halloween.game.MatchManager;
import dev.halloween.game.Voices;
import java.lang.reflect.Field;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * People's voices: every recording loads (WAV through our loader, mono so it sounds from the person), and a
 * grab, a wound, a death and a frightened townsperson each give the right scream in the right voice.
 */
public class VoicesClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-voices-test");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("voices")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);

			// Every clip loads through the sound engine's own buffer library (our WAV loader takes over there).
			String bad = ctx.computeOnClient(mc -> {
				StringBuilder sb = new StringBuilder();
				int count = 0;
				try {
					SoundBufferLibrary library = library(mc.getSoundManager());
					for (Identifier id : mc.getResourceManager().listResources("sounds/voice", p -> p.getPath().endsWith(".ogg")).keySet()) {
						if (!id.getNamespace().equals("halloween")) {
							continue;
						}
						count++;
						SoundBuffer buffer = library.getCompleteBuffer(id).get();
						if (!buffer.isValid() || buffer.format().getChannels() != 1 || buffer.format().getSampleSizeInBits() != 16 || buffer.size() < 2000) {
							sb.append(id.getPath()).append(' ');
						}
					}
				} catch (Exception e) {
					return "error " + e;
				}
				return count < 60 ? "only " + count + " clips" : sb.toString();
			});
			check(bad.isEmpty(), "all 65 voice clips load as mono 16-bit sound (" + bad + ")");

			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = 2;
				HwConfig.get().botQteSuccessChance = 0f;
			});
			server.runCommand("hw start @p");
			ctx.waitTicks(60);

			// A grab: the victim screams, in their own voice.
			String grab = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().getFirst();
				ServerPlayer shape = player(s);
				bot.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ() - 1.2, java.util.Set.of(), 0f, 0f, false);
				int before = count("voice." + gender(bot) + ".scream");
				MatchManager.grabOut(shape, bot);
				return count("voice." + gender(bot) + ".scream") > before ? "ok" : bot.getPlainTextName() + " (" + gender(bot) + ") stayed quiet";
			});
			check("ok".equals(grab), "a grabbed survivor screams in their own voice (" + grab + ")");
			check(server.computeOnServer(s -> {
				for (BotPlayer bot : Bots.all()) {
					boolean slim = java.util.List.of("laurie", "annie", "lynda", "rachel", "tanya", "jennifer", "alexis").contains(bot.getPlainTextName().toLowerCase());
					if (Voices.female(bot) != slim) {
						return false;
					}
				}
				return true;
			}), "the women survivors scream with women's voices, the men with men's");
			server.runOnServer(s -> {
				var st = MatchManager.shapeState(player(s));
				if (st.grab != null) {
					st.grab.victim().teleportTo(st.grab.victim().getX(), st.grab.victim().getY(), st.grab.victim().getZ());
				}
				dev.halloween.game.Fight.stun(player(s), 1f, null);
			});
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				var st = MatchManager.shapeState(player(s));
				st.stunnedTicks = 0;
				st.stun = 0;
				st.recoverTicks = 0;
				st.attackCooldown = 0;
			});

			// A blow that doesn't kill: a cry of pain. One that does: a death scream.
			String blows = server.computeOnServer(s -> {
				BotPlayer bot = Bots.all().get(1);
				ServerPlayer shape = player(s);
				bot.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ() - 1.4, java.util.Set.of(), 0f, 0f, false);
				shape.teleportTo(s.overworld(), shape.getX(), shape.getY(), shape.getZ(), java.util.Set.of(), 180f, 10f, false);
				bot.setHealth(bot.getMaxHealth());
				String g = gender(bot);
				int hurt = count("voice." + g + ".hurt");
				MatchManager.onShapeAttack(shape, bot.getId(), false);
				boolean hurtOk = count("voice." + g + ".hurt") > hurt;
				int death = count("voice." + g + ".death");
				bot.setHealth(1f);
				MatchManager.shapeState(shape).attackCooldown = 0;
				MatchManager.onShapeAttack(shape, bot.getId(), true);
				boolean deathOk = count("voice." + g + ".death") > death;
				return hurtOk && deathOk ? "ok" : "hurt " + hurtOk + ", death " + deathOk;
			});
			check("ok".equals(blows), "a wound draws a cry of pain, a killing blow a death scream (" + blows + ")");

			// A townsperson who sees him screams (Mrs. ... with a woman's voice).
			int before = server.computeOnServer(s -> count("voice.female.scream") + count("voice.male.scream"));
			server.runOnServer(s -> {
				ServerPlayer shape = player(s);
				for (var npc : s.overworld().getEntities(net.minecraft.world.entity.EntityTypes.MANNEQUIN, dev.halloween.game.Townsfolk::isNpc)) {
					var front = npc.position().add(npc.getLookAngle().multiply(3, 0, 3));
					shape.teleportTo(s.overworld(), front.x, npc.getY(), front.z, java.util.Set.of(), npc.getYRot() + 180, 0f, false);
					break;
				}
			});
			boolean screamed = false;
			for (int i = 0; i < 30 && !screamed; i++) {
				ctx.waitTicks(5);
				screamed = server.computeOnServer(s -> count("voice.female.scream") + count("voice.male.scream")) > before;
			}
			check(screamed, "a townsperson who sees him screams");
			check(server.computeOnServer(s -> {
				for (var npc : s.overworld().getEntities(net.minecraft.world.entity.EntityTypes.MANNEQUIN, dev.halloween.game.Townsfolk::isNpc)) {
					String name = npc.getPlainTextName();
					if (name.startsWith("Mrs") && !Voices.female(npc) || name.startsWith("Mr.") && Voices.female(npc)) {
						return false;
					}
				}
				return true;
			}), "Mrs. screams with a woman's voice, Mr. with a man's");
			LOG.info("Voices heard: {}", (Object) server.computeOnServer(s -> AiStats.snapshot().getOrDefault("*total", Map.of()).toString()));
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			server.runOnServer(s -> {
				HwConfig.get().countdownSeconds = new HwConfig().countdownSeconds;
				HwConfig.get().botQteSuccessChance = new HwConfig().botQteSuccessChance;
			});
		}
		LOG.info("Voices client test finished OK");
	}

	private static String gender(LivingEntity who) {
		return Voices.female(who) ? "female" : "male";
	}

	private static int count(String key) {
		return AiStats.snapshot().getOrDefault("*total", Map.of()).getOrDefault(key, 0);
	}

	private static SoundBufferLibrary library(SoundManager manager) throws Exception {
		SoundEngine engine = (SoundEngine) field(manager, SoundEngine.class);
		return (SoundBufferLibrary) field(engine, SoundBufferLibrary.class);
	}

	private static Object field(Object owner, Class<?> type) throws Exception {
		for (Field f : owner.getClass().getDeclaredFields()) {
			if (type.isAssignableFrom(f.getType())) {
				f.setAccessible(true);
				return f.get(owner);
			}
		}
		throw new IllegalStateException("no " + type.getSimpleName() + " in " + owner.getClass());
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
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
