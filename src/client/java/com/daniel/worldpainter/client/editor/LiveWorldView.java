package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.editor.EditorOps;
import com.daniel.worldpainter.editor.EditorSessions;
import com.daniel.worldpainter.editor.SculptOps;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The painter's 3D view: the real world, seen through the same kind of camera as the 3D structure
 * editor. While it is open the player flies as a spectator at the camera position (their game mode
 * and position come back when it closes), Minecraft draws the world behind the painter's panels, and
 * mouse rays pick real blocks.
 */
public final class LiveWorldView {
	private final ResourceKey<Level> dimension;
	private final Projection proj = new Projection();
	private OrbitCamera cam;
	private boolean entering;
	private boolean wanted;
	private CameraType oldCameraType;
	private boolean hidHud;

	public LiveWorldView(ResourceKey<Level> dimension) {
		this.dimension = dimension;
	}

	/** The camera, or null while the view is not (yet) open. */
	public OrbitCamera camera() {
		return cam;
	}

	public boolean open() {
		return cam != null;
	}

	public boolean opening() {
		return entering;
	}

	/**
	 * Opens the view looking at (x, z), or with {@code from} as the camera if given. The player turns
	 * into a spectator on the server first; the view appears once that is done.
	 */
	public void enter(Minecraft mc, double x, double z, OrbitCamera from) {
		wanted = true;
		if (cam != null || entering) {
			return;
		}
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		entering = true;
		UUID playerId = mc.player.getUUID();
		server.submit(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (level == null || player == null) {
				return null;
			}
			OrbitCamera c = from;
			if (c == null || Double.isNaN(c.pivotY)) {
				int bx = (int) Math.floor(from != null ? from.pivotX : x), bz = (int) Math.floor(from != null ? from.pivotZ : z);
				int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
				OrbitCamera made = new OrbitCamera(bx + 0.5, y + 0.5, bz + 0.5);
				if (from != null) {
					made.yaw = from.yaw;
					made.pitch = from.pitch;
					made.distance = from.distance;
				} else {
					made.yaw = 180;
					made.pitch = 40;
					made.distance = 48;
				}
				c = made;
			}
			EditorSessions.begin(server, player);
			Vec3 eye = c.eye();
			// Moving the player there makes the server send the chunks around the camera.
			player.teleportTo(eye.x, eye.y - player.getEyeHeight(), eye.z);
			return c;
		}).thenAcceptAsync(c -> {
			entering = false;
			if (c == null) {
				return;
			}
			if (!wanted) {
				// Closed again before it finished opening.
				endSession(mc);
				return;
			}
			cam = c;
			oldCameraType = mc.options.getCameraType();
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			if (!mc.gui.hud.isHidden()) {
				mc.gui.hud.toggle();
				hidHud = true;
			}
		}, mc).exceptionally(e -> {
			entering = false;
			WorldPainter.LOGGER.error("Could not open the 3D view", e);
			return null;
		});
	}

	/** Closes the view: game mode, position, camera and HUD go back to how they were. */
	public void exit(Minecraft mc) {
		wanted = false;
		if (cam == null) {
			return;
		}
		cam = null;
		if (oldCameraType != null) {
			mc.options.setCameraType(oldCameraType);
		}
		if (hidHud && mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
		}
		hidHud = false;
		endSession(mc);
	}

	private void endSession(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) {
			return;
		}
		UUID playerId = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player != null) {
				EditorSessions.end(server, player, true);
			}
		});
	}

	/** Every frame: puts the player at the camera and updates the projection used for picking and outlines. */
	public void frame(Minecraft mc, int guiWidth, int guiHeight) {
		if (cam == null) {
			return;
		}
		if (mc.player != null) {
			cam.apply(mc.player);
		}
		proj.update(mc.gameRenderer.mainCamera(), guiWidth, guiHeight);
	}

	public void tick(Minecraft mc) {
		if (cam != null && mc.player != null) {
			cam.apply(mc.player);
		}
	}

	/** The block under a screen point (looking through water), or null. */
	public BlockRay.Hit pick(Minecraft mc, double gx, double gy) {
		if (cam == null || !proj.valid() || mc.level == null) {
			return null;
		}
		return BlockRay.cast(mc.level, proj.cameraPos(), proj.ray(gx, gy), 400, false);
	}

	/** Moves the camera to look at (x, z) on the ground there (the server finds the height). */
	public void flyTo(Minecraft mc, int x, int z) {
		onServer(mc, level -> level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), y -> {
			if (cam != null) {
				cam.focus(x + 0.5, y + 0.5, z + 0.5);
			}
		});
	}

	/** Changes sculpted blocks in the world; {@code done} gets the changes (for undo). */
	public void sculpt(Minecraft mc, long[] positions, int count, boolean add, Consumer<List<EditorOps.BlockChange>> done) {
		onServer(mc, level -> SculptOps.apply(level, positions, count, add), done);
	}

	/** Undoes ({@code toBefore}) or redoes block changes made by sculpting. */
	public void revert(Minecraft mc, List<EditorOps.BlockChange> changes, boolean toBefore) {
		onServer(mc, level -> {
			SculptOps.revert(level, changes, toBefore);
			return true;
		}, ok -> {
		});
	}

	private <T> void onServer(Minecraft mc, Function<ServerLevel, T> work, Consumer<T> then) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.submit(() -> {
			ServerLevel level = server.getLevel(dimension);
			return level == null ? null : work.apply(level);
		}).thenAcceptAsync(result -> {
			if (result != null) {
				then.accept(result);
			}
		}, mc).exceptionally(e -> {
			WorldPainter.LOGGER.error("3D view action failed", e);
			return null;
		});
	}

	// ------------------------------------------------------------------ outlines in the world

	private static double groundY(Minecraft mc, double x, double z) {
		if (mc.level == null) {
			return 64;
		}
		return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)) + 0.05;
	}

	/** Screen position of a world point, or null if it is behind the camera. */
	public double[] project(double x, double y, double z) {
		return cam == null || !proj.valid() ? null : proj.project(x, y, z);
	}

	public void line(GuiGraphicsExtractor g, double x1, double y1, double z1, double x2, double y2, double z2, int color) {
		proj.line(g, x1, y1, z1, x2, y2, z2, color);
	}

	public void block(GuiGraphicsExtractor g, int x, int y, int z, int color) {
		proj.block(g, x, y, z, color);
	}

	/** A circle lying on the ground (the 2D brush). */
	public void groundCircle(GuiGraphicsExtractor g, Minecraft mc, double cx, double cz, double r, int color) {
		int n = (int) Math.clamp(r * 3, 24, 120);
		double px = cx + r, pz = cz, py = groundY(mc, px, pz);
		for (int i = 1; i <= n; i++) {
			double a = i * Math.PI * 2 / n;
			double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r, y = groundY(mc, x, z);
			proj.line(g, px, py, pz, x, y, z, color);
			px = x;
			py = y;
			pz = z;
		}
	}

	/** A rectangle lying on the ground (inclusive block bounds). */
	public void groundRect(GuiGraphicsExtractor g, Minecraft mc, int minX, int minZ, int maxX, int maxZ, int color) {
		groundEdge(g, mc, minX, minZ, maxX + 1, minZ, color);
		groundEdge(g, mc, maxX + 1, minZ, maxX + 1, maxZ + 1, color);
		groundEdge(g, mc, maxX + 1, maxZ + 1, minX, maxZ + 1, color);
		groundEdge(g, mc, minX, maxZ + 1, minX, minZ, color);
	}

	private void groundEdge(GuiGraphicsExtractor g, Minecraft mc, double x0, double z0, double x1, double z1, int color) {
		double len = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
		int n = (int) Math.clamp(len / 2, 1, 160);
		double px = x0, pz = z0, py = groundY(mc, Math.min(x0, Math.max(x0, x1) - 0.5), Math.min(z0, Math.max(z0, z1) - 0.5));
		for (int i = 1; i <= n; i++) {
			double x = x0 + (x1 - x0) * i / n, z = z0 + (z1 - z0) * i / n;
			double y = groundY(mc, Math.min(x, Math.max(x0, x1) - 0.5), Math.min(z, Math.max(z0, z1) - 0.5));
			proj.line(g, px, py, pz, x, y, z, color);
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
					proj.line(g, px, py, pz, x, y, z, color);
				}
				px = x;
				py = y;
				pz = z;
			}
		}
	}

	/** A horizontal circle in the air (floating islands). */
	public void ring(GuiGraphicsExtractor g, double cx, double y, double cz, double r, int color) {
		int n = (int) Math.clamp(r * 3, 24, 120);
		for (int i = 0; i < n; i++) {
			double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
			proj.line(g, cx + Math.cos(a0) * r, y, cz + Math.sin(a0) * r, cx + Math.cos(a1) * r, y, cz + Math.sin(a1) * r, color);
		}
	}

	/** Roughly how many blocks one GUI pixel covers at a point (click tolerance of tools). */
	public double blocksPerPixelAt(double x, double y, double z, int guiHeight) {
		if (cam == null || !proj.valid()) {
			return 1;
		}
		Vec3 c = proj.cameraPos();
		double d = Math.sqrt((x - c.x) * (x - c.x) + (y - c.y) * (y - c.y) + (z - c.z) * (z - c.z));
		// Minecraft's default 70 degree field of view.
		return Math.max(0.1, d * 2 * Math.tan(Math.toRadians(35)) / Math.max(1, guiHeight));
	}
}
