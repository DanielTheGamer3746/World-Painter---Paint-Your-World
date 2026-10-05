package com.daniel.worldpainter.client.preview;

/**
 * The camera of the 3D preview: it looks at a pivot point from some distance and can orbit around
 * it, pan it and zoom, like the 3D structure editor. Unlike that editor it is not tied to the player,
 * so it also works before the world exists.
 */
public final class PreviewCamera {
	public static final double MIN_DISTANCE = 4;
	public static final double MAX_DISTANCE = 3000;

	public double pivotX, pivotY = 64, pivotZ;
	/** Minecraft yaw in degrees (0 = looking south / +Z, 180 = north, which matches the 2D map). */
	public double yaw = 180;
	/** Degrees, positive looks down. */
	public double pitch = 40;
	public double distance = 160;
	/** Vertical field of view in degrees. */
	public double fov = 60;

	// Derived each time something changes (see update()).
	public double eyeX, eyeY, eyeZ;
	public double fx, fy, fz;
	public double rx, ry, rz;
	public double ux, uy, uz;
	private double tanHalf;

	public PreviewCamera() {
		update();
	}

	/** Recomputes the eye position and view directions. Call after changing the fields directly. */
	public void update() {
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		fx = -Math.sin(yr) * Math.cos(pr);
		fy = -Math.sin(pr);
		fz = Math.cos(yr) * Math.cos(pr);
		rx = -Math.cos(yr);
		ry = 0;
		rz = -Math.sin(yr);
		// up = right x forward
		ux = ry * fz - rz * fy;
		uy = rz * fx - rx * fz;
		uz = rx * fy - ry * fx;
		eyeX = pivotX - fx * distance;
		eyeY = pivotY - fy * distance;
		eyeZ = pivotZ - fz * distance;
		tanHalf = Math.tan(Math.toRadians(fov) / 2);
	}

	public PreviewCamera copy() {
		PreviewCamera c = new PreviewCamera();
		c.pivotX = pivotX;
		c.pivotY = pivotY;
		c.pivotZ = pivotZ;
		c.yaw = yaw;
		c.pitch = pitch;
		c.distance = distance;
		c.fov = fov;
		c.update();
		return c;
	}

	public boolean sameView(PreviewCamera o) {
		return o != null && pivotX == o.pivotX && pivotY == o.pivotY && pivotZ == o.pivotZ && yaw == o.yaw
				&& pitch == o.pitch && distance == o.distance && fov == o.fov;
	}

	/**
	 * Direction of the ray through a point of a view that is {@code w x h} big ({@code sx, sy} from its
	 * top left corner). Written into {@code out} (normalized).
	 */
	public void ray(double sx, double sy, double w, double h, double[] out) {
		double half = h / 2.0;
		double a = (sx - w / 2.0) / half * tanHalf;
		double b = -(sy - half) / half * tanHalf;
		double dx = fx + rx * a + ux * b;
		double dy = fy + ry * a + uy * b;
		double dz = fz + rz * a + uz * b;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		out[0] = dx / len;
		out[1] = dy / len;
		out[2] = dz / len;
	}

	/** Screen position (in a {@code w x h} view) of a world point, or false if it is behind the camera. */
	public boolean project(double x, double y, double z, double w, double h, double[] out) {
		double vx = x - eyeX, vy = y - eyeY, vz = z - eyeZ;
		double depth = vx * fx + vy * fy + vz * fz;
		if (depth < 0.05) {
			return false;
		}
		double px = vx * rx + vy * ry + vz * rz;
		double py = vx * ux + vy * uy + vz * uz;
		double half = h / 2.0;
		out[0] = w / 2.0 + px / depth / tanHalf * half;
		out[1] = half - py / depth / tanHalf * half;
		out[2] = depth;
		return true;
	}

	/** Size of one screen pixel at a distance, in blocks (for a view {@code h} pixels high). */
	public double pixelSize(double depth, double h) {
		return depth * tanHalf * 2 / Math.max(1, h);
	}

	// ---- controls ----

	/** Drag to orbit: pixels moved on screen. */
	public void orbit(double dx, double dy) {
		yaw = ((yaw + dx * 0.4) % 360 + 360) % 360;
		pitch = Math.clamp(pitch + dy * 0.4, -89.5, 89.5);
		update();
	}

	/** Drag to pan: the scene follows the mouse ({@code viewHeight} in the same units as dx/dy). */
	public void pan(double dx, double dy, double viewHeight) {
		double perPixel = distance * tanHalf * 2 / Math.max(1, viewHeight);
		pivotX -= (rx * dx - ux * dy) * perPixel;
		pivotY -= (ry * dx - uy * dy) * perPixel;
		pivotZ -= (rz * dx - uz * dy) * perPixel;
		update();
	}

	/** Positive steps zoom in. */
	public void zoom(double steps) {
		distance = Math.clamp(distance * Math.pow(0.85, steps), MIN_DISTANCE, MAX_DISTANCE);
		update();
	}

	/** Moves the pivot relative to the view direction (WASD / QE). */
	public void fly(double forwardAmount, double rightAmount, double upAmount) {
		double yr = Math.toRadians(yaw);
		pivotX += -Math.sin(yr) * forwardAmount + rx * rightAmount;
		pivotZ += Math.cos(yr) * forwardAmount + rz * rightAmount;
		pivotY += upAmount;
		update();
	}

	public void focus(double x, double y, double z) {
		pivotX = x;
		pivotY = y;
		pivotZ = z;
		update();
	}

	public void viewTop() {
		pitch = 89.5;
		update();
	}

	/** Looking north, like the map is oriented. */
	public void viewFront() {
		yaw = 180;
		pitch = 12;
		update();
	}

	/** Looking west. */
	public void viewSide() {
		yaw = 90;
		pitch = 12;
		update();
	}
}
