package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.client.gui.Gfx;
import com.daniel.worldpainter.mixin.WorldAccessor;
import com.daniel.worldpainter.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.chunk.LegacyChunkCache;

/**
 * The real world seen through an {@link OrbitCamera}, for the painter's 3D view and the structure
 * editor. Beta has no spectator mode, but the painter pauses the game, so the player simply becomes
 * the camera: every frame they are put at the camera position (flying through walls, no HUD, no
 * view bobbing), and afterwards they go back exactly where they were. The game's chunk window
 * follows the camera, so the land around it loads and is drawn.
 */
public final class Camera3D {
	/** True while some view has the player as its camera (the hand and the in-a-block overlay are hidden). */
	private static volatile boolean viewing;

	private final Minecraft mc;
	private final Projection proj = new Projection();
	private OrbitCamera cam;
	/** The camera as the world was last drawn (input may have moved {@link #cam} since). */
	private OrbitCamera shown;
	private boolean active;
	/** The light table before "see in the dark", and the world it belongs to. */
	private float[] savedLight;
	private World lightWorld;
	private double sx, sy, sz;
	private float syaw, spitch;
	private boolean oldHideHud, oldBob, oldThird, oldNoClip;
	private int windowCx = Integer.MIN_VALUE, windowCz = Integer.MIN_VALUE;

	public Camera3D(Minecraft mc) {
		this.mc = mc;
	}

	public boolean active() {
		return active;
	}

	public static boolean viewing() {
		return viewing;
	}

	public OrbitCamera camera() {
		return cam;
	}

	public Projection projection() {
		return proj;
	}

	/** A camera looking at the ground at (x, z) from above at an angle. */
	public OrbitCamera cameraAt(double x, double z) {
		OrbitCamera c = new OrbitCamera(x, groundY(x, z) + 0.5, z);
		c.yaw = 180;
		c.pitch = 40;
		c.distance = 40;
		return c;
	}

	/** Starts (or moves) the view. */
	public void enter(OrbitCamera c) {
		ClientPlayerEntity p = mc.player;
		if (p == null || mc.world == null) {
			return;
		}
		cam = c;
		shown = null;
		if (!active) {
			sx = p.x;
			sy = p.y;
			sz = p.z;
			syaw = p.yaw;
			spitch = p.pitch;
			oldHideHud = mc.options.hideHud;
			oldBob = mc.options.bobView;
			oldThird = mc.options.thirdPerson;
			oldNoClip = p.noClip;
			mc.options.hideHud = true;
			mc.options.bobView = false;
			mc.options.thirdPerson = false;
			p.noClip = true;
			active = true;
			viewing = true;
		}
		apply();
	}

	/** Ends the view: the player, the HUD and the camera settings go back to how they were. */
	public void exit() {
		if (!active) {
			return;
		}
		active = false;
		viewing = false;
		setSeeInDark(false);
		ClientPlayerEntity p = mc.player;
		mc.options.hideHud = oldHideHud;
		mc.options.bobView = oldBob;
		mc.options.thirdPerson = oldThird;
		if (p != null) {
			p.noClip = oldNoClip;
			place(p, sx, sy, sz, syaw, spitch);
			recenter(p.x, p.z, true);
		}
	}

	/**
	 * Every frame, before drawing on top of the world: outlines and picking use the camera the world
	 * was just drawn with, then the player goes to where the camera is now (drawn next frame).
	 */
	public void frame(int guiWidth, int guiHeight) {
		if (!active || cam == null) {
			return;
		}
		proj.update(shown != null ? shown : cam, guiWidth, guiHeight, mc.displayWidth / (double) Math.max(1, mc.displayHeight));
		apply();
		shown = cam.copy();
	}

	public boolean seeInDark() {
		return savedLight != null;
	}

	/**
	 * Dungeons and caves are dark: while on, every place is drawn at least about half lit. Only the
	 * picture changes (Beta turns light levels into brightness with this table); it goes back to
	 * normal when the view ends.
	 */
	public void setSeeInDark(boolean on) {
		World w = mc.world;
		if (on == (savedLight != null)) {
			return;
		}
		if (on) {
			if (w == null || w.dimension == null || w.dimension.lightLevelToLuminance == null) {
				return;
			}
			float[] table = w.dimension.lightLevelToLuminance;
			savedLight = table.clone();
			lightWorld = w;
			for (int i = 0; i < table.length; i++) {
				table[i] = 0.45f + 0.55f * savedLight[i];
			}
		} else {
			if (lightWorld != null && lightWorld.dimension != null) {
				float[] table = lightWorld.dimension.lightLevelToLuminance;
				System.arraycopy(savedLight, 0, table, 0, Math.min(table.length, savedLight.length));
			}
			savedLight = null;
			lightWorld = null;
		}
		if (w != null && mc.worldRenderer != null) {
			try {
				// Chunks keep their light in their drawing: draw them again.
				mc.worldRenderer.reload();
			} catch (LinkageError e) {
				// Only newly drawn chunks change.
			}
		}
	}

	private void apply() {
		ClientPlayerEntity p = mc.player;
		if (p == null || cam == null) {
			return;
		}
		V3 e = cam.eye();
		place(p, e.x(), MathUtil.clamp(e.y(), -30.0, 400.0), e.z(), cam.yaw, cam.pitch);
		recenter(e.x(), e.z(), false);
	}

	/** Beta draws the player's eyes at their position (the player's y is at eye height). */
	private static void place(ClientPlayerEntity p, double x, double y, double z, float yaw, float pitch) {
		p.setPosition(x, y, z);
		p.prevX = p.lastTickX = x;
		p.prevY = p.lastTickY = y;
		p.prevZ = p.lastTickZ = z;
		p.yaw = p.prevYaw = yaw;
		p.pitch = p.prevPitch = pitch;
		p.velocityX = p.velocityY = p.velocityZ = 0;
	}

