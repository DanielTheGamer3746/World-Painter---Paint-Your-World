package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.client.gui.Gfx;

/**
 * Maps between the 3D world and the 2D screen for the camera Beta draws the world with: the eye
 * position and direction of the player, Beta's 70 degree field of view and the window's shape.
 * Picking and outlines line up with what Minecraft draws.
 */
public final class Projection {
	private static final double NEAR = 0.05;
	private static final double TAN_HALF_FOV = Math.tan(Math.toRadians(70) / 2);

	private V3 eye = new V3(0, 0, 0);
	private V3 forward = new V3(0, 0, 1), right = new V3(-1, 0, 0), up = new V3(0, 1, 0);
	private int width = 1, height = 1;
	private double aspect = 1;
	private boolean valid;

	/** Call once per frame. {@code guiWidth/guiHeight} are the GUI size, {@code aspect} the window's width / height. */
	public void update(OrbitCamera cam, int guiWidth, int guiHeight, double aspect) {
		valid = cam != null;
		if (!valid) {
			return;
		}
		eye = cam.eye();
		forward = cam.forward();
		right = cam.right();
		up = cam.up();
		width = Math.max(1, guiWidth);
		height = Math.max(1, guiHeight);
		this.aspect = aspect > 0 ? aspect : width / (double) height;
	}

	public boolean valid() {
		return valid;
	}

	public V3 cameraPos() {
		return eye;
	}

	/** Direction (normalized) of the ray from the camera through a screen point. */
	public V3 ray(double guiX, double guiY) {
		double nx = 2.0 * guiX / width - 1.0;
		double ny = 1.0 - 2.0 * guiY / height;
		double rx = nx * TAN_HALF_FOV * aspect, uy = ny * TAN_HALF_FOV;
		return new V3(forward.x() + right.x() * rx + up.x() * uy,
				forward.y() + right.y() * rx + up.y() * uy,
				forward.z() + right.z() * rx + up.z() * uy).normalize();
	}

	/** Camera space of a point: (right, up, depth). */
	private double[] view(double x, double y, double z) {
		V3 d = new V3(x - eye.x(), y - eye.y(), z - eye.z());
		return new double[]{d.dot(right), d.dot(up), d.dot(forward)};
	}

	private double sx(double[] v) {
		return (v[0] / (v[2] * TAN_HALF_FOV * aspect) + 1) * 0.5 * width;
	}

	private double sy(double[] v) {
		return (1 - v[1] / (v[2] * TAN_HALF_FOV)) * 0.5 * height;
	}

	/** Screen position of a world point, or null if it is behind the camera. */
	public double[] project(double x, double y, double z) {
		if (!valid) {
			return null;
		}
		double[] v = view(x, y, z);
		if (v[2] < NEAR) {
			return null;
		}
		return new double[]{sx(v), sy(v)};
	}

	/** Draws a world-space line, cut at the camera and at the screen edges. */
	public void line(Gfx g, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		if (!valid) {
			return;
		}
		double[] a = view(x1, y1, z1), b = view(x2, y2, z2);
		if (a[2] < NEAR && b[2] < NEAR) {
			return;
		}
		if (a[2] < NEAR) {
			a = toNear(a, b);
		} else if (b[2] < NEAR) {
			b = toNear(b, a);
		}
		g.line(sx(a), sy(a), sx(b), sy(b), color);
	}

	private static double[] toNear(double[] behind, double[] front) {
		double t = (NEAR - behind[2]) / (front[2] - behind[2]);
		return new double[]{behind[0] + (front[0] - behind[0]) * t, behind[1] + (front[1] - behind[1]) * t, NEAR};
	}

	/** Outline of a box given by its min corner and max corner (exclusive, in blocks). */
	public void box(Gfx g, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		line(g, x0, y0, z0, x1, y0, z0, color);
		line(g, x0, y0, z1, x1, y0, z1, color);
		line(g, x0, y1, z0, x1, y1, z0, color);
		line(g, x0, y1, z1, x1, y1, z1, color);
		line(g, x0, y0, z0, x0, y0, z1, color);
		line(g, x1, y0, z0, x1, y0, z1, color);
		line(g, x0, y1, z0, x0, y1, z1, color);
		line(g, x1, y1, z0, x1, y1, z1, color);
		line(g, x0, y0, z0, x0, y1, z0, color);
		line(g, x1, y0, z0, x1, y1, z0, color);
		line(g, x0, y0, z1, x0, y1, z1, color);
		line(g, x1, y0, z1, x1, y1, z1, color);
	}

	/** One block, slightly enlarged so the outline sits just outside its faces. */
	public void block(Gfx g, int x, int y, int z, int color) {
		double e = 0.004;
		box(g, x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e, color);
	}
}
