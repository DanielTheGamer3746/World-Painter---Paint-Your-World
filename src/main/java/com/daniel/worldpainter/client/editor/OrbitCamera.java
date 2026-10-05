package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.util.MathUtil;

/**
 * A 3D-software style camera: it looks at a pivot point from some distance, and can orbit around
 * it, pan it and zoom. The player is put at the camera position every frame (the game is paused),
 * so Minecraft draws exactly this view.
 */
public final class OrbitCamera {
	public double pivotX, pivotY, pivotZ;
	/** Minecraft yaw in degrees (0 = looking south / +Z). */
	public float yaw = 45;
	/** Degrees, positive looks down. */
	public float pitch = 35;
	public double distance = 30;

	public static final double MIN_DISTANCE = 2;
	public static final double MAX_DISTANCE = 160;

	public OrbitCamera(double x, double y, double z) {
		pivotX = x;
		pivotY = y;
		pivotZ = z;
	}

	public V3 forward() {
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		return new V3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
	}

	/** Screen-right direction (horizontal). */
	public V3 right() {
		double yr = Math.toRadians(yaw);
		return new V3(-Math.cos(yr), 0, -Math.sin(yr));
	}

	/** Screen-up direction. */
	public V3 up() {
		return right().cross(forward());
	}

	public V3 eye() {
		V3 f = forward();
		return new V3(pivotX - f.x() * distance, pivotY - f.y() * distance, pivotZ - f.z() * distance);
	}

	/** Drag to orbit: pixels moved on screen. */
	public void orbit(double dx, double dy) {
		yaw = (float) ((yaw + dx * 0.4) % 360);
		pitch = (float) MathUtil.clamp(pitch + dy * 0.4, -89.5, 89.5);
	}

	/** Drag to pan: the scene follows the mouse. */
	public void pan(double dx, double dy, double screenHeight) {
		double perPixel = distance * 1.2 / Math.max(1, screenHeight);
		V3 r = right(), u = up();
		pivotX -= (r.x() * dx - u.x() * dy) * perPixel;
		pivotY -= (r.y() * dx - u.y() * dy) * perPixel;
		pivotZ -= (r.z() * dx - u.z() * dy) * perPixel;
	}

	/** Positive steps zoom in. */
	public void zoom(double steps) {
		distance = MathUtil.clamp(distance * Math.pow(0.85, steps), MIN_DISTANCE, MAX_DISTANCE);
	}

	/** Moves the pivot relative to the view (WASD flying). */
	public void fly(double forwardAmount, double rightAmount, double upAmount) {
		double yr = Math.toRadians(yaw);
		double fx = -Math.sin(yr), fz = Math.cos(yr);
		V3 r = right();
		pivotX += fx * forwardAmount + r.x() * rightAmount;
		pivotZ += fz * forwardAmount + r.z() * rightAmount;
		pivotY = MathUtil.clamp(pivotY + upAmount, -16.0, 200.0);
	}

	public void focus(double x, double y, double z) {
		pivotX = x;
		pivotY = y;
		pivotZ = z;
	}

	public void viewTop() {
		pitch = 89.5f;
	}

	public void viewFront() {
		yaw = 180;
		pitch = 10;
	}

	public void viewSide() {
		yaw = 90;
		pitch = 10;
	}

	public OrbitCamera copy() {
		OrbitCamera c = new OrbitCamera(pivotX, pivotY, pivotZ);
		c.yaw = yaw;
		c.pitch = pitch;
		c.distance = distance;
		return c;
	}
}
