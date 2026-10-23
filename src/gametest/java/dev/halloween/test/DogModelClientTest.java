package dev.halloween.test;

import dev.halloween.client.ClientDarkness;
import dev.halloween.client.DogRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The side job's dog (client.DogRenderer): from the side, from the front, the head close, growling, walking, running. */
public class DogModelClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-dog-model");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("dogmodel")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("time set noon");
			server.runCommand("gamerule advance_time false");
			ctx.runOnClient(mc -> {
				ClientDarkness.enabled = false;
				mc.options.setCameraType(CameraType.FIRST_PERSON);
			});
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = s.getPlayerList().getPlayers().getFirst();
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				run(s, "fill " + (x - 8) + " 150 " + (z - 8) + " " + (x + 8) + " 150 " + (z + 8) + " minecraft:smooth_stone");
				run(s, "fill " + (x - 8) + " 151 " + (z - 8) + " " + (x + 8) + " 156 " + (z + 8) + " minecraft:air");
				run(s, "summon minecraft:wolf " + (x + 0.5) + " 151 " + (z + 0.5) + " {NoAI:1b,Rotation:[90f,0f],Tags:[\"hw_test\"]}");
				p.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
				return new int[]{x, z};
			});
			// Facing west (yaw 90): from the south we see its side.
			shot(ctx, server, at[0] + 0.5, 151.7, at[1] + 3.2, 180f, 5f, "dog_side", -1f, 0f);
			shot(ctx, server, at[0] - 2.2, 151.9, at[1] + 1.4, 240f, 14f, "dog_front", -1f, 0f);
			shot(ctx, server, at[0] - 1.6, 152.0, at[1] + 0.75, 245f, 18f, "dog_head", -1f, 0f);
			shot(ctx, server, at[0] - 1.9, 151.9, at[1] + 1.1, 240f, 14f, "dog_growl", 0f, 1f);
			shot(ctx, server, at[0] + 0.5, 151.7, at[1] + 3.2, 180f, 5f, "dog_walk", 0.4f, 0f);
			shot(ctx, server, at[0] + 0.5, 151.7, at[1] + 3.2, 180f, 5f, "dog_run", 1f, 0f);
			shot(ctx, server, at[0] + 2.6, 152.3, at[1] - 1.8, 55f, 20f, "dog_back", -1f, 0f);
			DogRenderer.forceSpeed = -1f;
			DogRenderer.forceGrowl = -1f;
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
		}
		LOG.info("Dog model client test finished OK");
	}

	private static void shot(ClientGameTestContext ctx, TestServerContext server, double x, double y, double z, float yaw, float pitch, String name,
		float speed, float growl) {
		DogRenderer.forceSpeed = speed;
		DogRenderer.forceGrowl = growl;
		server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().teleportTo(s.overworld(), x, y - 1.62, z, java.util.Set.of(), yaw, pitch, false));
		ctx.waitTicks(speed >= 0 ? 13 : 10);
		ctx.takeScreenshot(name);
	}

	private static void run(MinecraftServer s, String command) {
		s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), command);
	}
}
