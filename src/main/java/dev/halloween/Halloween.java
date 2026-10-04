package dev.halloween;

import dev.halloween.game.HwCommands;
import dev.halloween.game.LightSystem;
import dev.halloween.game.MatchManager;
import dev.halloween.net.HwNet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Halloween implements ModInitializer {
	public static final String MOD_ID = "halloween";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HwConfig.load();
		HwSounds.init();
		HwNet.registerPayloads();

		ServerPlayNetworking.registerGlobalReceiver(HwNet.SenseToggle.TYPE, (payload, ctx) -> {
			var state = MatchManager.shapeState(ctx.player());
			if (state != null) {
				state.senseHeld = payload.active();
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(HwNet.ShapeJump.TYPE, (payload, ctx) -> MatchManager.onShapeJump(ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(HwNet.QtePress.TYPE, (payload, ctx) -> MatchManager.onQtePress(ctx.player()));

		ServerTickEvents.END_SERVER_TICK.register(MatchManager::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> MatchManager.markDirty());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> MatchManager.clearAll());

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onAttack(player, entity));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> MatchManager.onUseEntity(player, hand, entity));
		UseBlockCallback.EVENT.register(LightSystem::onUseBlock);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> MatchManager.allowDamage(entity, source.getEntity()));
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> MatchManager.allowDeath(entity));

		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> HwCommands.register(dispatcher));
		LOGGER.info("Halloween loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