	/** Beta's older chunk ring only has chunks within 15 of a center chunk: keep it at the camera (the usual cache loads any chunk asked for). */
	private void recenter(double x, double z, boolean force) {
		World world = mc.world;
		if (world == null) {
			return;
		}
		int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
		if (!force && cx == windowCx && cz == windowCz) {
			return;
		}
		windowCx = cx;
		windowCz = cz;
		ChunkSource source = ((WorldAccessor) world).worldpainter$chunkSource();
		if (source instanceof LegacyChunkCache cache) {
			try {
				cache.setSpawnPoint(cx, cz);
			} catch (LinkageError e) {
				// The game moves it itself with the player.
			}
		}
	}

	/** The block under a screen point, or null. */
	public BlockRay.Hit pick(double gx, double gy, boolean hitFluids, boolean hitSmall) {
		if (!active || !proj.valid() || mc.world == null) {
			return null;
		}
		return BlockRay.cast(mc.world, proj.cameraPos(), proj.ray(gx, gy), 240, hitFluids, hitSmall);
	}

	/** Moves the camera to look at the ground at (x, z). */
	public void flyTo(int x, int z) {
		if (cam != null) {
			cam.focus(x + 0.5, groundY(x + 0.5, z + 0.5) + 0.5, z + 0.5);
		}
	}

	/** Highest block that is not air (plants and fluids count), or 64. */
	public double groundY(double x, double z) {
		World w = mc.world;
		if (w == null) {
			return 64;
		}
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
		for (int y = 127; y > 0; y--) {
			if (w.getBlockId(bx, y, bz) != 0) {
				return y + 1;
			}
		}
		return 64;
	}

	/** Roughly how many blocks one GUI pixel covers at a point (click tolerance of tools). */
	public double blocksPerPixelAt(double x, double y, double z, int guiHeight) {
		if (!proj.valid()) {
			return 1;
		}
		V3 c = proj.cameraPos();
		double d = Math.sqrt((x - c.x()) * (x - c.x()) + (y - c.y()) * (y - c.y()) + (z - c.z()) * (z - c.z()));
		return Math.max(0.1, d * 2 * Math.tan(Math.toRadians(35)) / Math.max(1, guiHeight));
	}

	// ------------------------------------------------------------------ outlines in the world

	public double[] project(double x, double y, double z) {
		return proj.project(x, y, z);
	}

	public void line(Gfx g, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		proj.line(g, x1, y1, z1, x2, y2, z2, color);
	}

	public void block(Gfx g, int x, int y, int z, int color) {
		proj.block(g, x, y, z, color);
	}

	public void box(Gfx g, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
		proj.box(g, x0, y0, z0, x1, y1, z1, color);
	}

	/** A circle lying on the ground (the 2D brush). */
	public void groundCircle(Gfx g, double cx, double cz, double r, int color) {
		int n = (int) MathUtil.clamp(r * 3, 24, 120);
		double px = cx + r, pz = cz, py = groundY(px, pz);
		for (int i = 1; i <= n; i++) {
			double a = i * Math.PI * 2 / n;
			double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r, y = groundY(x, z);
			proj.line(g, px, py, pz, x, y, z, color);
			px = x;
			py = y;
			pz = z;
		}
	}

	/** A rectangle lying on the ground (inclusive block bounds). */
	public void groundRect(Gfx g, int minX, int minZ, int maxX, int maxZ, int color) {
		groundEdge(g, minX, minZ, maxX + 1, minZ, color);
		groundEdge(g, maxX + 1, minZ, maxX + 1, maxZ + 1, color);
		groundEdge(g, maxX + 1, maxZ + 1, minX, maxZ + 1, color);
		groundEdge(g, minX, maxZ + 1, minX, minZ, color);
	}

	private void groundEdge(Gfx g, double x0, double z0, double x1, double z1, int color) {
		double len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
		int n = (int) MathUtil.clamp(len / 2, 1, 160);
		double px = x0, pz = z0, py = groundY(Math.min(x0, Math.max(x0, x1) - 0.5), Math.min(z0, Math.max(z0, z1) - 0.5));
		for (int i = 1; i <= n; i++) {
			double x = x0 + (x1 - x0) * i / n, z = z0 + (z1 - z0) * i / n;
			double y = groundY(Math.min(x, Math.max(x0, x1) - 0.5), Math.min(z, Math.max(z0, z1) - 0.5));
			proj.line(g, px, py, pz, x, y, z, color);
			px = x;
			py = y;
			pz = z;
		}
	}

	/** Three circles showing a ball (the 3D sculpt brush). */
	public void ball(Gfx g, double cx, double cy, double cz, double r, int color) {
		int n = (int) MathUtil.clamp(r * 4, 24, 96);
		for (int plane = 0; plane < 3; plane++) {
			double px = 0, py = 0, pz = 0;
			for (int i = 0; i <= n; i++) {
				double a = i * Math.PI * 2 / n;
				double u = Math.cos(a) * r, v = Math.sin(a) * r;
				double x = cx + (plane == 2 ? 0 : u);
				double y = cy + (plane == 0 ? 0 : v);
				double z = cz + (plane == 0 ? v : plane == 1 ? 0 : u);
				if (i > 0) {
					proj.line(g, px, py, pz, x, y, z, color);
				}
				px = x;
				py = y;
				pz = z;
			}
		}
	}

	/** A horizontal circle in the air (floating islands). */
	public void ring(Gfx g, double cx, double y, double cz, double r, int color) {
		int n = (int) MathUtil.clamp(r * 3, 24, 120);
		for (int i = 0; i < n; i++) {
			double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
			proj.line(g, cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r, cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r, color);
		}
	}
}
