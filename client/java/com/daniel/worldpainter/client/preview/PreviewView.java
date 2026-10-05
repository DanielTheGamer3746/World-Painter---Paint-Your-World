package com.daniel.worldpainter.client.preview;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.edit.PainterState;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The 3D preview inside the painter: keeps the scene up to date with the design, renders it on
 * background threads (a quick low resolution image while the view moves, a sharp one once it stops)
 * and shows the latest image as a texture. Also projects 3D points to the screen for overlays.
 */
public final class PreviewView implements AutoCloseable {
	private static final Identifier TEXTURE_ID = WorldPainter.id("painter_preview");
	/** Pixel budget of a sharp image. */
	private static final int MAX_PIXELS = 1024 * 576;
	private static final long SETTLE_NANOS = 160_000_000L;
	public static final int[] DISTANCES = {128, 256, 512};

	public final PreviewCamera cam = new PreviewCamera();
	private final PreviewScene scene = new PreviewScene();
	private final PreviewScene.Source source;
	private final PreviewRenderer renderer = new PreviewRenderer();
	private final ExecutorService coordinator = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "World Painter 3D preview");
		t.setDaemon(true);
		return t;
	});

	/** Where the view is on screen (GUI coordinates). */
	public int left, top, width = 1, height = 1;
	public double guiScale = 1;
	/** How far around the pivot the world is shown, in blocks. */
	public int viewDistance = 256;
	public boolean shadows = true;
	/** The pivot height follows the ground under it (off after focusing on a block). */
	public boolean autoHeight = true;

	private record Request(PreviewScene.Snapshot scene, PreviewCamera cam, int w, int h, boolean shadows) {
	}

	private record Frame(int[] pixels, int w, int h, PreviewCamera cam) {
	}

	private DynamicTexture texture;
	private int texW, texH;
	private Future<?> running;
	private Request queued;
	private final AtomicReference<Frame> finished = new AtomicReference<>();
	private final AtomicReference<int[]> spare = new AtomicReference<>();
	private Frame shown;
	private Request lastRequest;
	private long lastChange;
	private boolean sharp;
	private volatile Throwable failure;

	public PreviewView(PainterState state) {
		this.source = new PaintedSceneSource(state);
	}

	public void setBounds(int left, int top, int width, int height, double guiScale) {
		this.left = left;
		this.top = top;
		this.width = Math.max(1, width);
		this.height = Math.max(1, height);
		this.guiScale = guiScale;
	}

	public boolean contains(double gx, double gy) {
		return gx >= left && gx < left + width && gy >= top && gy < top + height;
	}

	/** Forget all built tiles (after "Clear All", or when what is shown changes completely). */
	public void invalidate() {
		scene.clear();
	}

	public PreviewScene.Snapshot snapshot() {
		return scene.snapshot();
	}

	/** True while parts of the scene are still being built (or nothing has been drawn yet). */
	public boolean busy() {
		return scene.pending() || shown == null;
	}

	/** Brings the scene up to date and starts a new render if something changed. Call once per frame. */
	public void update() {
		long now = System.nanoTime();
		boolean first = scene.snapshot() == null;
		scene.update(source, cam.pivotX, cam.pivotZ, viewDistance, first ? 40_000_000L : 5_000_000L);
		PreviewScene.Snapshot snap = scene.snapshot();
		if (autoHeight && snap != null) {
			int ground = snap.solidTop((int) Math.floor(cam.pivotX), (int) Math.floor(cam.pivotZ));
			if (ground != SceneTile.NONE && Math.abs(ground - cam.pivotY) > 0.01) {
				double d = ground - cam.pivotY;
				cam.pivotY = Math.abs(d) < 0.5 ? ground : cam.pivotY + d * 0.35;
				cam.update();
			}
		}

		// Size of the sharp image: up to one texture pixel per screen pixel.
		double pw = width * guiScale, ph = height * guiScale;
		double scale = Math.min(1.0, Math.sqrt(MAX_PIXELS / Math.max(1.0, pw * ph)));
		int sw = Math.max(1, (int) Math.round(pw * scale));
		int sh = Math.max(1, (int) Math.round(ph * scale));
		if (texture == null || sw != texW || sh != texH) {
			allocate(sw, sh);
		}

		Request last = lastRequest;
		boolean changed = last == null || last.scene != snap || !cam.sameView(last.cam) || last.shadows != shadows
				|| (last.w != sw && last.w != Math.max(1, sw / 2)) || (last.h != sh && last.h != Math.max(1, sh / 2));
		if (changed) {
			lastChange = now;
			sharp = false;
			request(new Request(snap, cam.copy(), Math.max(1, sw / 2), Math.max(1, sh / 2), shadows));
		} else if (!sharp && now - lastChange > SETTLE_NANOS) {
			sharp = true;
			request(new Request(snap, cam.copy(), sw, sh, shadows));
		}
		pump();
		upload();
	}

	private void request(Request r) {
		lastRequest = r;
		if (running != null && !running.isDone()) {
			queued = r;
			return;
		}
		start(r);
	}

	private void pump() {
		if (queued != null && (running == null || running.isDone())) {
			Request r = queued;
			queued = null;
			start(r);
		}
		Throwable t = failure;
		if (t != null) {
			failure = null;
			WorldPainter.LOGGER.error("3D preview failed to render", t);
		}
	}

	private void start(Request r) {
		running = coordinator.submit(() -> {
			try {
				int n = r.w * r.h;
				int[] px = spare.getAndSet(null);
				if (px == null || px.length < n) {
					px = new int[n];
				}
				renderer.render(r.scene, r.cam, r.w, r.h, px, r.shadows);
				Frame old = finished.getAndSet(new Frame(px, r.w, r.h, r.cam));
				if (old != null) {
					spare.compareAndSet(null, old.pixels);
				}
			} catch (Throwable t) {
				failure = t;
			}
		});
	}

	private void allocate(int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		if (texture != null) {
			mc.getTextureManager().release(TEXTURE_ID);
		}
		texture = new DynamicTexture(() -> "World Painter 3D preview", w, h, true);
		mc.getTextureManager().register(TEXTURE_ID, texture);
		texW = w;
		texH = h;
		NativeImage img = texture.getPixels();
		if (img != null) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					img.setPixel(x, y, source.environment().skyHorizon());
				}
			}
			texture.upload();
		}
		shown = null;
	}

	/** Copies a finished image into the texture (scaled up if it was a quick low resolution one). */
	private void upload() {
		Frame f = finished.getAndSet(null);
		if (f == null || texture == null) {
			return;
		}
		NativeImage img = texture.getPixels();
		if (img == null) {
			return;
		}
		int[] px = f.pixels;
		for (int y = 0; y < texH; y++) {
			int row = (int) ((long) y * f.h / texH) * f.w;
			for (int x = 0; x < texW; x++) {
				img.setPixel(x, y, px[row + (int) ((long) x * f.w / texW)]);
			}
		}
		texture.upload();
		if (shown != null) {
			spare.compareAndSet(null, shown.pixels);
		}
		shown = f;
	}

	public void blit(GuiGraphicsExtractor g) {
		if (texture == null) {
			g.fill(left, top, left + width, top + height, source.environment().skyHorizon());
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, left, top, 0f, 0f, width, height, texW, texH, texW, texH);
	}

	/** The camera of the image on screen (overlays and picking must match what is shown). */
	public PreviewCamera shownCamera() {
		return shown != null ? shown.cam : cam;
	}

	/** The block under a screen point, looking through water. */
	public PreviewRenderer.Hit pick(double gx, double gy) {
		if (!contains(gx, gy)) {
			return null;
		}
		return renderer.pick(scene.snapshot(), shownCamera(), gx - left, gy - top, width, height);
	}

	// ------------------------------------------------------------------ overlays

	private final double[] pa = new double[3];
	private final double[] pb = new double[3];

	/** Screen position of a world point (GUI coordinates), or null if it is behind the camera. */
	public double[] project(double x, double y, double z) {
		double[] out = new double[3];
		if (!shownCamera().project(x, y, z, width, height, out)) {
			return null;
		}
		out[0] += left;
		out[1] += top;
		return out;
	}

	/** A world-space line, clipped at the camera and the view edges. */
	public void line(GuiGraphicsExtractor g, double ax, double ay, double az, double bx, double by, double bz, int color) {
		PreviewCamera c = shownCamera();
		double near = 0.06;
		double da = (ax - c.eyeX) * c.fx + (ay - c.eyeY) * c.fy + (az - c.eyeZ) * c.fz;
		double db = (bx - c.eyeX) * c.fx + (by - c.eyeY) * c.fy + (bz - c.eyeZ) * c.fz;
		if (da < near && db < near) {
			return;
		}
		if (da < near) {
			double t = (near - da) / (db - da);
			ax += (bx - ax) * t;
			ay += (by - ay) * t;
			az += (bz - az) * t;
		} else if (db < near) {
			double t = (near - db) / (da - db);
			bx += (ax - bx) * t;
			by += (ay - by) * t;
			bz += (az - bz) * t;
		}
		if (!c.project(ax, ay, az, width, height, pa) || !c.project(bx, by, bz, width, height, pb)) {
			return;
		}
		line2d(g, pa[0], pa[1], pb[0], pb[1], color);
	}

	/** A 2D line in view coordinates (0,0 = top left of the view), clipped to the view. */
	private void line2d(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int color) {
		double dx = x1 - x0, dy = y1 - y0;
		double t0 = 0, t1 = 1;
		double[] p = {-dx, dx, -dy, dy};
		double[] q = {x0, width - 1 - x0, y0, height - 1 - y0};
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
		if (steps > 4000) {
			return;
		}
		boolean horizontal = Math.abs(ex - sx) >= Math.abs(ey - sy);
		int runX = (int) Math.round(sx), runY = (int) Math.round(sy);
		int lastX = runX, lastY = runY;
		for (int i = 1; i <= steps; i++) {
			int px = (int) Math.round(sx + (ex - sx) * i / steps);
			int py = (int) Math.round(sy + (ey - sy) * i / steps);
			if (horizontal ? py != runY : px != runX) {
				fillRun(g, runX, runY, lastX, lastY, color);
				runX = px;
				runY = py;
			}
			lastX = px;
			lastY = py;
		}
		fillRun(g, runX, runY, lastX, lastY, color);
	}

	private void fillRun(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(left + Math.min(x0, x1), top + Math.min(y0, y1), left + Math.max(x0, x1) + 1, top + Math.max(y0, y1) + 1, color);
	}

	/** Outline of a box (min corner inclusive, max corner exclusive, in blocks). */
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

	public void block(GuiGraphicsExtractor g, int x, int y, int z, int color) {
		double e = 0.01;
		box(g, x - e, y - e, z - e, x + 1 + e, y + 1 + e, z + 1 + e, color);
	}

	/** Height (just above the ground) used to draw outlines that lie on the terrain. */
	public double groundY(double x, double z) {
		PreviewScene.Snapshot s = scene.snapshot();
		int t = s == null ? SceneTile.NONE : s.columnTop((int) Math.floor(x), (int) Math.floor(z));
		return t == SceneTile.NONE ? cam.pivotY : t + 0.05;
	}

	/** A circle lying on the terrain (the 2D brush in the 3D view). */
	public void groundCircle(GuiGraphicsExtractor g, double cx, double cz, double r, int color) {
		int n = (int) Math.clamp(r * 3, 24, 120);
		double px = cx + r, pz = cz, py = groundY(px, pz);
		for (int i = 1; i <= n; i++) {
			double a = i * Math.PI * 2 / n;
			double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r, y = groundY(x, z);
			line(g, px, py, pz, x, y, z, color);
			px = x;
			py = y;
			pz = z;
		}
	}

	/** A rectangle lying on the terrain (inclusive block bounds). */
	public void groundRect(GuiGraphicsExtractor g, int minX, int minZ, int maxX, int maxZ, int color) {
		groundEdge(g, minX, minZ, maxX + 1, minZ, color);
		groundEdge(g, maxX + 1, minZ, maxX + 1, maxZ + 1, color);
		groundEdge(g, maxX + 1, maxZ + 1, minX, maxZ + 1, color);
		groundEdge(g, minX, maxZ + 1, minX, minZ, color);
	}

	private void groundEdge(GuiGraphicsExtractor g, double x0, double z0, double x1, double z1, int color) {
		double len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
		int n = (int) Math.clamp(len / 2, 1, 160);
		double px = x0, pz = z0, py = groundY(x0 - (x1 < x0 ? 1 : 0), z0 - (z1 < z0 ? 1 : 0));
		for (int i = 1; i <= n; i++) {
			double x = x0 + (x1 - x0) * i / n, z = z0 + (z1 - z0) * i / n;
			double y = groundY(Math.min(x, Math.max(x0, x1) - 0.5), Math.min(z, Math.max(z0, z1) - 0.5));
			line(g, px, py, pz, x, y, z, color);
			px = x;
			py = y;
			pz = z;
		}
	}

	/** Three circles showing a ball (the 3D sculpt brush). */
	public void ball(GuiGraphicsExtractor g, double cx, double cy, double cz, double r, int color) {
		int n = (int) Math.clamp(r * 4, 24, 96);
		for (int plane = 0; plane < 3; plane++) {
			double px = 0, py = 0, pz = 0;
			for (int i = 0; i <= n; i++) {
				double a = i * Math.PI * 2 / n;
				double u = Math.cos(a) * r, v = Math.sin(a) * r;
				double x = cx + (plane == 2 ? 0 : u);
				double y = cy + (plane == 0 ? 0 : v);
				double z = cz + (plane == 0 ? v : plane == 1 ? 0 : u);
				if (i > 0) {
					line(g, px, py, pz, x, y, z, color);
				}
				px = x;
				py = y;
				pz = z;
			}
		}
	}

	/** A horizontal circle in the air (for floating islands). */
	public void ring(GuiGraphicsExtractor g, double cx, double y, double cz, double r, int color) {
		int n = (int) Math.clamp(r * 3, 24, 120);
		for (int i = 0; i < n; i++) {
			double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
			line(g, cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r, cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r, color);
		}
	}

	@Override
	public void close() {
		coordinator.shutdownNow();
		renderer.close();
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
			texture = null;
		}
	}
}
