package dev.halloween.game;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.halloween.HwConfig;
import dev.halloween.Role;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class HwCommands {
	private HwCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		var role = Commands.argument("players", EntityArgument.players());
		for (Role r : Role.values()) {
			role.then(Commands.literal(r.name().toLowerCase(java.util.Locale.ROOT)).executes(ctx -> setRole(ctx, r)));
		}
		dispatcher.register(Commands.literal("hw")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("role").then(role))
			.then(Commands.literal("arena").executes(HwCommands::arena))
			.then(Commands.literal("mannequin").executes(HwCommands::mannequin))
			.then(Commands.literal("bloodthirst")
				.then(Commands.argument("value", FloatArgumentType.floatArg(0, ShapeState.BLOODTHIRST_MAX)).executes(HwCommands::bloodthirst)))
			.then(Commands.literal("reset").executes(ctx -> {
				MatchManager.reset(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.translatable("halloween.cmd.reset"), true);
				return 1;
			}))
			.then(Commands.literal("reload").executes(ctx -> {
				HwConfig.load();
				ctx.getSource().sendSuccess(() -> Component.translatable("halloween.cmd.reloaded"), true);
				return 1;
			})));
	}

	private static int setRole(CommandContext<CommandSourceStack> ctx, Role role) throws CommandSyntaxException {
		Collection<ServerPlayer> players = EntityArgument.getPlayers(ctx, "players");
		for (ServerPlayer player : players) {
			MatchManager.applyRole(player, role);
		}
		ctx.getSource().sendSuccess(() -> Component.translatable("halloween.cmd.role", players.size(), Component.translatable("halloween.role." + role.name().toLowerCase(java.util.Locale.ROOT))), true);
		return players.size();
	}

	private static int arena(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ArenaBuilder.build(source, source.getLevel(), BlockPos.containing(source.getPosition()));
		source.sendSuccess(() -> Component.translatable("halloween.cmd.arena"), true);
		return 1;
	}

	private static int mannequin(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		var pos = source.getPosition();
		ArenaBuilder.summonMannequin(source.getServer().createCommandSourceStack().withLevel(source.getLevel()).withSuppressedOutput(),
			pos.x, pos.y, pos.z, source.getRotation().y);
		return 1;
	}

	private static int bloodthirst(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		ShapeState state = MatchManager.shapeState(player);
		if (state == null) {
			ctx.getSource().sendFailure(Component.translatable("halloween.cmd.not_shape"));
			return 0;
		}
		state.bloodthirst = FloatArgumentType.getFloat(ctx, "value");
		return 1;
	}
}
