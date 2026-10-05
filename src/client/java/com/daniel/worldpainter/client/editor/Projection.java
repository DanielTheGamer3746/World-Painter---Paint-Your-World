package com.daniel.worldpainter.client.editor;

import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Maps between the 3D world and the 2D screen using the camera's own view/projection matrix, so
 * picking and outlines line up exactly with what Minecraft draws.
 */
public final class Projection {
	private static final float NEAR_W = 0.05f;

	private final Matrix4f matrix = new Matrix4f();
	private final Matrix4f inverse = new Matrix4f();
	private Vec3 camera = Vec3.ZERO;
	private final Vector3f forward = new Vector3f(0, 0, 1);
	private int width = 1, height = 1;
	private boolean valid;

	/** Call once per frame before using the other methods. {@code width/height} are the GUI size. */
	public void update(Camera cam, int guiWidth, int guiHeight) {
		valid = cam != null && cam.isInitialized();
		if (!valid) {
			return;
		}
		cam.getViewRotationProjectionMatrix(matrix);
		inverse.set(matrix).invert();
		camera = cam.position();
		forward.set(cam.forwardVector());
		width = Math.max(1, guiWidth);
		height = Math.max(1, guiHeight);
	}

	public boolean valid() {
		return valid;
	}

	public Vec3 cameraPos() {
		return camera;
	}

	/** Direction (normalized) of the ray from the camera through a screen point. */
	public Vec3 ray(double guiX, double guiY) {
		float nx = (float) (2.0 * guiX / width - 1.0);
		float ny = (float) (1.0 - 2.0 * guiY / height);
		Vector4f p = new Vector4f(nx, ny, 0.5f, 1f);
		inverse.transform(p);
		if (Math.abs(p.w) < 1e-9f) {
			return new Vec3(forward.x, forward.y, forward.z);
		}
		Vec3 dir = new Vec3(p.x / p.w, p.y / p.w, p.z / p.w).normalize();
		if (dir.x * forward.x + dir.y * forward.y + dir.z * forward.z < 0) {
			dir = dir.scale(-1);
		}
		return dir;
	}

	private Vector4f clip(double x, double y, double z) {
		Vector4f v = new Vector4f((float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z), 1f);
		return matrix.transform(v);
	}

	/** Screen position of a world point, or null if it is behind the camera. */
	public double[] project(double x, double y, double z) {
		Vector4f c = clip(x, y, z);
		if (c.w < NEAR_W) {
			return null;
		}
		return new double[]{(c.x / c.w + 1) * 0.5 * width, (1 - c.y / c.w) * 0.5 * height};
	}

	/** Draws a world-space line, clipped at the camera and the screen edges. */
	public void line(GuiGraphicsExtractor g, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		if (!valid) {
			return;
		}
		Vector4f a = clip(x1, y1, z1);
		Vector4f b = clip(x2, y2, z2);
		if (a.w < NEAR_W && b.w < NEAR_W) {
			return;
		}
		if (a.w < NEAR_W) {
			a = lerpToNear(a, b);
		} else if (b.w < NEAR_W) {
			b = lerpToNear(b, a);
		}
		double ax = (a.x / a.w + 1) * 0.5 * width, ay = (1 - a.y / a.w) * 0.5 * height;
		double bx = (b.x / b.w + 1) * 0.5 * width, by = (1 - b.y / b.w) * 0.5 * height;
		drawLine2d(g, ax, ay, bx, by, width, height, color);
	}

	private static Vector4f lerpToNear(Vector4f behind, Vector4f front) {
		float t = (NEAR_W - behind.w) / (front.w - behind.w);
		return new Vector4f(
				behind.x + (front.x - behind.x) * t,
				behind.y + (front.y - behind.y) * t,
				behind.z + (front.z - behind.z) * t,
				NEAR_W);
	}

	/** Outline of a box given by its min corner and max corner (exclusive, in blocks). */
	public void box(GuiGraphicsExtractor g, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
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
	public void block(GuiGraphicsExtractor g, int x, int y, int z, int color) {
		double e = 0.004;
		box(g, x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e, color);
	}

	/** A 2D line clipped to the screen (Liang-Barsky), drawn as horizontal/vertical runs. */
	static void drawLine2d(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int w, int h, int color) {
		double dx = x1 - x0, dy = y1 - y0;
		double t0 = 0, t1 = 1;
		double[] p = {-dx, dx, -dy, dy};
		double[] q = {x0, w - 1 - x0, y0, h - 1 - y0};
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0) {
				if (q[i] < 0) {
					return;
				}
			} else {
				double r = q[i] / p[i];
				if (p[i] < 0) {
					if (r > t1) {
						return;
					}
					t0 = Math.max(t0, r);
				} else {
					if (r < t0) {
						return;
					}
					t1 = Math.min(t1, r);
				}
			}
		}
		double sx = x0 + t0 * dx, sy = y0 + t0 * dy, ex = x0 + t1 * dx, ey = y0 + t1 * dy;
		int steps = (int) Math.ceil(Math.max(Math.abs(ex - sx), Math.abs(ey - sy)));
		if (steps == 0) {
			g.fill((int) sx, (int) sy, (int) sx + 1, (int) sy + 1, color);
			return;
		}
		boolean horizontal = Math.abs(ex - sx) >= Math.abs(ey - sy);
		int runStartX = (int) Math.round(sx), runStartY = (int) Math.round(sy);
		int lastX = runStartX, lastY = runStartY;
		for (int i = 1; i <= steps; i++) {
			int px = (int) Math.round(sx + (ex - sx) * i / steps);
			int py = (int) Math.round(sy + (ey - sy) * i / steps);
			boolean sameRun = horizontal ? py == runStartY : px == runStartX;
			if (!sameRun) {
				fillRun(g, runStartX, runStartY, lastX, lastY, color);
				runStartX = px;
				runStartY = py;
			}
			lastX = px;
			lastY = py;
		}
		fillRun(g, runStartX, runStartY, lastX, lastY, color);
	}

	private static void fillRun(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1) + 1, Math.max(y0, y1) + 1, color);
	}
}
