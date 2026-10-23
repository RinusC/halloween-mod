package dev.halloween.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/** Projects world positions onto the GUI so HUD markers can sit on top of things in the world. */
public final class Projection {
	private Projection() {
	}

	/** Screen position in GUI pixels plus distance, or null when the point is behind the camera. */
	public record Point(float x, float y, double distance) {
	}

	public static Point toScreen(Vec3 world, int guiWidth, int guiHeight) {
		if (ClientVr.active()) {
			// In the headset the HUD is a panel before the face: marks go on the world instead (VrMarks).
			return null;
		}
		Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		Vec3 d = world.subtract(camera.position());
		Vector3fc f = camera.forwardVector();
		Vector3fc u = camera.upVector();
		Vector3fc l = camera.leftVector();
		double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
		if (z < 0.05) {
			return null;
		}
		double right = -(d.x * l.x() + d.y * l.y() + d.z * l.z());
		double up = d.x * u.x() + d.y * u.y() + d.z * u.z();
		double tanY = Math.tan(Math.toRadians(camera.getFov() / 2.0));
		double aspect = (double) guiWidth / guiHeight;
		float sx = (float) (guiWidth / 2.0 + right / z / (tanY * aspect) * guiWidth / 2.0);
		float sy = (float) (guiHeight / 2.0 - up / z / tanY * guiHeight / 2.0);
		return new Point(sx, sy, d.length());
	}
}
