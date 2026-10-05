package com.daniel.worldpainter.client.editor;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A 3D-software style camera: it looks at a pivot point from some distance, and can orbit around
 * it, pan it and zoom. The player (a spectator while editing) is moved to the camera position every
 * frame, so Minecraft renders exactly this view.
 */
public final class OrbitCamera {
	public double pivotX, pivotY, pivotZ;
	/** Minecraft yaw in degrees (0 = looking south / +Z). */
	public float yaw = 45;
	/** Degrees, positive looks down. */
	public float pitch = 35;
	public double distance = 30;

	public static final double MIN_DISTANCE = 2;
	public static final double MAX_DISTANCE = 400;

	public OrbitCamera(double x, double y, double z) {
		pivotX = x;
		pivotY = y;
		pivotZ = z;
	}

	public Vec3 forward() {
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
	}

	/** Screen-right direction (horizontal). */
	public Vec3 right() {
		double yr = Math.toRadians(yaw);
		return new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
	}

	/** Screen-up direction. */
	public Vec3 up() {
		Vec3 f = forward(), r = right();
		return new Vec3(r.y * f.z - r.z * f.y, r.z * f.x - r.x * f.z, r.x * f.y - r.y * f.x);
	}

	public Vec3 eye() {
		Vec3 f = forward();
		return new Vec3(pivotX - f.x * distance, pivotY - f.y * distance, pivotZ - f.z * distance);
	}

	/** Drag to orbit: pixels moved on screen. */
	public void orbit(double dx, double dy) {
		yaw = (float) ((yaw + dx * 0.4) % 360);
		pitch = (float) Math.clamp(pitch + dy * 0.4, -89.5, 89.5);
	}

	/** Drag to pan: the scene follows the mouse. */
	public void pan(double dx, double dy, double screenHeight) {
		double perPixel = distance * 1.2 / Math.max(1, screenHeight);
		Vec3 r = right(), u = up();
		pivotX += (r.x * dx - u.x * dy) * perPixel * -1;
		pivotY += (r.y * dx - u.y * dy) * perPixel * -1;
		pivotZ += (r.z * dx - u.z * dy) * perPixel * -1;
	}

	/** Positive steps zoom in. */
	public void zoom(double steps) {
		distance = Math.clamp(distance * Math.pow(0.85, steps), MIN_DISTANCE, MAX_DISTANCE);
	}

	/** Moves the pivot relative to the view (WASD / QE flying). */
	public void fly(double forwardAmount, double rightAmount, double upAmount) {
		double yr = Math.toRadians(yaw);
		double fx = -Math.sin(yr), fz = Math.cos(yr);
		Vec3 r = right();
		pivotX += fx * forwardAmount + r.x * rightAmount;
		pivotZ += fz * forwardAmount + r.z * rightAmount;
		pivotY += upAmount;
	}

	public void focus(double x, double y, double z) {
		pivotX = x;
		pivotY = y;
		pivotZ = z;
	}

	/** Blender-like fixed views. */
	public void viewTop() {
		pitch = 89.5f;
	}

	public void viewFront() {
		yaw = 0;
		pitch = 10;
	}

	public void viewSide() {
		yaw = 90;
		pitch = 10;
	}

	/** Puts the player's eyes at the camera position, looking at the pivot. */
	public void apply(LocalPlayer player) {
		Vec3 e = eye();
		player.setPos(e.x, e.y - player.getEyeHeight(), e.z);
		player.setYRot(yaw);
		player.setXRot(pitch);
		player.setYHeadRot(yaw);
		player.setDeltaMovement(Vec3.ZERO);
		player.setOldPosAndRot();
	}
}
