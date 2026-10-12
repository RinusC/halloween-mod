package dev.halloween.test;

import dev.halloween.HwConfig;
import dev.halloween.client.ClientDarkness;
import dev.halloween.game.BotPlayer;
import dev.halloween.game.HwItems;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every item's 3D model (tools/item_models.pl): held (first person, and by a mannequin facing us), lying about, and the icons in the hotbar. */
public class ItemModelsClientTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("halloween-item-models");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (System.getenv("HW_SIM") != null || TestFilter.skip("items")) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			TestServerContext server = sp.getServer();
			ctx.waitTicks(40);
			server.runCommand("execute as @p at @p run hw arena");
			ctx.waitTicks(40);
			server.runOnServer(s -> {
				HwConfig cfg = HwConfig.get();
				cfg.countdownSeconds = 1;
				cfg.botsFillTo = 2;
				cfg.eventsEnabled = false;
			});
			dev.halloween.game.Weather.forced = dev.halloween.game.Weather.CLEAR;
			server.runCommand("hw start");
			ctx.waitTicks(80);
			server.runCommand("time set noon");
			ctx.runOnClient(mc -> ClientDarkness.enabled = false);
			int[] at = server.computeOnServer(s -> {
				ServerPlayer p = player(s);
				int x = (int) Math.floor(p.getX());
				int z = (int) Math.floor(p.getZ());
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
					"fill " + (x - 10) + " 150 " + (z - 10) + " " + (x + 10) + " 150 " + (z + 10) + " minecraft:smooth_stone");
				p.teleportTo(s.overworld(), x + 0.5, 151, z + 0.5, java.util.Set.of(), 0f, 10f, false);
				p.getInventory().clearContent();
				return new int[]{x, z};
			});
			// A mannequin a few steps off, turned a little to show its right hand.
			server.runOnServer(s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
				"summon minecraft:mannequin " + (at[0] + 0.5) + " 151 " + (at[1] + 3.1) + " {Rotation:[150f,0f],immovable:1b,Tags:[\"hw_test\"]}"));
			HwItems.Kind[] kinds = HwItems.Kind.values();
			// The hotbar full of them: the inventory keeps flat icons.
			server.runOnServer(s -> {
				for (int i = 0; i < 9; i++) {
					player(s).getInventory().setItem(i, HwItems.create(kinds[i * 4 % kinds.length]));
				}
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("items_hotbar");
			for (HwItems.Kind kind : kinds) {
				server.runOnServer(s -> player(s).setItemInHand(InteractionHand.MAIN_HAND, HwItems.create(kind)));
				ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
				ctx.waitTicks(8);
				ctx.takeScreenshot("items_" + kind.key() + "_first");
				server.runOnServer(s -> {
					for (var e : s.overworld().getEntities((net.minecraft.world.entity.Entity) null, player(s).getBoundingBox().inflate(8), e -> e.entityTags().contains("hw_test"))) {
						((net.minecraft.world.entity.LivingEntity) e).setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, HwItems.create(kind));
					}
				});
				ctx.waitTicks(4);
				ctx.takeScreenshot("items_" + kind.key() + "_held");
			}
			ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
			// All of them lying about on the floor in rows, looked down at.
			server.runOnServer(s -> {
				ServerPlayer p = player(s);
				p.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
				for (int i = 0; i < kinds.length; i++) {
					ItemEntity e = new ItemEntity(s.overworld(), at[0] - 4 + (i % 8) * 1.2, 151.05, at[1] + 1.5 + (i / 8) * 1.2, HwItems.create(kinds[i]), 0, 0, 0);
					e.setNeverPickUp();
					e.setNoGravity(true);
					s.overworld().addFreshEntity(e);
				}
				p.teleportTo(s.overworld(), at[0] + 0.5, 152.5, at[1] - 2.5, java.util.Set.of(), 0f, 32f, false);
				s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=hw_test]");
			});
			ctx.waitTicks(20);
			ctx.takeScreenshot("items_ground");
			server.runCommand("kill @e[type=item]");
			server.runCommand("hw stop");
			ctx.waitTicks(5);
			ctx.runOnClient(mc -> ClientDarkness.enabled = true);
			dev.halloween.game.Weather.forced = -1;
		}
		LOG.info("Item models client test finished OK");
	}

	private static ServerPlayer player(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (!(p instanceof BotPlayer)) {
				return p;
			}
		}
		throw new IllegalStateException("no player");
	}
}
