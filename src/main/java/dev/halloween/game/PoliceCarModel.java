package dev.halloween.game;

import com.mojang.math.Transformation;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;

/**
 * A black-and-white police car made of block displays: white doors, black hood, trunk and roof, dark glass, black
 * wheels, a red and blue light bar taking turns, headlights and tail lights. Brackett's patrol drives one (see
 * PatrolCar); the van that takes a survivor away is one parked (see Exits). Its front is +z before it is turned
 * by {@code yaw}; it stands on the ground at its position.
 */
final class PoliceCarModel {
	private final ServerLevel level;
	private final String tag;
	private final List<Display.BlockDisplay> parts = new ArrayList<>();
	private Display.BlockDisplay redLamp;
	private Display.BlockDisplay blueLamp;
	private double x;
	private double y;
	private double z;
	private float yaw;

	PoliceCarModel(ServerLevel level, double x, double y, double z, float yaw, String tag) {
		this.level = level;
		this.tag = tag;
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		this.build();
	}

	private static BlockState block(String id) {
		return BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace(id)).defaultBlockState();
	}

	private void build() {
		BlockState white = block("white_concrete");
		BlockState black = block("black_concrete");
		// Body: white doors, black hood and trunk, black underneath.
		this.part(black, 0f, 0.35f, 0f, 2.1f, 0.45f, 4.9f, false);
		this.part(white, 0f, 0.8f, 0f, 2.2f, 0.55f, 4.8f, false);
		this.part(black, 0f, 1.1f, 1.75f, 2.22f, 0.08f, 1.3f, false);
		this.part(black, 0f, 1.1f, -1.85f, 2.22f, 0.08f, 1.1f, false);
		// The cabin: dark glass all round under a black roof.
		this.part(Blocks.TINTED_GLASS.defaultBlockState(), 0f, 1.4f, -0.25f, 1.95f, 0.55f, 2.2f, false);
		this.part(black, 0f, 1.71f, -0.25f, 2.0f, 0.08f, 2.3f, false);
		// Wheels.
		for (float sx : new float[]{-1.08f, 1.08f}) {
			for (float sz : new float[]{-1.55f, 1.6f}) {
				this.part(block("black_wool"), sx, 0.35f, sz, 0.3f, 0.7f, 0.7f, false);
			}
		}
		// Lights: the bar on the roof, headlights, tail lights.
		this.redLamp = this.part(block("red_concrete"), -0.42f, 1.85f, -0.25f, 0.7f, 0.2f, 0.42f, true);
		this.blueLamp = this.part(block("blue_concrete"), 0.42f, 1.85f, -0.25f, 0.7f, 0.2f, 0.42f, false);
		for (float sx : new float[]{-0.7f, 0.7f}) {
			this.part(white, sx, 0.85f, 2.42f, 0.42f, 0.22f, 0.06f, true);
			this.part(block("red_concrete"), sx, 0.85f, -2.42f, 0.36f, 0.18f, 0.06f, true);
		}
	}

	private Display.BlockDisplay part(BlockState state, float cx, float cy, float cz, float sx, float sy, float sz, boolean lit) {
		Display.BlockDisplay d = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, this.level);
		d.setBlockState(state);
		d.setTransformation(new Transformation(new Vector3f(cx - sx / 2, cy - sy / 2, cz - sz / 2), null, new Vector3f(sx, sy, sz), null));
		d.setPosRotInterpolationDuration(3);
		d.setViewRange(3f);
		if (lit) {
			d.setBrightnessOverride(new Brightness(15, 15));
		}
		d.snapTo(this.x, this.y, this.z, this.yaw, 0f);
		d.addTag(this.tag);
		d.addTag(Leftovers.stamp());
		this.level.addFreshEntity(d);
		this.parts.add(d);
		return d;
	}

	void moveTo(double x, double y, double z, float yaw) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.yaw = yaw;
		for (Display.BlockDisplay d : this.parts) {
			d.snapTo(x, y, z, yaw, 0f);
		}
	}

	/** The lights on the roof take turns. */
	void flash(int ticks) {
		if (ticks % 6 == 0) {
			boolean red = ticks % 12 == 0;
			this.redLamp.setBrightnessOverride(new Brightness(red ? 15 : 2, red ? 15 : 0));
			this.blueLamp.setBrightnessOverride(new Brightness(red ? 2 : 15, red ? 0 : 15));
		}
	}

	void remove() {
		for (Display.BlockDisplay d : this.parts) {
			d.discard();
		}
		this.parts.clear();
	}
}
