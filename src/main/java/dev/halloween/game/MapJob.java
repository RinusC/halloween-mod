package dev.halloween.game;

import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lays a {@link MapPlan} into the world around an origin, a few milliseconds a tick so the server keeps
 * running: column by column (ground, buildings, air above), then fixing fence and pane shapes, then the
 * signs, the map points, an autumn biome and the world rules for a match.
 */
public final class MapJob {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
	private static final long BUDGET_NANOS = 35_000_000L;
	private static MapJob current;

	private final ServerLevel level;
	private final BlockPos origin;
	private final MapPlan plan;
	private final CommandSourceStack source;
	private final String name;
	private int phase;
	/** Next column to lay (as an index over the square), next shape to fix. */
	private int column;
	private int fixed;
	private long lastReport;
	private final long started = System.currentTimeMillis();

	private MapJob(ServerLevel level, BlockPos origin, MapPlan plan, CommandSourceStack source, String name) {
		this.level = level;
		this.origin = origin;
		this.plan = plan;
		this.source = source;
		this.name = name;
	}

	public static boolean busy() {
		return current != null;
	}

	/** Starts building {@code plan} with its centre at {@code origin} (feet level of the ground). */
	public static void start(ServerLevel level, BlockPos origin, MapPlan plan, CommandSourceStack source, String name) {
		current = new MapJob(level, origin, plan, source, name);
	}

	/** Runs the whole job at once (tests). */
	public static void finishNow(MinecraftServer server) {
		while (current != null) {
			current.step(Long.MAX_VALUE);
		}
	}

	public static void tick(MinecraftServer server) {
		if (current != null) {
			current.step(BUDGET_NANOS);
		}
	}

	private int side() {
		return this.plan.radius * 2 + 1;
	}

	private void step(long budget) {
		long until = budget == Long.MAX_VALUE ? Long.MAX_VALUE : System.nanoTime() + budget;
		int total = this.side() * this.side();
		if (this.phase == 0) {
			while (this.column < total && System.nanoTime() < until) {
				this.layColumn(this.column++);
			}
			this.report(this.column * 80 / total);
			if (this.column >= total) {
				this.phase = 1;
			}
		} else if (this.phase == 1) {
			List<BlockPos> fix = this.plan.shapeFix;
			while (this.fixed < fix.size() && System.nanoTime() < until) {
				BlockPos pos = this.origin.offset(fix.get(this.fixed++));
				BlockState state = this.level.getBlockState(pos);
				BlockState updated = Block.updateFromNeighbourShapes(state, this.level, pos);
				if (updated != state) {
					this.level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
				}
			}
			this.report(80 + this.fixed * 15 / Math.max(1, fix.size()));
			if (this.fixed >= fix.size()) {
				this.phase = 2;
			}
		} else {
			this.finish();
			current = null;
		}
	}

	/** One column of the square: every height of the plan, only where the world differs. */
	private void layColumn(int index) {
		int x = index % this.side() - this.plan.radius;
		int z = index / this.side() - this.plan.radius;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = this.plan.yMin; y <= this.plan.yMax; y++) {
			BlockState want = this.plan.at(x, y, z);
			pos.set(this.origin.getX() + x, this.origin.getY() + y, this.origin.getZ() + z);
			if (this.level.getBlockState(pos) != want) {
				if (want.getBlock() instanceof net.minecraft.world.level.block.CarvedPumpkinBlock) {
					// A pumpkin looks round for a golem to make: the chunks it looks into must be there.
					for (int dx = -3; dx <= 3; dx += 3) {
						for (int dz = -3; dz <= 3; dz += 3) {
							this.level.getChunk((pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4);
						}
					}
				}
				this.level.setBlock(pos, want, FLAGS);
			}
		}
	}

	private void report(int percent) {
		long now = System.currentTimeMillis();
		if (now - this.lastReport > 3000) {
			this.lastReport = now;
			this.source.sendSystemMessage(Component.translatable("halloween.cmd.map_progress", this.name, percent).withStyle(ChatFormatting.GRAY));
		}
	}

	private void finish() {
		MinecraftServer server = this.level.getServer();
		// Sign texts.
		this.plan.signs.forEach((local, lines) -> {
			BlockEntity be = this.level.getBlockEntity(this.origin.offset(local));
			if (be instanceof SignBlockEntity sign) {
				SignText text = new SignText();
				for (int i = 0; i < Math.min(4, lines.length); i++) {
					text = text.setMessage(i, Component.literal(lines[i]));
				}
				sign.setText(text, true);
				sign.setText(text, false);
			}
		});
		// Map points.
		MapPoints points = MapPoints.get(this.level);
		points.clearAll();
		for (Object[] p : this.plan.points) {
			points.add((MapPoints.Type) p[0], this.origin.offset((BlockPos) p[1]));
		}
		// Autumn: the badlands' dry brown grass and leaves; pieces small enough for the command's limit.
		CommandSourceStack console = server.createCommandSourceStack().withLevel(this.level).withSuppressedOutput();
		int r = this.plan.radius;
		for (int x = -r; x <= r; x += 32) {
			for (int z = -r; z <= r; z += 32) {
				// Within the world's height (a map built near the bottom of a flat world would reach under it).
				int yLow = Math.max(this.level.getMinY(), this.origin.getY() + this.plan.yMin - 4);
				int yHigh = Math.min(this.level.getMaxY(), this.origin.getY() + this.plan.yMax);
				for (int y = yLow; y <= yHigh; y += 32) {
					int x2 = Math.min(r, x + 31);
					int z2 = Math.min(r, z + 31);
					int y2 = Math.min(yHigh, y + 31);
					server.getCommands().performPrefixedCommand(console, String.format(Locale.ROOT, "fillbiome %d %d %d %d %d %d minecraft:badlands",
						this.origin.getX() + x, y, this.origin.getZ() + z,
						this.origin.getX() + x2, y2, this.origin.getZ() + z2));
				}
			}
		}
		// Leftovers inside the town (dropped items, old mannequins), and the rules for a night in Haddonfield.
		String box = String.format(Locale.ROOT, "x=%d,y=%d,z=%d,dx=%d,dy=%d,dz=%d", this.origin.getX() - r, this.origin.getY() - 4,
			this.origin.getZ() - r, 2 * r, 50, 2 * r);
		for (String command : new String[]{"kill @e[type=item," + box + "]", "kill @e[type=minecraft:mannequin," + box + "]",
			"gamerule advance_time false", "gamerule advance_weather false", "gamerule spawn_mobs false",
			"gamerule natural_health_regeneration false", "gamerule show_death_messages false", "gamerule locator_bar false",
			"gamerule immediate_respawn true", "time set midnight", "weather clear"}) {
			server.getCommands().performPrefixedCommand(console, command);
		}
		long seconds = (System.currentTimeMillis() - this.started) / 1000;
		this.source.sendSuccess(() -> Component.translatable("halloween.cmd.map_done", this.name, seconds, points.all().values().stream().mapToInt(List::size).sum())
			.withStyle(ChatFormatting.GREEN), true);
		if (this.source.getPlayer() != null) {
			BlockPos spawn = points.first(MapPoints.Type.SPAWN_CIVILIAN);
			if (spawn != null) {
				this.source.getPlayer().teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
			}
		}
	}
}
