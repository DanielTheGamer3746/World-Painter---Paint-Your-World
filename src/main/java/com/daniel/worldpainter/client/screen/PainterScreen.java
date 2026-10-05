package com.daniel.worldpainter.client.screen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.DraftSession;
import com.daniel.worldpainter.client.Icons;
import com.daniel.worldpainter.client.PainterTarget;
import com.daniel.worldpainter.client.Settings;
import com.daniel.worldpainter.client.WorldPainterClient;
import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.edit.VolumeOps;
import com.daniel.worldpainter.client.existing.ExistingStructure;
import com.daniel.worldpainter.client.editor.BlockRay;
import com.daniel.worldpainter.client.editor.Camera3D;
import com.daniel.worldpainter.client.editor.OrbitCamera;
import com.daniel.worldpainter.client.existing.ExistingWorldLayer;
import com.daniel.worldpainter.client.existing.LoadedChunks;
import com.daniel.worldpainter.client.existing.NaturalBiomes;
import com.daniel.worldpainter.client.gui.Gfx;
import com.daniel.worldpainter.client.gui.TextBox;
import com.daniel.worldpainter.client.map.BiomeColors;
import com.daniel.worldpainter.client.map.MapColors;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapRenderer;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.map.StructureInfo;
import com.daniel.worldpainter.client.map.SurfaceColors;
import com.daniel.worldpainter.client.templates.ProceduralTemplates;
import com.daniel.worldpainter.client.templates.Template;
import com.daniel.worldpainter.client.templates.TemplateData;
import com.daniel.worldpainter.client.templates.TemplateLibrary;
import com.daniel.worldpainter.client.tools.Brush;
import com.daniel.worldpainter.client.tools.HeightTool;
import com.daniel.worldpainter.client.tools.Hit3D;
import com.daniel.worldpainter.client.tools.Sculpt3DTool;
import com.daniel.worldpainter.client.tools.SmoothEdgesTool;
import com.daniel.worldpainter.client.tools.Tool;
import com.daniel.worldpainter.client.tools.Tool3D;
import com.daniel.worldpainter.client.tools.Tools;
import com.daniel.worldpainter.data.BetaBiomes;
import com.daniel.worldpainter.data.DirtyChunks;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.data.StructurePlan;
import com.daniel.worldpainter.gen.PaintBindings;
import com.daniel.worldpainter.gen.StructureControl;
import com.daniel.worldpainter.live.LiveRegen;
import com.daniel.worldpainter.util.MathUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The world painter for Beta 1.7.3. Every map pixel is one block; the map can show the whole
 * 60,000,000 block world. In the world you are playing, the painter can also show the real world in
 * 3D (F5) and be used in it: every tool works on the block under the mouse.
 */
public final class PainterScreen extends Screen {
	private static final int TOP = 24;
	private static final int TOOLBAR_W = 28;
	private static final int PANEL_W = 176;
	private static final int STATUS_H = 13;
	private static final int ROW_H = 11;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A8;
	private static final int DIM = 0xFF70707A;
	private static final int ACCENT = 0xFF3E6FB0;
	private static final int MOUSE_LEFT = 0, MOUSE_RIGHT = 1, MOUSE_MIDDLE = 2;
	/** Time per frame for live regeneration and for reading loaded chunks onto the map. */
	private static final long LIVE_BUDGET = 14_000_000L, SCAN_BUDGET = 3_000_000L;

	/** What the main area shows: the 2D map, or the real world in 3D. */
	private enum Layout {
		MAP("2D map"), WORLD("3D world");

		final String label;

		Layout(String label) {
			this.label = label;
		}
	}

	/** Remembered while the game runs, so the painter opens the way it was left. */
	private static Layout lastLayout = Layout.MAP;
	private static OrbitCamera lastCamera;

	private final PainterTarget target;
	private final PainterState state;
	private final MapView view = new MapView();
	private final MapRenderer renderer = new MapRenderer();
	private final Hits hits = new Hits();
	private final Minecraft mc;
	private final Gfx g;
	/** The natural biomes of the world (from its seed), or null. */
	private final NaturalBiomes natural;

	/** The 3D view (the real world); null where it cannot be shown (world not open, not in the Overworld). */
	private final Camera3D world3d;
	private Layout layout = Layout.MAP;
	private final MapView miniView = new MapView();
	private boolean showMiniMap = true;
	private boolean orbiting3d, panning3d, painting3d;
	private Hit3D hover3d;
	private Hit3D lastPaintHit;
	/** Reads the running world's loaded chunks onto the map; null for saved worlds and drafts. */
	private final LoadedChunks loaded;
	/** True while the structure editor opened from here is shown (the painter comes back afterwards). */
	private boolean suspended;
	private final List<String> allBiomes;
	private final List<String> allStructures;
	private List<Template> templates;

	private final TextBox biomeSearch;
	private final TextBox blockSearch;
	private final TextBox templateName;
	private final TextBox structureSearch;
	private final List<TextBox> boxes = new ArrayList<>();
	private String structureQuery = "";
	private String biomeQuery = "";
	private String blockQuery = "";
	private String templateNameValue = "My template";
	private int topButtonsLeft;

	private int biomeScroll, blockScroll, templateScroll, structureScroll;
	private List<String> blockResults = List.of();
	private String blockResultsQuery;

	private boolean painting;
	private boolean panning;
	private double mouseX, mouseY;
	private final Set<Integer> heldKeys = new HashSet<>();
	private long lastFrameNanos;
	private long ticks;
	private boolean showHelp;
	/** The settings panel (gear button in the top bar). */
	private boolean showSettings;
	private int settingsX, settingsY, settingsW, settingsH;
	/** Live changes: when the design last changed, to save it a moment after you stop. */
	private int seenChangeCount = -1;
	private long lastChangeMillis;
	private long discardArmedUntil;
	private long clearArmedUntil;
	private long deleteTemplateArmedUntil;
	private boolean discarded;
	private boolean cleanedUp;

	public PainterScreen(PainterTarget target) {
		this.target = target;
		this.mc = WorldPainterClient.mc();
		this.g = new Gfx(mc);
		PaintWorld world = PaintWorld.open(target.paintDir(), 4096);
		Long seed = null;
		if (target.kind() == PainterTarget.Kind.RUNNING_WORLD && mc.world != null) {
			seed = mc.world.getSeed();
		} else if (target.worldRoot() != null) {
			seed = NaturalBiomes.seedOf(target.worldRoot());
		}
		ExistingWorldLayer existing = null;
		if (target.worldRoot() != null) {
			existing = new ExistingWorldLayer(target.worldRoot().resolve("region"), seed);
		}
		this.natural = seed == null ? null : new NaturalBiomes(seed);
		this.state = new PainterState(target, world, existing);
		boolean runningHere = target.kind() == PainterTarget.Kind.RUNNING_WORLD && mc.world != null && mc.player != null
				&& mc.world.dimension != null && mc.world.dimension.id == 0;
		this.world3d = runningHere ? new Camera3D(mc) : null;
		if (runningHere && existing != null) {
			this.loaded = new LoadedChunks(existing, (x, z) -> {
				String b = state.paintedBiome(x, z);
				return b != null ? b : naturalBiome(x, z);
			});
			LiveRegen.setListener(this.loaded::changed);
		} else {
			this.loaded = null;
		}
		this.allBiomes = BiomeColors.vanillaIds();
		this.allStructures = StructureInfo.vanillaIds();
		this.templates = loadTemplates();
		this.state.template = ProceduralTemplates.ALL.get(0);

		if (playerHere()) {
			view.centerX = mc.player.x;
			view.centerZ = mc.player.z;
			view.zoom = 0;
		} else {
			view.zoom = existing != null && !existing.isEmpty() ? 1 : 2;
		}
		if (target.kind() == PainterTarget.Kind.NEW_WORLD && PaintWorld.hasPaint(target.paintDir())) {
			state.say("Loaded your earlier design for a new world. Use Clear All to start fresh.");
		} else if (target.kind() == PainterTarget.Kind.RUNNING_WORLD && !inOverworld()) {
			state.say("You are not in the Overworld: you are painting the Overworld, it changes when you go back.");
		}

		if (world3d != null && lastLayout == Layout.WORLD) {
			layout = Layout.WORLD;
		}
		miniView.zoom = 1;
		useLiveDesign();

		biomeSearch = box("Search biomes", biomeQuery, v -> {
			biomeQuery = v;
			biomeScroll = 0;
		});
		blockSearch = box("Search blocks", blockQuery, v -> {
			blockQuery = v;
			blockScroll = 0;
		});
		templateName = box("Template name", templateNameValue, v -> templateNameValue = v);
		templateName.setMaxLength(48);
		structureSearch = box("Search structures", structureQuery, v -> {
			structureQuery = v;
			structureScroll = 0;
		});
	}

	private TextBox box(String hint, String value, Consumer<String> responder) {
		TextBox b = new TextBox(hint, value, responder);
		boxes.add(b);
		return b;
	}

	private boolean inOverworld() {
		return mc.world != null && mc.world.dimension != null && mc.world.dimension.id == 0;
	}

	/** True if the player stands in the painted world (their position can be shown). */
	private boolean playerHere() {
		return target.kind() == PainterTarget.Kind.RUNNING_WORLD && mc.player != null && inOverworld();
	}

	private List<Template> loadTemplates() {
		List<Template> l = new ArrayList<>(ProceduralTemplates.ALL);
		l.addAll(TemplateLibrary.list(WorldPainterClient.gameDir()));
		return l;
	}

	// ------------------------------------------------------------------ setup

	@Override
	public void init() {
		buttons.clear();
		Keyboard.enableRepeatEvents(true);
		suspended = false;
		layoutView();
		if (layout == Layout.WORLD && world3d != null) {
			openWorldView();
		}
	}

	private String layoutLabel() {
		return "Layout: " + layout.label;
	}

	private void cycleLayout() {
		setLayout(layout == Layout.MAP ? Layout.WORLD : Layout.MAP);
	}

	private void setLayout(Layout l) {
		if (l == layout) {
			return;
		}
		if (l == Layout.WORLD && world3d == null) {
			state.say(target.kind() == PainterTarget.Kind.RUNNING_WORLD
					? "The 3D view shows the Overworld: go back to the Overworld to use it."
					: "The 3D view is the real world: open the world, press O in it and switch to 3D (F5).");
			return;
		}
		orbiting3d = panning3d = painting3d = false;
		if (l == Layout.MAP && world3d != null) {
			OrbitCamera c = world3d.camera();
			if (c != null) {
				view.centerX = c.pivotX;
				view.centerZ = c.pivotZ;
				lastCamera = c.copy();
			}
			world3d.exit();
		}
		layout = l;
		lastLayout = l;
		if (l == Layout.WORLD) {
			openWorldView();
		}
	}

	/** Opens the 3D view looking at the middle of the map (or where it looked last time nearby). */
	private void openWorldView() {
		OrbitCamera c = world3d.camera();
		if (c == null) {
			OrbitCamera last = lastCamera;
			if (last != null && Math.abs(last.pivotX - view.centerX) < 64 && Math.abs(last.pivotZ - view.centerZ) < 64) {
				c = last.copy();
			} else {
				c = world3d.cameraAt(view.centerX, view.centerZ);
			}
		}
		world3d.enter(c);
	}

	private boolean worldShown() {
		return layout == Layout.WORLD && world3d != null && world3d.active();
	}

	/** The part of the screen where the world is visible (between the side bars). */
	private boolean inWorldViewport(double x, double y) {
		return x >= TOOLBAR_W && x < width - PANEL_W && y >= TOP && y < height - STATUS_H;
	}

	private boolean overMiniMap(double x, double y) {
		return showMiniMap && miniView.contains(x, y);
	}

	private static Hit3D toHit(BlockRay.Hit h) {
		return h == null ? null : new Hit3D(h.x(), h.y(), h.z(), h.nx(), h.ny(), h.nz());
	}

	/**
	 * Live changes in the running world: generation uses this painter's design directly, so every
	 * stroke counts at once, and the chunks it touches regenerate right away.
	 */
	private boolean liveInWorld() {
		return live() && target.kind() == PainterTarget.Kind.RUNNING_WORLD && inOverworld();
	}

	private void useLiveDesign() {
		if (target.worldRoot() != null && target.kind() == PainterTarget.Kind.RUNNING_WORLD) {
			PaintBindings.useDesign(target.worldRoot(), liveInWorld() ? state.world : null);
		}
	}

	private static String label(String verb, String what) {
		return what == null ? "Nothing to " + verb.toLowerCase().replace("did", "do") : verb + ": " + what;
	}

	private String gridLabel() {
		return state.showGrid ? "Grid: On" : "Grid: Off";
	}

	private String existingLabel() {
		return state.showExisting ? "World: On" : "World: Off";
	}

	private void layoutView() {
		int areaW = Math.max(10, width - TOOLBAR_W - PANEL_W);
		int areaH = Math.max(10, height - TOP - STATUS_H);
		view.left = TOOLBAR_W;
		view.top = TOP;
		view.width = areaW;
		view.height = areaH;
		view.guiScale = g.scale();
		// The small map in a corner of the 3D view.
		int mw = Math.max(60, Math.min(200, areaW / 3));
		int mh = mw * 3 / 4;
		miniView.left = TOOLBAR_W + 6;
		miniView.top = height - STATUS_H - 6 - mh;
		miniView.width = mw;
		miniView.height = mh;
		miniView.guiScale = g.scale();
	}

	private static boolean shiftDown() {
		return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
	}

	private static boolean controlDown() {
		return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
				|| Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA);
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(int mx, int my, float delta) {
		g.begin();
		mouseX = mx;
		mouseY = my;
		if (state.pendingEdit3d != null) {
			int[] at = state.pendingEdit3d;
			state.pendingEdit3d = null;
			openStructureEditorAt(at[0], at[1]);
			if (mc.currentScreen != this) {
				return;
			}
		}
		applyKeyboardPan();
		layoutView();
		state.blocksPerGuiPixel = view.blocksPerGuiPixel();
		hits.clear();
		for (TextBox b : boxes) {
			b.visible = false;
		}

		liveFrame();
		hover3d = null;
		if (world3d != null && layout == Layout.MAP && world3d.active()) {
			// Back from the structure editor on the map.
			world3d.exit();
		}
		if (worldShown()) {
			world3d.frame(width, height);
			OrbitCamera c = world3d.camera();
			// The map follows the camera, so switching back shows the same place.
			view.centerX = c.pivotX;
			view.centerZ = c.pivotZ;
			LiveRegen.setFocus(c.pivotX, c.pivotZ);
			if (inWorldViewport(mx, my) && !overMiniMap(mx, my) && !orbiting3d && !panning3d && !showHelp && !showSettings) {
				hover3d = toHit(world3d.pick(mx, my, false, false));
			}
			g.enableScissor(TOOLBAR_W, TOP, width - PANEL_W, height - STATUS_H);
			drawWorldOverlays();
			g.disableScissor();
			if (showMiniMap) {
				drawMiniMap(mx, my);
			}
			drawWorldButtons();
		} else {
			if (liveInWorld()) {
				LiveRegen.setFocus(view.centerX, view.centerZ);
			}
			g.fill(0, 0, width, height, 0xFF111116);
			renderer.update(state, view);
			renderer.blit(g, view);
			g.enableScissor(view.left, view.top, view.left + view.width, view.top + view.height);
			drawMapOverlays(mx, my);
			g.disableScissor();
		}

		drawTopBar();
		drawToolbar();
		drawPanel();
		drawStatus();
		drawMessage();
		for (TextBox b : boxes) {
			b.draw(g, ticks);
		}
		if (showSettings) {
			drawSettings();
		}

		String tip = hits.tooltip(mx, my);
		if (tip != null && !showHelp) {
			g.tooltip(g.wrap(tip, 220), mx, my, width, height);
		}
		if (showHelp) {
			drawHelp();
		}
	}

	private void drawMapOverlays(int mx, int my) {
		double bpg = view.blocksPerGuiPixel();
		if (state.showGrid) {
			if (bpg <= 0.75) {
				gridLines(16, 0x28FFFFFF);
			}
			if (bpg <= 24) {
				gridLines(512, 0x50FFFFFF);
			} else if (bpg <= 800) {
				gridLines(16384, 0x40FFFFFF);
			} else {
				gridLines(1_000_000, 0x40FFFFFF);
			}
		}
		// World border (the painted world is 60,000,000 blocks wide)
		int lim = PaintWorld.WORLD_LIMIT;
		MapDraw.rect(g, view, -lim, -lim, lim - 1, lim - 1, 0xFFFF4040);
		MapDraw.cross(g, view, 0.5, 0.5, 0xFFFFD040);
		if (playerHere()) {
			MapDraw.cross(g, view, mc.player.x, mc.player.z, 0xFF40FF60);
		}
		if (state.showStructures || state.tool == Tools.STRUCTURES) {
			drawStructures();
		}
		if (state.selection != null) {
			int[] s = state.selection;
			MapDraw.rect(g, view, s[0], s[1], s[2], s[3], 0xFF00E5FF);
		}
		if (!panning) {
			state.tool.drawOverlay(state, g, view, mx, my);
		}
	}

	// ------------------------------------------------------------------ 3D view (the real world)

	/** Outlines drawn into the world: tool previews, the selection, placed structures, the block under the mouse. */
	private void drawWorldOverlays() {
		Camera3D w = world3d;
		OrbitCamera c = w.camera();
		StructurePlan plan = state.world.structures();
		if (state.showStructures || state.tool == Tools.STRUCTURES) {
			for (StructurePlan.Placed placed : plan.placed) {
				double dx = placed.x() - c.pivotX, dz = placed.z() - c.pivotZ;
				if (dx * dx + dz * dz > 200 * 200) {
					continue;
				}
				Integer built = StructureControl.builtY(placed.x(), placed.z());
				double y = built != null ? built + 0.5 : w.groundY(placed.x() + 0.5, placed.z() + 0.5) + 2;
				if (built != null) {
					// Placed dungeons are underground: show the room through the ground.
					w.box(g, placed.x() - 3, built - 1, placed.z() - 3, placed.x() + 4, built + 5, placed.z() + 4, 0xFF7FFF7F);
				}
				double[] at = w.project(placed.x() + 0.5, y, placed.z() + 0.5);
				if (at != null && inWorldViewport(at[0], at[1])) {
					int gx = (int) Math.round(at[0]), gy = (int) Math.round(at[1]);
					g.icon(Icons.STRUCTURE, gx - 8, gy - 16);
					g.text(StructureInfo.pretty(placed.structure()) + (built != null ? " (Y " + built + ")" : ""), gx + 10, gy - 12, 0xFF9CFF9C, true);
				}
			}
		}
		if (state.selection != null) {
			int[] s = state.selection;
			w.groundRect(g, s[0], s[1], s[2], s[3], 0xFF00E5FF);
		}
		int[] drag = state.tool.dragRect();
		if (drag != null) {
			w.groundRect(g, drag[0], drag[1], drag[2], drag[3], state.tool == Tools.STRUCTURES ? 0xFFFF5050 : 0xFFFFFF00);
		}
		Hit3D h = painting3d ? lastPaintHit : hover3d;
		if (h == null || orbiting3d || panning3d) {
			return;
		}
		boolean shift = shiftDown();
		if (state.tool instanceof Tool3D t3) {
			double[] t = t3.target(state, h, shift);
			if (state.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
				w.line(g, h.x() + 0.5, h.y() + 1, h.z() + 0.5, t[0], t[1], t[2], 0x80D0A0FF);
				w.ring(g, t[0], t[1], t[2], state.islandSize / 2.0, 0xFFD0A0FF);
			} else {
				int color = state.sculpt3dMode == PainterState.Sculpt3DMode.ADD ? (shift ? 0xFFFF8060 : 0xFF80FF80)
						: state.sculpt3dMode == PainterState.Sculpt3DMode.CARVE ? (shift ? 0xFF80FF80 : 0xFFFF8060) : 0xFFD0A0FF;
				w.ball(g, t[0], t[1], t[2], Sculpt3DTool.radius(state) + 0.5, color);
			}
		} else if (state.tool.usesBrush()) {
			Brush b = state.brush;
			if (b.square) {
				w.groundRect(g, h.x() - b.radius, h.z() - b.radius, h.x() + b.radius, h.z() + b.radius, 0xFFFFFFFF);
			} else {
				w.groundCircle(g, h.x() + 0.5, h.z() + 0.5, b.radius + 0.5, 0xFFFFFFFF);
			}
		} else if (state.tool == Tools.STAMP && state.template != null) {
			Template t = state.template;
			int tw = t.width(state.templateSize), td = t.depth(state.templateSize);
			if ((state.templateRotation & 1) == 1) {
				int tmp = tw;
				tw = td;
				td = tmp;
			}
			int x0 = h.x() - tw / 2, z0 = h.z() - td / 2;
			w.groundRect(g, x0, z0, x0 + tw - 1, z0 + td - 1, 0xFF7FFFFF);
		} else if (state.tool == Tools.STRUCTURES && state.structureMode == PainterState.StructureMode.PLACE) {
			com.daniel.worldpainter.data.BetaStructures.Entry e = com.daniel.worldpainter.data.BetaStructures.get(state.structure);
			int r = e != null && e.kind() == com.daniel.worldpainter.data.BetaStructures.Kind.LAKE ? 8 : 4;
			w.groundRect(g, h.x() - r, h.z() - r, h.x() + r, h.z() + r, 0xFF7FFF7F);
		}
		w.block(g, h.x(), h.y(), h.z(), 0xFFFFFF60);
	}

	/** A small map in the corner of the 3D view: where you are; click to go somewhere else. */
	private void drawMiniMap(int mx, int my) {
		OrbitCamera c = world3d.camera();
		miniView.centerX = c != null ? c.pivotX : view.centerX;
		miniView.centerZ = c != null ? c.pivotZ : view.centerZ;
		renderer.update(state, miniView);
		renderer.blit(g, miniView);
		g.enableScissor(miniView.left, miniView.top, miniView.left + miniView.width, miniView.top + miniView.height);
		if (state.selection != null) {
			int[] s = state.selection;
			MapDraw.rect(g, miniView, s[0], s[1], s[2], s[3], 0xFF00E5FF);
		}
		if (c != null) {
			double ex = c.eye().x(), ez = c.eye().z();
			int n = 16;
			for (int i = 0; i <= n; i += 2) {
				double wx = ex + (c.pivotX - ex) * i / n, wz = ez + (c.pivotZ - ez) * i / n;
				int gx = (int) Math.round(miniView.toGuiX(wx)), gy = (int) Math.round(miniView.toGuiY(wz));
				if (miniView.contains(gx, gy)) {
					g.fill(gx, gy, gx + 1, gy + 1, 0xFFFFD040);
				}
			}
			MapDraw.cross(g, miniView, ex, ez, 0xFFFFD040);
			MapDraw.cross(g, miniView, c.pivotX, c.pivotZ, 0xFFFFFFFF);
		}
		if (hover3d != null) {
			MapDraw.rect(g, miniView, hover3d.x(), hover3d.z(), hover3d.x(), hover3d.z(), 0xFFFFFFFF);
		}
		g.disableScissor();
		g.outline(miniView.left - 1, miniView.top - 1, miniView.width + 2, miniView.height + 2, ACCENT);
		hits.add(miniView.left, miniView.top, miniView.width, miniView.height, new Hits.Hit() {
			@Override
			public void click(double x, double y, int button) {
				int bx = (int) Math.floor(miniView.toWorldX(x)), bz = (int) Math.floor(miniView.toWorldZ(y));
				world3d.flyTo(bx, bz);
				state.say("Flying to X " + bx + "  Z " + bz);
			}

			@Override
			public boolean scroll(double amount) {
				miniView.zoom = MathUtil.clamp(miniView.zoom + (amount > 0 ? -1 : 1), MapView.MIN_ZOOM, 6);
				return true;
			}
		}, "Map: click to fly there, mouse wheel to zoom");
	}

	/** Small buttons in the corner of the 3D view, and what live changes are doing. */
	private void drawWorldButtons() {
		int x0 = TOOLBAR_W + 4, x = x0, y = TOP + 4;
		int right = width - PANEL_W - 4;
		String[][] items = {
				{"Top", "Look straight down"},
				{"Front", "Look north (like the map)"},
				{"Side", "Look west"},
				{"Map", "Show or hide the small map"},
				{"Edit 3D", "Open the structure editor here: change blocks, chest loot and spawners (K in game)"},
		};
		for (int i = 0; i < items.length; i++) {
			String text = items[i][0];
			int w = g.width(text) + 10;
			if (x + w > right && x > x0) {
				x = x0;
				y += 17;
			}
			int index = i;
			button(x, y, w, text, i == 3 && showMiniMap, () -> worldButton(index), items[i][1]);
			x += w + 2;
		}
		List<String> lines = new ArrayList<>();
		if (live()) {
			String st = LiveRegen.status();
			if (!st.isEmpty()) {
				lines.add(st);
			}
		} else {
			if (state.world.hasUnsavedChanges()) {
				lines.add("Save (Ctrl+S): land you fly to next generates with your design");
			}
			long pending = state.world.regen().countChunks();
			if (pending > 0) {
				lines.add("Save & Reload: " + pending + " painted chunk" + (pending == 1 ? "" : "s") + " already here regenerate");
			}
		}
		int ly = y + 21;
		for (String text : lines) {
			int tw = g.width(text);
			int tx = width - PANEL_W - tw - 8;
			g.fill(tx - 4, ly - 3, tx + tw + 4, ly + 11, 0xC0101018);
			g.text(text, tx, ly, 0xFFFFD040, false);
			ly += 14;
		}
	}

	private void worldButton(int index) {
		OrbitCamera c = world3d.camera();
		if (c == null) {
			return;
		}
		switch (index) {
			case 0:
				c.viewTop();
				break;
			case 1:
				c.viewFront();
				break;
			case 2:
				c.viewSide();
				break;
			case 3:
				showMiniMap = !showMiniMap;
				break;
			default:
				openStructureEditor(c.copy());
				break;
		}
	}

	/** Shows the structure editor; Esc there comes back here. */
	private void openStructureEditor(OrbitCamera start) {
		if (world3d == null) {
			state.say("The structure editor works in the world: open the world, press O and try again (or press K in the world).");
			return;
		}
		if (state.world.hasUnsavedChanges() && !live()) {
			save();
		}
		suspended = true;
		mc.setScreen(new StructureEditorScreen(this, world3d, start));
	}

	private void gridLines(int spacing, int color) {
		double wx0 = view.toWorldX(view.left), wx1 = view.toWorldX(view.left + view.width);
		double wz0 = view.toWorldZ(view.top), wz1 = view.toWorldZ(view.top + view.height);
		long startX = Math.floorDiv((long) Math.floor(wx0), spacing) * spacing;
		long startZ = Math.floorDiv((long) Math.floor(wz0), spacing) * spacing;
		int count = 0;
		for (long x = startX; x <= wx1 && count < 400; x += spacing, count++) {
			int sx = (int) Math.round(view.toGuiX(x));
			g.fill(sx, view.top, sx + 1, view.top + view.height, color);
		}
		count = 0;
		for (long z = startZ; z <= wz1 && count < 400; z += spacing, count++) {
			int sy = (int) Math.round(view.toGuiY(z));
			g.fill(view.left, sy, view.left + view.width, sy + 1, color);
		}
	}

	/** One button of the top bar, laid out from the right edge. */
	private int topButton(int right, String text, String tip, boolean selected, Runnable action) {
		int w = Math.max(20, g.width(text) + 10);
		int x = right - w;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= 3 && mouseY < 21;
		g.fill(x, 3, x + w, 21, selected ? ACCENT : hover ? 0xFF4A4A5A : 0xFF33333F);
		g.outline(x, 3, w, 18, 0xFF55556A);
		g.text(text, x + (w - g.width(text)) / 2, 8, WHITE, false);
		hits.add(x, 3, w, 18, (mx, my, b) -> action.run(), tip);
		return x - 2;
	}

	private void drawTopBar() {
		g.fill(0, 0, width, TOP - 2, 0xFF1B1B23);
		g.fill(0, TOP - 2, width, TOP - 1, 0xFF2C2C38);
		int x = width - 4;
		if (live()) {
			// Live changes: the design saves itself and the world follows it, so no Save buttons.
			x = topButton(x, "Done", "Close the painter (your design is saved automatically)", false, this::closeLive);
		} else {
			x = topButton(x, "Discard", "Close without saving (click twice)", false, this::discard);
			x = topButton(x, "Save & Close", "Save the design and close the painter", false, () -> {
				if (save()) {
					closeScreen();
				}
			});
			x = topButton(x, "Save", "Save the design (Ctrl+S)", false, this::save);
			if (target.kind() == PainterTarget.Kind.RUNNING_WORLD) {
				x = topButton(x, "Save & Reload", "Save, then leave and open the world again so painted chunks you already visited regenerate", false, this::saveAndReload);
			}
		}
		if (target.kind() == PainterTarget.Kind.NEW_WORLD) {
			x = topButton(x, "Clear All", "Delete the whole design (click twice)", false, this::clearAll);
		}
		x -= 6;
		boolean canRedo = state.session.canRedo(), canUndo = state.session.canUndo();
		x = topButton(x, canRedo ? "Redo" : "§7Redo", "Redo (Ctrl+Y)", false, () -> state.say(label("Redid", state.session.redo())));
		x = topButton(x, canUndo ? "Undo" : "§7Undo", "Undo (Ctrl+Z)", false, () -> state.say(label("Undid", state.session.undo())));
		x -= 6;
		if (state.existing != null) {
			x = topButton(x, existingLabel(), "Show or hide the existing world under the paint", false,
					() -> state.showExisting = !state.showExisting);
		}
		if (world3d != null) {
			x = topButton(x, layoutLabel(), "Switch between the 2D map and the real world in 3D (F5)", false, this::cycleLayout);
		}
		x = topButton(x, gridLabel(), "Show chunk / region grid lines", false, () -> state.showGrid = !state.showGrid);
		x = topButton(x, "View: " + state.viewMode.label, "What the map shows", false, () -> {
			PainterState.ViewMode[] modes = PainterState.ViewMode.values();
			state.viewMode = modes[(state.viewMode.ordinal() + 1) % modes.length];
		});
		x = topButton(x, "?", "Controls (F1)", showHelp, () -> showHelp = !showHelp);
		int gearRight = x;
		x = topButton(x, "    ", "Settings", showSettings, () -> showSettings = !showSettings);
		g.icon(Icons.GEAR, gearRight - 21, 4);
		topButtonsLeft = x;

		String title = "World Painter - " + target.displayName() + (state.world.hasUnsavedChanges() ? " *" : "");
		int room = topButtonsLeft - 10;
		if (room > 40) {
			g.text(fit(title, room), 6, 8, WHITE, false);
		}
	}

	private void drawToolbar() {
		g.fill(0, TOP, TOOLBAR_W - 2, height - STATUS_H, 0xFF18181E);
		int y = TOP + 3;
		for (Tool tool : Tools.ALL) {
			int x = 3;
			boolean sel = state.tool == tool;
			boolean hover = mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 20;
			g.fill(x, y, x + 22, y + 20, sel ? ACCENT : hover ? 0xFF3A3A48 : 0xFF2A2A34);
			if (sel) {
				g.outline(x, y, 22, 20, 0xFF9CC2FF);
			}
			g.icon(Tools.icon(tool), x + 3, y + 2);
			String tip = tool.name() + " (" + tool.hotkey() + ") - " + tool.help();
			hits.add(x, y, 22, 20, (mx, my, b) -> selectTool(tool), tip);
			y += 22;
		}
	}

	private void selectTool(Tool tool) {
		state.tool = tool;
		if (tool == Tools.SCULPT && state.layer != Layer.HEIGHT) {
			state.layer = Layer.HEIGHT;
		}
	}

	private void drawStatus() {
		int y = height - STATUS_H;
		g.fill(0, y, width, height, 0xFF1B1B23);
		String saved;
		if (live()) {
			String st = LiveRegen.status();
			saved = st.isEmpty() ? "Live changes" : st;
		} else {
			saved = state.world.hasUnsavedChanges() ? "Unsaved changes" : "Saved";
		}
		String right = (worldShown() ? "3D world" : view.scaleText()) + "   " + saved;
		int rw = g.width(right);
		g.text(right, width - rw - 6, y + 3, GRAY, false);
		if (hover3d != null) {
			g.text(fit("Y " + hover3d.y() + "  " + hoverInfo(hover3d.x(), hover3d.z()), width - rw - 20), 6, y + 3, WHITE, false);
		} else if (!worldShown() && view.contains(mouseX, mouseY)) {
			int bx = (int) Math.floor(view.toWorldX(mouseX));
			int bz = (int) Math.floor(view.toWorldZ(mouseY));
			g.text(fit(hoverInfo(bx, bz), width - rw - 20), 6, y + 3, WHITE, false);
		}
	}

	/** Status line text for a block column. */
	private String hoverInfo(int x, int z) {
		StringBuilder sb = new StringBuilder();
		sb.append("X ").append(x).append("  Z ").append(z);
		if (!PaintWorld.inWorld(x, z)) {
			return sb.append("  (outside the world)").toString();
		}
		String b = state.paintedBiome(x, z);
		if (b != null) {
			sb.append("  | ").append(BiomeColors.pretty(b));
		} else {
			String e = naturalBiome(x, z);
			sb.append("  | ").append(e != null ? BiomeColors.pretty(e) + " (world)" : "not painted");
		}
		short h = state.paintedHeight(x, z);
		if (h != PaintTile.NO_HEIGHT) {
			sb.append("  | Y ").append(h);
		} else {
			short eh = state.existingHeight(x, z);
			if (eh != ExistingWorldLayer.UNKNOWN) {
				sb.append("  | Y ").append(eh).append(" (world)");
			}
		}
		String su = state.paintedSurface(x, z);
		if (su != null) {
			sb.append("  | ").append(SurfaceColors.pretty(su));
		}
		short f = state.paintedFluid(x, z);
		if (f != FluidType.NONE) {
			FluidType t = FluidType.type(f);
			sb.append("  | ").append(t == FluidType.DRY ? "dry" : t.name().toLowerCase() + " to Y " + FluidType.level(f));
		}
		int[] edits = VolumeOps.columnCounts(state, x, z);
		if (edits != null) {
			sb.append("  | 3D: +").append(edits[0]).append(" / -").append(edits[1]);
		}
		return sb.toString();
	}

	/** The biome Beta generates at a block for this world's seed, or null if the seed is unknown. */
	private String naturalBiome(int x, int z) {
		if (natural == null) {
			return null;
		}
		String[] area = natural.area(x & ~15, z & ~15, new String[256]);
		return area[(x & 15) * 16 + (z & 15)];
	}

	private void drawMessage() {
		if (state.message == null || System.currentTimeMillis() > state.messageUntil) {
			return;
		}
		String msg = fit(state.message, view.width - 20);
		int w = g.width(msg) + 12;
		int x = view.left + (view.width - w) / 2;
		int y = view.top + view.height - 22;
		g.fill(x, y, x + w, y + 16, 0xE0101018);
		g.outline(x, y, w, 16, ACCENT);
		g.text(msg, x + 6, y + 4, WHITE, false);
	}

	private void drawHelp() {
		String[] lines = {
				"World Painter - controls",
				"",
				"Left mouse: use the tool      Shift + left mouse: erase / invert",
				"Right or middle mouse drag, or Space + drag, or WASD / arrows: move the map",
				"Mouse wheel: zoom (whole 60,000,000 block world at the far end)",
				"Ctrl + wheel or [ ]: brush size      1-4: layer (biome, height, surface, water/lava)",
				"B brush  E eraser  H sculpt  M smooth edges  G fill  R rectangle",
				"I pick  L select  T stamp template  Q rotate template  P structures",
				"C 3D sculpt (add ground, carve caves, restore, floating islands)",
				"F5: 2D map / 3D world (in the world you play). In 3D: right or middle drag orbits,",
				"Shift + drag pans, wheel zooms, WASD moves, F focuses on the block under the mouse.",
				"Edit 3D (or K in the world): the structure editor - blocks, chest loot, spawners.",
				"Ctrl+Z undo   Ctrl+Y redo   Ctrl+S save   Home: go to 0,0   F1: this help",
				"",
				"Each map pixel is one block. Unpainted land is generated normally by Minecraft.",
				"Beta 1.7.3 picks biomes from temperature and rainfall: a painted biome also sets",
				"those, so grass colors, snow, ice and hills follow it. Its structures are dungeons:",
				"place, remove or forbid them with the Structures tool (P); lakes can be placed too.",
				"With live changes (gear button, on by default) what you paint changes the world as you",
				"paint; without them painted changes show after Save & Reload (with a backup).",
				"",
				"Click anywhere to close"
		};
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, g.width(l));
		}
		w += 20;
		int h = lines.length * 11 + 16;
		int x = (width - w) / 2, y = (height - h) / 2;
		g.fill(x, y, x + w, y + h, 0xF0101018);
		g.outline(x, y, w, h, ACCENT);
		for (int i = 0; i < lines.length; i++) {
			g.text(lines[i], x + 10, y + 8 + i * 11, i == 0 ? 0xFFFFD040 : WHITE, false);
		}
	}

	// ------------------------------------------------------------------ side panel

	private void drawPanel() {
		int px = width - PANEL_W;
		int bottom = height - STATUS_H;
		g.fill(px, TOP, width, bottom, 0xFF18181E);
		g.fill(px, TOP, px + 1, bottom, 0xFF2C2C38);
		int x = px + 6;
		int w = PANEL_W - 12;
		int y = TOP + 4;

		g.text(state.tool.name(), x, y, 0xFFFFD040, false);
		y += 12;

		if (state.tool == Tools.STRUCTURES) {
			int listBottom = bottom - 4 - bottomSectionsHeight();
			int sy = structureSection(x, y, w, listBottom);
			if (state.selection != null) {
				selectionSection(x, Math.max(sy + 4, listBottom + 4), w);
			}
			return;
		}
		if (state.tool == Tools.SCULPT_3D) {
			int sy = sculpt3dSection(x, y, w);
			int by = Math.max(sy + 4, bottom - 4 - bottomSectionsHeight());
			if (state.sculpt3dMode != PainterState.Sculpt3DMode.ISLAND) {
				by = brushSection(x, by, w);
			}
			if (state.selection != null) {
				selectionSection(x, by, w);
			}
			return;
		}

		List<Layer> layers = state.layers();
		if (!layers.contains(state.layer)) {
			state.layer = layers.get(0);
		}
		int tabW = w / layers.size();
		for (int i = 0; i < layers.size(); i++) {
			Layer l = layers.get(i);
			String name;
			switch (l) {
				case BIOME: name = "Biome"; break;
				case HEIGHT: name = "Height"; break;
				case SURFACE: name = "Surface"; break;
				default: name = "Fluid"; break;
			}
			int tx = x + i * tabW;
			button(tx, y, tabW - 2, name, state.layer == l, () -> state.layer = l, l.displayName + " layer (" + (i + 1) + ")");
		}
		y += 18;

		// The bottom sections are laid out from the bottom up so lists can take the remaining space.
		int listBottom = bottom - 4 - bottomSectionsHeight();
		switch (state.layer) {
			case BIOME:
				y = biomeSection(x, y, w, listBottom);
				break;
			case SURFACE:
				y = surfaceSection(x, y, w, listBottom);
				break;
			case HEIGHT:
				y = heightSection(x, y, w);
				break;
			default:
				y = fluidSection(x, y, w);
				break;
		}

		int by = Math.max(y + 4, listBottom + 4);
		if (state.tool.usesBrush()) {
			by = brushSection(x, by, w);
		}
		if (state.tool == Tools.STAMP) {
			by = templateSection(x, by, w);
		}
		if (state.selection != null) {
			selectionSection(x, by, w);
		}
	}

	private int bottomSectionsHeight() {
		int h = 0;
		if (state.tool.usesBrush() && !(state.tool == Tools.SCULPT_3D && state.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND)) {
			h += 12 + 3 * 17 + 18;
		}
		if (state.tool == Tools.STAMP) {
			h += 12 + 6 * ROW_H + 4 + 17 + 12 + 18;
		}
		if (state.selection != null) {
			h += 12 + 18 * 2 + (target.existingWorld() ? 18 : 0) + 17 + 18;
		}
		return h;
	}

	private int sculpt3dSection(int x, int y, int w) {
		PainterState.Sculpt3DMode[] modes = PainterState.Sculpt3DMode.values();
		int bw = w / modes.length;
		for (int i = 0; i < modes.length; i++) {
			PainterState.Sculpt3DMode m = modes[i];
			String tip;
			switch (m) {
				case ADD: tip = "Add ground: grows up from the ground you click (hills, pillars, arches). Shift carves."; break;
				case CARVE: tip = "Carve: digs into the ground you click (pits, caves, tunnels). Hold to dig deeper. Shift adds."; break;
				case RESTORE: tip = "Remove 3D edits: back to what the 2D design makes there."; break;
				default: tip = "Click the ground to place a floating island above it."; break;
			}
			button(x + i * bw, y, bw - 2, m.label, state.sculpt3dMode == m, () -> state.sculpt3dMode = m, tip);
		}
		y += 18;
		if (state.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
			y = slider(x, y, w, "Island size: " + state.islandSize + " blocks", (state.islandSize - 8) / 120.0,
					f -> state.islandSize = (int) Math.round(8 + f * 120), step -> state.islandSize = MathUtil.clamp(state.islandSize + step * 4, 8, 128),
					"Width of the island");
			y = slider(x, y, w, "Height above ground: " + state.islandHeight, (state.islandHeight - 4) / 60.0,
					f -> state.islandHeight = (int) Math.round(4 + f * 60), step -> state.islandHeight = MathUtil.clamp(state.islandHeight + step * 2, 4, 64),
					"How high the island's top floats above the block you click (Beta worlds end at Y 127)");
		} else {
			g.text("Ball size: " + (Sculpt3DTool.radius(state) * 2 + 1) + " blocks", x, y + 1, GRAY, false);
			y += 12;
		}
		String text = "Works on the top of the ground you click. Hold to keep going. Ball up to "
				+ (Sculpt3DTool.MAX_RADIUS * 2 + 1) + " blocks (brush size).";
		for (String line : g.wrap(text, w)) {
			g.text(line, x, y, DIM, false);
			y += 10;
		}
		return y;
	}

	private int structureSection(int x, int y, int w, int listBottom) {
		PainterState.StructureMode[] modes = PainterState.StructureMode.values();
		int bw = w / modes.length;
		for (int i = 0; i < modes.length; i++) {
			PainterState.StructureMode m = modes[i];
			String label = m == PainterState.StructureMode.ZONE ? "Zones" : m == PainterState.StructureMode.EDIT ? "3D" : m.label;
			String tip;
			switch (m) {
				case PLACE: tip = "Click to place the chosen dungeon or lake (it generates there whatever the biome). Shift+click removes."; break;
				case REMOVE: tip = "Click a dungeon to remove it: placed ones, or ones already generated in the world. Click a red X to bring it back."; break;
				case EDIT: tip = "Click a spot to open the structure editor there: blocks, chest loot and spawners."; break;
				default: tip = "Drag a rectangle where no vanilla dungeon may generate. Shift+click a zone to delete it."; break;
			}
			button(x + i * bw, y, bw - 2, label, state.structureMode == m, () -> state.structureMode = m, tip);
		}
		y += 18;
		StructurePlan plan = state.world.structures();
		button(x, y, w, "Vanilla dungeons: " + (plan.vanillaStructures ? "On" : "Off"), !plan.vanillaStructures, () -> {
			state.session.begin("Toggle vanilla dungeons");
			state.session.editStructures(p -> p.vanillaStructures = !p.vanillaStructures);
			state.session.end();
			state.say(state.world.structures().vanillaStructures
					? "Vanilla dungeons generate normally again."
					: "No vanilla dungeons in new chunks - only the ones you place.");
		}, "Off: only dungeons you place generate (in chunks generated from now on)");
		y += 17;
		button(x, y, w, "Show structures on map: " + (state.showStructures ? "On" : "Off"), false,
				() -> state.showStructures = !state.showStructures, "Show placed, generated and removed dungeons, lakes and zones on the map");
		y += 17;
		g.text("Placed " + plan.placed.size() + "   Removed " + plan.removed.size() + "   Zones " + plan.zones.size(), x, y, GRAY, false);
		y += 12;

		if (state.structureMode != PainterState.StructureMode.PLACE) {
			String text;
			switch (state.structureMode) {
				case REMOVE:
					text = "Click a dungeon to remove it. Generated dungeons (their spawners) show when you zoom in. Their chunks regenerate without them.";
					break;
				case EDIT:
					text = target.kind() == PainterTarget.Kind.RUNNING_WORLD
							? "Click a dungeon (or any spot) to edit it in 3D: place and break blocks, change chest loot and spawners. In the world you can also press K."
							: "The structure editor works inside the world: open the world, press O and use this mode, or press K in the world.";
					break;
				default:
					text = "Drag to draw a zone where vanilla dungeons can not generate. Dungeons you place still generate inside. Shift+click a zone to delete it.";
					break;
			}
			for (String line : g.wrap(text, w)) {
				g.text(line, x, y, DIM, false);
				y += 10;
			}
			return y;
		}
		swatchLine(x, y, w, StructureInfo.color(state.structure), StructureInfo.pretty(state.structure));
		y += 13;
		for (String line : g.wrap(StructureInfo.about(state.structure), w)) {
			g.text(line, x, y, DIM, false);
			y += 10;
		}
		y += 2;
		structureSearch.place(x, y, w);
		y += 17;
		String q = structureQuery.trim().toLowerCase();
		List<String> items = new ArrayList<>();
		for (String id : allStructures) {
			if (q.isEmpty() || id.contains(q) || StructureInfo.pretty(id).toLowerCase().contains(q)) {
				items.add(id);
			}
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		structureScroll = list(x, y, w, h, items, structureScroll, v -> structureScroll = v,
				StructureInfo::pretty, StructureInfo::color, id -> id.equals(state.structure), id -> state.structure = id);
		return y + h;
	}

	// ---- structures on the map ----

	private void drawStructures() {
		StructurePlan plan = state.world.structures();
		double bpg = view.blocksPerGuiPixel();
		int wx0 = (int) Math.floor(view.toWorldX(view.left)), wx1 = (int) Math.ceil(view.toWorldX(view.left + view.width));
		int wz0 = (int) Math.floor(view.toWorldZ(view.top)), wz1 = (int) Math.ceil(view.toWorldZ(view.top + view.height));

		for (StructurePlan.Zone z : plan.zones) {
			if (z.maxX() < wx0 || z.minX() > wx1 || z.maxZ() < wz0 || z.minZ() > wz1) {
				continue;
			}
			fillWorldRect(z.minX(), z.minZ(), z.maxX(), z.maxZ(), 0x30FF3030);
			MapDraw.rect(g, view, z.minX(), z.minZ(), z.maxX(), z.maxZ(), 0xFFFF5050);
		}

		if (state.existing != null) {
			int drawn = 0;
			for (ExistingStructure es : state.existing.structuresNear(wx0, wz0, wx1, wz1)) {
				if (++drawn > 3000) {
					break;
				}
				int color = StructureInfo.color(es.id());
				if ((es.maxX() - es.minX()) / bpg >= 6) {
					MapDraw.rect(g, view, es.minX(), es.minZ(), es.maxX(), es.maxZ(), (color & 0x00FFFFFF) | 0xB0000000);
				}
				double cx = (es.minX() + es.maxX()) / 2.0 + 0.5, cz = (es.minZ() + es.maxZ()) / 2.0 + 0.5;
				dot(cx, cz, color);
				if (bpg <= 1.5) {
					label(cx, cz, StructureInfo.pretty(es.id()), color);
				}
			}
		}

		for (StructurePlan.Removed r : plan.removed) {
			double cx = r.chunkX() * 16 + 8, cz = r.chunkZ() * 16 + 8;
			if (cx >= wx0 - 16 && cx <= wx1 + 16 && cz >= wz0 - 16 && cz <= wz1 + 16) {
				redX(cx, cz);
			}
		}

		for (StructurePlan.Placed p : plan.placed) {
			double cx = p.x() + 0.5, cz = p.z() + 0.5;
			if (cx < wx0 - 16 || cx > wx1 + 16 || cz < wz0 - 16 || cz > wz1 + 16) {
				continue;
			}
			int gx = (int) Math.round(view.toGuiX(cx)), gy = (int) Math.round(view.toGuiY(cz));
			g.icon(Icons.STRUCTURE, gx - 8, gy - 8);
			if (bpg <= 4) {
				Integer built = StructureControl.builtY(p.x(), p.z());
				label(cx, cz, StructureInfo.pretty(p.structure()) + (built != null ? " (Y " + built + ")" : ""), 0xFF9CFF9C);
			}
		}
	}

	private void fillWorldRect(int minX, int minZ, int maxX, int maxZ, int color) {
		int x0 = (int) Math.max(view.left, Math.floor(view.toGuiX(minX)));
		int y0 = (int) Math.max(view.top, Math.floor(view.toGuiY(minZ)));
		int x1 = (int) Math.min(view.left + view.width, Math.ceil(view.toGuiX(maxX + 1.0)));
		int y1 = (int) Math.min(view.top + view.height, Math.ceil(view.toGuiY(maxZ + 1.0)));
		if (x1 > x0 && y1 > y0) {
			g.fill(x0, y0, x1, y1, color);
		}
	}

	private void dot(double wx, double wz, int color) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		g.fill(gx - 3, gy - 3, gx + 4, gy + 4, 0xFF101014);
		g.fill(gx - 2, gy - 2, gx + 3, gy + 3, color);
	}

	private void redX(double wx, double wz) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		for (int i = -4; i <= 4; i++) {
			g.fill(gx + i, gy + i, gx + i + 2, gy + i + 1, 0xFFFF3030);
			g.fill(gx + i, gy - i, gx + i + 2, gy - i + 1, 0xFFFF3030);
		}
	}

	private void label(double wx, double wz, String text, int color) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		g.text(text, gx + 10, gy - 4, color, true);
	}

	private int biomeSection(int x, int y, int w, int listBottom) {
		swatchLine(x, y, w, BiomeColors.color(state.biome), BiomeColors.pretty(state.biome));
		y += 13;
		BetaBiomes.Entry e = BetaBiomes.get(state.biome);
		if (e != null) {
			for (String line : g.wrap(e.about(), w)) {
				g.text(line, x, y, DIM, false);
				y += 10;
			}
			y += 2;
		}
		biomeSearch.place(x, y, w);
		y += 17;
		String q = biomeQuery.trim().toLowerCase();
		List<String> items = new ArrayList<>();
		for (String id : allBiomes) {
			if (q.isEmpty() || id.contains(q) || BiomeColors.pretty(id).toLowerCase().contains(q)) {
				items.add(id);
			}
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		biomeScroll = list(x, y, w, h, items, biomeScroll, v -> biomeScroll = v,
				BiomeColors::pretty, BiomeColors::color, id -> id.equals(state.biome), id -> {
					state.biome = id;
					if (state.tool == Tools.PICK || state.tool == Tools.SELECT) {
						state.tool = Tools.BRUSH;
					}
				});
		return y + h;
	}

	private int surfaceSection(int x, int y, int w, int listBottom) {
		swatchLine(x, y, w, SurfaceColors.color(state.surface), SurfaceColors.pretty(state.surface));
		y += 13;
		blockSearch.place(x, y, w);
		y += 17;
		if (!blockQuery.equals(blockResultsQuery)) {
			blockResultsQuery = blockQuery;
			blockResults = SurfaceColors.search(blockQuery, 300);
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		blockScroll = list(x, y, w, h, blockResults, blockScroll, v -> blockScroll = v, SurfaceColors::pretty,
				SurfaceColors::color, id -> id.equals(state.surface), id -> state.surface = id);
		return y + h;
	}

	private int heightSection(int x, int y, int w) {
		int range = PainterState.MAX_Y - PainterState.MIN_Y;
		y = slider(x, y, w, "Paint height: Y " + state.height, (state.height - PainterState.MIN_Y) / (double) range,
				f -> state.height = (int) Math.round(PainterState.MIN_Y + f * range), step -> state.height = MathUtil.clamp(state.height + step, PainterState.MIN_Y, PainterState.MAX_Y),
				"Height used by the Brush and Rectangle tools on the height layer (the sea is at Y 63, the world ends at Y 127)");
		y = slider(x, y, w, "Base height: Y " + state.baseHeight, (state.baseHeight - PainterState.MIN_Y) / (double) range,
				f -> state.baseHeight = (int) Math.round(PainterState.MIN_Y + f * range), step -> state.baseHeight = MathUtil.clamp(state.baseHeight + step, PainterState.MIN_Y, PainterState.MAX_Y),
				"Where sculpting starts on land that is not painted and not known from an existing world");
		g.text("Sculpt mode", x, y + 1, GRAY, false);
		y += 11;
		PainterState.HeightMode[] modes = PainterState.HeightMode.values();
		int bw = w / 3;
		for (int i = 0; i < modes.length; i++) {
			PainterState.HeightMode m = modes[i];
			button(x + (i % 3) * bw, y + (i / 3) * 17, bw - 2, m.label, state.heightMode == m, () -> {
				state.heightMode = m;
				state.tool = Tools.SCULPT;
			}, "Sculpt tool: " + m.label.toLowerCase());
		}
		y += 2 * 17 + 2;
		g.text("Below Y 63 unpainted water fills in", x, y, DIM, false);
		return y + 11;
	}

	private int fluidSection(int x, int y, int w) {
		FluidType[] types = FluidType.values();
		int bw = w / 3;
		for (int i = 0; i < types.length; i++) {
			FluidType t = types[i];
			String name = t == FluidType.WATER ? "Water" : t == FluidType.LAVA ? "Lava" : "Dry";
			button(x + i * bw, y, bw - 2, name, state.fluidType == t, () -> state.fluidType = t,
					t == FluidType.DRY ? "Removes water/lava above the ground (e.g. dry land below sea level)" : name + " up to the level below");
		}
		y += 18;
		int range = PainterState.MAX_Y - PainterState.MIN_Y;
		if (state.fluidType != FluidType.DRY) {
			y = slider(x, y, w, "Surface level: Y " + state.fluidLevel, (state.fluidLevel - PainterState.MIN_Y) / (double) range,
					f -> state.fluidLevel = (int) Math.round(PainterState.MIN_Y + f * range), step -> state.fluidLevel = MathUtil.clamp(state.fluidLevel + step, PainterState.MIN_Y, PainterState.MAX_Y),
					"The top water/lava block. The sea is at Y 63.");
		}
		g.text("Fills from the ground up to the level", x, y, DIM, false);
		return y + 11;
	}

	private int brushSection(int x, int y, int w) {
		Brush b = state.brush;
		g.text("Brush", x, y + 1, GRAY, false);
		button(x + w - 2 * 44, y - 1, 42, "Circle", !b.square, () -> b.square = false, "Round brush");
		button(x + w - 44, y - 1, 42, "Square", b.square, () -> b.square = true, "Square brush");
		y += 14;
		y = slider(x, y, w, "Size: " + (b.radius * 2 + 1) + " blocks", Math.log(b.radius + 1) / Math.log(Brush.MAX_RADIUS + 1),
				f -> b.radius = MathUtil.clamp(Math.round(Math.pow(Brush.MAX_RADIUS + 1, f)) - 1, 0, Brush.MAX_RADIUS),
				step -> b.radius = MathUtil.clamp(b.radius + step * Math.max(1, b.radius / 8), 0, Brush.MAX_RADIUS),
				"Brush diameter in blocks (Ctrl + mouse wheel or [ ])");
		y = slider(x, y, w, "Hardness: " + Math.round(b.hardness * 100) + "%", b.hardness,
				f -> b.hardness = (float) f, step -> b.hardness = MathUtil.clamp(b.hardness + step * 0.05f, 0f, 1f),
				"Soft brushes fade out towards the edge (height tools)");
		y = slider(x, y, w, "Strength: " + b.strength, (b.strength - 1) / 63.0,
				f -> b.strength = 1 + (int) Math.round(f * 63), step -> b.strength = MathUtil.clamp(b.strength + step, 1, 64),
				"How fast height tools work / how far smoothing looks");
		return y + 2;
	}

	private int templateSection(int x, int y, int w) {
		g.text("Templates (Q rotates: " + state.templateRotation * 90 + " deg)", x, y + 1, GRAY, false);
		y += 12;
		int h = 6 * ROW_H;
		templateScroll = list(x, y, w, h, templates, templateScroll, v -> templateScroll = v,
				t -> t.name() + (t.resizable() ? "" : " *"),
				t -> t.resizable() ? 0xFF6FA0D8 : 0xFFD8B06F,
				t -> t == state.template, t -> state.template = t);
		y += h + 4;
		Template t = state.template;
		if (t != null && t.resizable()) {
			y = slider(x, y, w, "Size: " + state.templateSize + " blocks",
					(state.templateSize - ProceduralTemplates.MIN_SIZE) / (double) (ProceduralTemplates.MAX_SIZE - ProceduralTemplates.MIN_SIZE),
					f -> state.templateSize = (int) Math.round(ProceduralTemplates.MIN_SIZE + f * (ProceduralTemplates.MAX_SIZE - ProceduralTemplates.MIN_SIZE)),
					step -> state.templateSize = MathUtil.clamp(state.templateSize + step * 32, ProceduralTemplates.MIN_SIZE, ProceduralTemplates.MAX_SIZE),
					"Width of the generated area");
		} else if (t != null) {
			button(x, y, w, "Delete this saved template", false, this::deleteTemplate, "Deletes the template file (click twice)");
			y += 17;
		}
		if (t != null) {
			g.text(fit(t.description(), w), x, y, DIM, false);
		}
		return y + 12 + 6;
	}

	private void selectionSection(int x, int y, int w) {
		int[] s = state.selection;
		g.text("Selection " + (s[2] - s[0] + 1) + " x " + (s[3] - s[1] + 1), x, y + 1, GRAY, false);
		y += 12;
		int bw = (w - 2) / 2;
		button(x, y, bw, "Fill", false, () -> {
			state.session.begin("Fill selection");
			Ops.fillRect(state.session, s[0], s[1], s[2], s[3], state.currentValue());
			state.session.end();
			state.say("Filled the selection");
		}, "Fill the selection with the chosen " + state.layer.displayName.toLowerCase());
		button(x + bw + 2, y, bw, "Erase", false, () -> {
			state.session.begin("Erase selection");
			Ops.clearRect(state.session, s[0], s[1], s[2], s[3], state.layer);
			state.session.end();
			state.say("Erased the " + state.layer.displayName.toLowerCase() + " layer in the selection");
		}, "Erase the chosen layer inside the selection");
		y += 18;
		int bw3 = (w - 4) / 3;
		button(x, y, bw3, "Smooth", false, () -> smoothSelection(s), "Smooth borders / heights inside the selection");
		button(x + bw3 + 2, y, bw3, "Clear 3D", false, () -> {
			state.session.begin("Clear 3D edits");
			VolumeOps.clearRect(state.session, s[0], s[1], s[2], s[3]);
			state.session.end();
			state.say("Removed the 3D edits in the selection");
		}, "Remove caves, overhangs and floating islands sculpted in 3D inside the selection");
		button(x + 2 * (bw3 + 2), y, w - 2 * (bw3 + 2), "Deselect", false, () -> state.selection = null, "Clear the selection");
		y += 18;
		if (target.existingWorld()) {
			button(x, y, w, "Regenerate these chunks", false, () -> {
				state.session.regenChunks(s[0] >> 4, s[1] >> 4, s[2] >> 4, s[3] >> 4);
				state.say(live() && target.kind() == PainterTarget.Kind.RUNNING_WORLD
						? "The selected chunks regenerate in a moment (live changes)."
						: "The selected chunks regenerate the next time the world loads (after you save).");
			}, "Generate the selected area again with the current design (a backup is kept when the world loads)");
			y += 18;
		}
		templateName.place(x, y, w);
		y += 17;
		button(x, y, w, "Save as template", false, () -> saveSelectionAsTemplate(s), "Save the painted content of the selection so you can stamp it anywhere");
	}

	private void smoothSelection(int[] s) {
		long area = (s[2] - s[0] + 1L) * (s[3] - s[1] + 1L);
		if (area > 16_000_000L) {
			state.say("Selection too large to smooth at once (max 4000 x 4000).");
			return;
		}
		int k = MathUtil.clamp(1 + state.brush.strength / 6, 1, 10);
		state.session.begin("Smooth selection");
		if (state.layer == Layer.HEIGHT) {
			HeightTool.smoothRect(state, s[0], s[1], s[2], s[3], k, 1f, (x, z) -> 1f);
		} else {
			SmoothEdgesTool.smoothCategorical(state, state.layer, s[0], s[1], s[2], s[3], k, (x, z) -> true);
		}
		state.session.end();
		state.say("Smoothed the selection");
	}

	private void saveSelectionAsTemplate(int[] s) {
		int w = s[2] - s[0] + 1, d = s[3] - s[1] + 1;
		if (w > TemplateData.MAX_SIDE || d > TemplateData.MAX_SIDE) {
			state.say("Templates can be at most " + TemplateData.MAX_SIDE + " x " + TemplateData.MAX_SIDE + " blocks.");
			return;
		}
		TemplateData data = TemplateData.capture(state, s[0], s[1], s[2], s[3]);
		if (data.isEmpty()) {
			state.say("Nothing is painted in the selection.");
			return;
		}
		try {
			String name = TemplateLibrary.sanitize(templateNameValue);
			TemplateLibrary.save(WorldPainterClient.gameDir(), name, data);
			templates = loadTemplates();
			for (Template t : templates) {
				if (t.name().equals(name)) {
					state.template = t;
				}
			}
			state.tool = Tools.STAMP;
			state.say("Saved template \"" + name + "\" - now stamp it anywhere");
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not save template", e);
			state.say("Could not save the template: " + e.getMessage());
		}
	}

	private void deleteTemplate() {
		long now = System.currentTimeMillis();
		if (now > deleteTemplateArmedUntil) {
			deleteTemplateArmedUntil = now + 3000;
			state.say("Click again to delete the template file");
			return;
		}
		try {
			TemplateLibrary.delete(state.template);
		} catch (IOException e) {
			state.say("Could not delete: " + e.getMessage());
			return;
		}
		templates = loadTemplates();
		state.template = ProceduralTemplates.ALL.get(0);
		state.say("Template deleted");
	}

	// ---- small custom widgets ----

	private void swatchLine(int x, int y, int w, int color, String text) {
		g.fill(x, y, x + 10, y + 10, color);
		g.outline(x, y, 10, 10, 0xFF000000);
		g.text(fit(text, w - 14), x + 14, y + 1, WHITE, false);
	}

	private void button(int x, int y, int w, String text, boolean selected, Runnable action, String tip) {
		if (w <= 0) {
			return;
		}
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 15;
		g.fill(x, y, x + w, y + 15, selected ? ACCENT : hover ? 0xFF3A3A48 : 0xFF2A2A34);
		String t = fit(text, w - 4);
		g.text(t, x + (w - g.width(t)) / 2, y + 4, WHITE, false);
		hits.add(x, y, w, 15, (mx, my, b) -> action.run(), tip);
	}

	/** Draws a slider and returns the y below it. */
	private int slider(int x, int y, int w, String text, double fraction, DoubleConsumer onSet, IntConsumer onStep, String tip) {
		double f = MathUtil.clamp(fraction, 0.0, 1.0);
		g.fill(x, y, x + w, y + 14, 0xFF26262F);
		g.fill(x, y, x + (int) Math.round(w * f), y + 14, 0xFF34507A);
		g.outline(x, y, w, 14, 0xFF3A3A48);
		g.text(fit(text, w - 6), x + 4, y + 3, WHITE, false);
		hits.add(x, y, w, 14, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				onSet.accept(MathUtil.clamp((mx - x) / w, 0.0, 1.0));
			}

			@Override
			public void drag(double mx, double my) {
				onSet.accept(MathUtil.clamp((mx - x) / w, 0.0, 1.0));
			}

			@Override
			public boolean scroll(double amount) {
				onStep.accept(amount > 0 ? 1 : -1);
				return true;
			}
		}, tip);
		return y + 17;
	}

	/** Draws a scrollable list and returns the (clamped) scroll position. */
	private <T> int list(int x, int y, int w, int h, List<T> items, int scroll, IntConsumer setScroll,
						 Function<T, String> label, ToIntFunction<T> color, Predicate<T> selected, Consumer<T> onPick) {
		int visible = Math.max(1, h / ROW_H);
		int maxScroll = Math.max(0, items.size() - visible);
		int sc = MathUtil.clamp(scroll, 0, maxScroll);
		g.fill(x, y, x + w, y + h, 0xFF141419);
		g.outline(x, y, w, h, 0xFF2C2C38);
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		for (int i = 0; i < visible + 1 && sc + i < items.size(); i++) {
			T item = items.get(sc + i);
			int ry = y + 1 + i * ROW_H;
			boolean sel = selected.test(item);
			if (sel) {
				g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0xFF2F4A73);
			} else if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H) {
				g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0xFF262633);
			}
			g.fill(x + 3, ry + 2, x + 10, ry + 9, color.applyAsInt(item));
			g.text(fit(label.apply(item), w - 18), x + 13, ry + 2, sel ? WHITE : 0xFFD0D0D8, false);
		}
		g.disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(8, h * visible / items.size());
			int barY = y + (int) ((h - barH) * (sc / (double) maxScroll));
			g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF5A5A6A);
		}
		hits.add(x, y, w, h, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				int idx = sc + (int) ((my - y - 1) / ROW_H);
				if (idx >= 0 && idx < items.size()) {
					onPick.accept(items.get(idx));
				}
			}

			@Override
			public boolean scroll(double amount) {
				setScroll.accept(MathUtil.clamp(sc - (amount > 0 ? 3 : -3), 0, maxScroll));
				return true;
			}
		});
		return sc;
	}

	private String fit(String s, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (g.width(s) <= maxWidth) {
			return s;
		}
		String ell = "...";
		int end = s.length();
		while (end > 0 && g.width(s.substring(0, end) + ell) > maxWidth) {
			end--;
		}
		return s.substring(0, end) + ell;
	}

	// ------------------------------------------------------------------ input

	private int blockX(double gx) {
		return (int) Math.floor(view.toWorldX(gx));
	}

	private int blockZ(double gy) {
		return (int) Math.floor(view.toWorldZ(gy));
	}

	private TextBox focusedBox() {
		for (TextBox b : boxes) {
			if (b.focused()) {
				return b;
			}
		}
		return null;
	}

	/** Beta's screens only see clicks; the painter reads the mouse itself (wheel, drags, releases). */
	@Override
	public void onMouseEvent() {
		double mx = Mouse.getEventX() * width / (double) minecraft.displayWidth;
		double my = height - Mouse.getEventY() * height / (double) minecraft.displayHeight - 1;
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			mouseScrolled(mx, my, wheel > 0 ? 1 : -1);
		}
		int button = Mouse.getEventButton();
		if (button >= 0) {
			if (Mouse.getEventButtonState()) {
				mousePressed(mx, my, button);
			} else {
				mouseUp(mx, my, button);
			}
		} else if (Mouse.getEventDX() != 0 || Mouse.getEventDY() != 0) {
			double dx = Mouse.getEventDX() * width / (double) minecraft.displayWidth;
			double dy = -Mouse.getEventDY() * height / (double) minecraft.displayHeight;
			if (Mouse.isButtonDown(MOUSE_LEFT) || Mouse.isButtonDown(MOUSE_RIGHT) || Mouse.isButtonDown(MOUSE_MIDDLE)) {
				mouseDragged(mx, my, dx, dy);
			}
		}
	}

	private void mousePressed(double mx, double my, int button) {
		if (showHelp) {
			showHelp = false;
			return;
		}
		if (showSettings) {
			// Clicks inside the panel use its buttons; a click anywhere else closes it.
			if (mx >= settingsX && mx < settingsX + settingsW && my >= settingsY && my < settingsY + settingsH) {
				hits.click(mx, my, button);
			} else {
				showSettings = false;
			}
			return;
		}
		TextBox clickedBox = null;
		for (TextBox b : boxes) {
			if (b.contains(mx, my)) {
				clickedBox = b;
			}
		}
		for (TextBox b : boxes) {
			b.setFocused(b == clickedBox);
		}
		if (clickedBox != null) {
			return;
		}
		if (hits.click(mx, my, button)) {
			return;
		}
		if (worldShown()) {
			if (inWorldViewport(mx, my)) {
				worldClicked(mx, my, button);
			}
			return;
		}
		if (view.contains(mx, my)) {
			if (button == MOUSE_RIGHT || button == MOUSE_MIDDLE || (button == MOUSE_LEFT && Keyboard.isKeyDown(Keyboard.KEY_SPACE))) {
				panning = true;
				return;
			}
			if (button == MOUSE_LEFT) {
				painting = true;
				state.tool.press(state, blockX(mx), blockZ(my), shiftDown());
			}
		}
	}

	private void worldClicked(double mx, double my, int button) {
		if (button == MOUSE_RIGHT || button == MOUSE_MIDDLE || (button == MOUSE_LEFT && Keyboard.isKeyDown(Keyboard.KEY_SPACE))) {
			if (shiftDown()) {
				panning3d = true;
			} else {
				orbiting3d = true;
			}
			return;
		}
		if (button != MOUSE_LEFT) {
			return;
		}
		Hit3D hit = toHit(world3d.pick(mx, my, false, false));
		if (hit == null) {
			state.say("Point at the ground to use the tool (right or middle drag turns the view).");
			return;
		}
		painting3d = true;
		lastPaintHit = hit;
		state.blocksPerGuiPixel = world3d.blocksPerPixelAt(hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5, height);
		if (state.tool instanceof Tool3D t3) {
			t3.press3d(state, hit, shiftDown());
		} else {
			state.tool.press(state, hit.x(), hit.z(), shiftDown());
		}
	}

	private void mouseDragged(double mx, double my, double dx, double dy) {
		OrbitCamera cam3d = world3d == null ? null : world3d.camera();
		if (orbiting3d && cam3d != null) {
			if (controlDown()) {
				cam3d.zoom(-dy * 0.05);
			} else {
				cam3d.orbit(dx, dy);
			}
			return;
		}
		if (panning3d && cam3d != null) {
			cam3d.pan(dx, dy, height);
			return;
		}
		if (painting3d) {
			Hit3D hit = toHit(world3d.pick(mx, my, false, false));
			if (hit != null) {
				lastPaintHit = hit;
			}
			if (state.tool instanceof Tool3D t3) {
				t3.drag3d(state, hit, shiftDown());
			} else if (hit != null) {
				state.tool.drag(state, hit.x(), hit.z(), shiftDown());
			}
			return;
		}
		if (panning) {
			view.panGui(dx, dy);
			return;
		}
		if (hits.drag(mx, my)) {
			return;
		}
		if (painting) {
			state.tool.drag(state, blockX(mx), blockZ(my), shiftDown());
		}
	}

	private void mouseUp(double mx, double my, int button) {
		hits.release();
		if (orbiting3d || panning3d) {
			orbiting3d = false;
			panning3d = false;
			return;
		}
		if (painting3d && button == MOUSE_LEFT) {
			painting3d = false;
			if (state.tool instanceof Tool3D t3) {
				t3.release3d(state, shiftDown());
			} else if (lastPaintHit != null) {
				state.tool.release(state, lastPaintHit.x(), lastPaintHit.z(), shiftDown());
			}
			return;
		}
		if (panning) {
			panning = false;
			return;
		}
		if (painting && button == MOUSE_LEFT) {
			painting = false;
			state.tool.release(state, blockX(mx), blockZ(my), shiftDown());
		}
	}

	private void mouseScrolled(double mx, double my, double amount) {
		if (showHelp || showSettings) {
			return;
		}
		if (hits.scroll(mx, my, amount)) {
			return;
		}
		if (worldShown()) {
			if (inWorldViewport(mx, my) && world3d.camera() != null) {
				if (controlDown()) {
					resizeBrush(amount > 0 ? 1 : -1);
				} else {
					world3d.camera().zoom(amount > 0 ? 1 : -1);
				}
			}
			return;
		}
		if (view.contains(mx, my)) {
			if (controlDown()) {
				resizeBrush(amount > 0 ? 1 : -1);
			} else {
				view.zoomAt(mx, my, amount > 0 ? -1 : 1);
			}
		}
	}

	private void resizeBrush(int dir) {
		Brush b = state.brush;
		int step = Math.max(1, b.radius / 6);
		b.radius = MathUtil.clamp(b.radius + dir * step, 0, Brush.MAX_RADIUS);
		state.say("Brush size " + (b.radius * 2 + 1));
	}

	/** Beta's screens only see key presses; the painter also needs releases (held keys move the map). */
	@Override
	public void onKeyboardEvent() {
		int key = Keyboard.getEventKey();
		if (Keyboard.getEventKeyState()) {
			if (key == Keyboard.KEY_F11) {
				minecraft.toggleFullscreen();
				return;
			}
			keyDown(Keyboard.getEventCharacter(), key);
		} else {
			heldKeys.remove(key);
		}
	}

	private void keyDown(char c, int key) {
		TextBox box = focusedBox();
		if (box != null) {
			if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) {
				box.setFocused(false);
				return;
			}
			box.key(c, key, controlDown(), controlDown() && key == Keyboard.KEY_V ? Screen.getClipboard() : null);
			return;
		}
		if (showHelp && (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_F1)) {
			showHelp = false;
			return;
		}
		if (showSettings && key == Keyboard.KEY_ESCAPE) {
			showSettings = false;
			return;
		}
		if (controlDown()) {
			if (key == Keyboard.KEY_Z) {
				state.say(shiftDown() ? label("Redid", state.session.redo()) : label("Undid", state.session.undo()));
				return;
			}
			if (key == Keyboard.KEY_Y) {
				state.say(label("Redid", state.session.redo()));
				return;
			}
			if (key == Keyboard.KEY_S) {
				save();
				return;
			}
		}
		for (Tool tool : Tools.ALL) {
			if (key == keyFor(tool.hotkey()) && !controlDown()) {
				selectTool(tool);
				return;
			}
		}
		if (key == Keyboard.KEY_1 || key == Keyboard.KEY_2 || key == Keyboard.KEY_3 || key == Keyboard.KEY_4) {
			int idx = key == Keyboard.KEY_1 ? 0 : key == Keyboard.KEY_2 ? 1 : key == Keyboard.KEY_3 ? 2 : 3;
			List<Layer> layers = state.layers();
			if (idx < layers.size()) {
				state.layer = layers.get(idx);
			}
			return;
		}
		if (key == Keyboard.KEY_Q) {
			state.templateRotation = (state.templateRotation + 1) & 3;
			return;
		}
		if (key == Keyboard.KEY_LBRACKET) {
			resizeBrush(-1);
			return;
		}
		if (key == Keyboard.KEY_RBRACKET) {
			resizeBrush(1);
			return;
		}
		if (key == Keyboard.KEY_EQUALS || key == Keyboard.KEY_ADD) {
			view.zoomAt(view.left + view.width / 2.0, view.top + view.height / 2.0, -1);
			return;
		}
		if (key == Keyboard.KEY_MINUS || key == Keyboard.KEY_SUBTRACT) {
			view.zoomAt(view.left + view.width / 2.0, view.top + view.height / 2.0, 1);
			return;
		}
		if (key == Keyboard.KEY_F1) {
			showHelp = !showHelp;
			return;
		}
		if (key == Keyboard.KEY_F5) {
			cycleLayout();
			return;
		}
		if (key == Keyboard.KEY_F && hover3d != null && world3d.camera() != null) {
			world3d.camera().focus(hover3d.x() + 0.5, hover3d.y() + 0.5, hover3d.z() + 0.5);
			return;
		}
		if (key == Keyboard.KEY_HOME && worldShown()) {
			world3d.flyTo(0, 0);
			return;
		}
		if (key == Keyboard.KEY_HOME) {
			view.centerX = 0;
			view.centerZ = 0;
			return;
		}
		if (isPanKey(key)) {
			heldKeys.add(key);
			return;
		}
		if (key == Keyboard.KEY_ESCAPE) {
			onClose();
		}
	}

	private static int keyFor(char c) {
		switch (c) {
			case 'B': return Keyboard.KEY_B;
			case 'E': return Keyboard.KEY_E;
			case 'H': return Keyboard.KEY_H;
			case 'M': return Keyboard.KEY_M;
			case 'G': return Keyboard.KEY_G;
			case 'R': return Keyboard.KEY_R;
			case 'I': return Keyboard.KEY_I;
			case 'L': return Keyboard.KEY_L;
			case 'T': return Keyboard.KEY_T;
			case 'P': return Keyboard.KEY_P;
			case 'C': return Keyboard.KEY_C;
			default: return -1;
		}
	}

	private static boolean isPanKey(int key) {
		return key == Keyboard.KEY_W || key == Keyboard.KEY_A || key == Keyboard.KEY_S || key == Keyboard.KEY_D
				|| key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN || key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT;
	}

	private void applyKeyboardPan() {
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		// A key released while another screen had the keyboard is not seen: check what is really held.
		heldKeys.removeIf(k -> !Keyboard.isKeyDown(k));
		if (heldKeys.isEmpty() || focusedBox() != null) {
			return;
		}
		if (worldShown()) {
			flyWithKeys(dt);
			return;
		}
		double speed = 500 * dt * (shiftDown() ? 3 : 1);
		double dx = 0, dy = 0;
		if (heldKeys.contains(Keyboard.KEY_W) || heldKeys.contains(Keyboard.KEY_UP)) {
			dy += speed;
		}
		if (heldKeys.contains(Keyboard.KEY_S) || heldKeys.contains(Keyboard.KEY_DOWN)) {
			dy -= speed;
		}
		if (heldKeys.contains(Keyboard.KEY_A) || heldKeys.contains(Keyboard.KEY_LEFT)) {
			dx += speed;
		}
		if (heldKeys.contains(Keyboard.KEY_D) || heldKeys.contains(Keyboard.KEY_RIGHT)) {
			dx -= speed;
		}
		view.panGui(dx, dy);
	}

	/** In the 3D view WASD move the camera, relative to where it looks. */
	private void flyWithKeys(double dt) {
		OrbitCamera c = world3d.camera();
		if (c == null) {
			return;
		}
		double speed = Math.max(4, c.distance * 0.8) * dt * (shiftDown() ? 3 : 1);
		double f = 0, r = 0;
		if (heldKeys.contains(Keyboard.KEY_W) || heldKeys.contains(Keyboard.KEY_UP)) {
			f += speed;
		}
		if (heldKeys.contains(Keyboard.KEY_S) || heldKeys.contains(Keyboard.KEY_DOWN)) {
			f -= speed;
		}
		if (heldKeys.contains(Keyboard.KEY_D) || heldKeys.contains(Keyboard.KEY_RIGHT)) {
			r += speed;
		}
		if (heldKeys.contains(Keyboard.KEY_A) || heldKeys.contains(Keyboard.KEY_LEFT)) {
			r -= speed;
		}
		if (f != 0 || r != 0) {
			c.fly(f, r, 0);
		}
	}

	@Override
	public void tick() {
		ticks++;
		if (live()) {
			tickLive();
		}
		if (painting && !worldShown() && view.contains(mouseX, mouseY)) {
			state.tool.hold(state, blockX(mouseX), blockZ(mouseY), shiftDown());
		}
		if (painting3d && worldShown()) {
			Hit3D hit = toHit(world3d.pick(mouseX, mouseY, false, false));
			if (hit != null) {
				lastPaintHit = hit;
			}
			if (state.tool instanceof Tool3D t3) {
				t3.hold3d(state, hit, shiftDown());
			} else if (hit != null) {
				state.tool.hold(state, hit.x(), hit.z(), shiftDown());
			}
		}
	}

	// ------------------------------------------------------------------ live changes and settings

	/** Live changes (the setting): the design saves itself and the running world follows it. */
	private boolean live() {
		return Settings.liveChanges();
	}

	/**
	 * Every frame: with live changes in the world you are playing, the chunks the design just changed
	 * are handed over right away and regenerated with a time budget; the map reads loaded chunks.
	 */
	private void liveFrame() {
		if (liveInWorld()) {
			drainRegen();
			LiveRegen.work(mc.world, mc.player, LIVE_BUDGET);
		}
		if (loaded != null && mc.world != null) {
			loaded.scan(mc.world, SCAN_BUDGET);
		}
	}

	/** Hands the chunks changed by the last edits to live regeneration. */
	private void drainRegen() {
		DirtyChunks changed = state.world.regen();
		if (!changed.isEmpty() && mc.world != null) {
			DirtyChunks now = changed.copy();
			changed.clear();
			LiveRegen.request(mc.world, now);
		}
	}

	/** Every tick with live changes on: saves the design a moment after the last change (not in the middle of a stroke). */
	private void tickLive() {
		long now = System.currentTimeMillis();
		int count = state.session.changeCount();
		if (count != seenChangeCount) {
			seenChangeCount = count;
			lastChangeMillis = now;
		}
		// In the world you are playing the design is used straight from the painter: saving can wait a little.
		long delay = liveInWorld() ? 1500 : 400;
		if (state.world.hasUnsavedChanges() && !painting && !painting3d && now - lastChangeMillis >= delay) {
			if (!liveCommit()) {
				// Saving failed (message shown): try again in a while rather than every tick.
				lastChangeMillis = now + 5000;
			}
		}
	}

	/**
	 * Saves the design and, in the running world, has the chunks it changed follow it right away
	 * (loaded ones in a moment, the others when you get near them).
	 */
	private boolean liveCommit() {
		state.session.end();
		if (liveInWorld()) {
			drainRegen();
		}
		DirtyChunks changed = state.world.regen().copy();
		try {
			state.world.save(MapColors::summarize, target.existingWorld());
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not save World Painter design", e);
			state.say("Could not save: " + e.getMessage());
			return false;
		}
		switch (target.kind()) {
			case NEW_WORLD:
				DraftSession.arm();
				break;
			case SAVED_WORLD:
				// Painted chunks that already exist regenerate when the world is opened.
				break;
			case RUNNING_WORLD:
				if (!liveInWorld()) {
					// Painted from another dimension: the Overworld uses the saved design.
					WorldPainter.rebind();
				} else if (!changed.isEmpty()) {
					LiveRegen.request(mc.world, changed);
				}
				break;
		}
		return true;
	}

	/** "Done" with live changes on: saves what is left and closes. */
	private void closeLive() {
		if (state.world.hasUnsavedChanges() && !liveCommit()) {
			return;
		}
		closeScreen();
	}

	private void toggleLive() {
		boolean on = !Settings.liveChanges();
		if (!on && state.world.hasUnsavedChanges()) {
			// What was painted live is in the world already: keep it in the saved design too.
			liveCommit();
		}
		Settings.setLiveChanges(on);
		useLiveDesign();
		if (on) {
			seenChangeCount = -1;
			state.say(target.kind() == PainterTarget.Kind.RUNNING_WORLD
					? "Live changes on: what you paint changes the world right away."
					: "Live changes on: your design saves itself.");
		} else {
			state.say("Live changes off: press Save to keep your changes, Save & Reload to see them in visited land.");
		}
	}

	private void drawSettings() {
		boolean on = Settings.liveChanges();
		int w = Math.min(360, width - 40);
		int textW = w - 20;
		List<String> about = new ArrayList<>();
		if (on) {
			about.addAll(g.wrap("On: everything you paint changes the world right away. The design saves itself, painted chunks"
					+ " near you regenerate within a few seconds, and painted chunks further away regenerate when you get near"
					+ " them. There are no Save buttons: press Done (or Esc) to close.", textW));
			about.add("");
			about.addAll(g.wrap("Builds in painted chunks are replaced by what the design generates there, without a backup."
					+ " Undo regenerates the chunks again.", textW));
		} else {
			about.addAll(g.wrap("Off: changes stay in the design until you press Save. Land that already exists regenerates"
					+ " after Save & Reload (the old region files are backed up first).", textW));
		}
		int h = 26 + 18 + about.size() * 11 + 30;
		int x = (width - w) / 2, y = Math.max(TOP + 10, (height - h) / 2);
		settingsX = x;
		settingsY = y;
		settingsW = w;
		settingsH = h;
		g.fill(x, y, x + w, y + h, 0xF0101018);
		g.outline(x, y, w, h, ACCENT);
		// Covers what is under the panel, so only the panel's own buttons react.
		hits.add(x, y, w, h, (mx, my, b) -> {
		});
		g.text("Settings", x + 10, y + 8, 0xFFFFD040, false);
		int ty = y + 24;
		button(x + 10, ty, w - 20, "Live changes: " + (on ? "On" : "Off"), on, this::toggleLive,
				"Click to turn live changes " + (on ? "off" : "on"));
		ty += 20;
		for (String line : about) {
			g.text(line, x + 10, ty, GRAY, false);
			ty += 11;
		}
		button(x + w - 70, y + h - 22, 60, "Done", false, () -> showSettings = false, "Close the settings");
	}

	// ------------------------------------------------------------------ saving / closing

	private boolean save() {
		if (live()) {
			boolean ok = liveCommit();
			if (ok) {
				state.say(target.kind() == PainterTarget.Kind.RUNNING_WORLD
						? "Saved. Live changes are on: painted chunks regenerate by themselves."
						: "Saved.");
			}
			return ok;
		}
		state.session.end();
		try {
			state.world.save(MapColors::summarize, target.existingWorld());
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not save World Painter design", e);
			state.say("Could not save: " + e.getMessage());
			return false;
		}
		switch (target.kind()) {
			case NEW_WORLD:
				DraftSession.arm();
				state.say("Saved. The design is used when you create the world.");
				break;
			case SAVED_WORLD:
				state.say("Saved. Painted chunks that already exist regenerate when the world is opened (with a backup).");
				break;
			case RUNNING_WORLD:
				WorldPainter.rebind();
				state.say("Saved. New chunks use it now; visited painted chunks regenerate after Save & Reload.");
				break;
		}
		return true;
	}

	/** Saves, leaves the world (saving it) and opens it again, so painted chunks already visited regenerate. */
	private void saveAndReload() {
		if (!save()) {
			return;
		}
		String folder = target.levelId();
		String name = target.displayName();
		Minecraft game = mc;
		closeScreen();
		// Like "Save and Quit to Title" (the world is saved as it closes), then like opening it from the world list.
		game.setWorld(null);
		game.startGame(folder, name, 0L);
		game.setScreen(null);
	}

	private void discard() {
		long now = System.currentTimeMillis();
		if (state.world.hasUnsavedChanges() && now > discardArmedUntil) {
			discardArmedUntil = now + 3000;
			state.say("Click Discard again to throw away your unsaved changes.");
			return;
		}
		discarded = true;
		closeScreen();
	}

	private void clearAll() {
		long now = System.currentTimeMillis();
		if (now > clearArmedUntil) {
			clearArmedUntil = now + 3000;
			state.say("Click Clear All again to delete the whole design.");
			return;
		}
		try {
			state.world.deleteEverything();
			state.session.resetHistory();
			renderer.invalidate();
			state.say("The design was cleared.");
		} catch (IOException e) {
			state.say("Could not clear: " + e.getMessage());
		}
	}

	private void onClose() {
		if (live()) {
			closeLive();
			return;
		}
		if (state.world.hasUnsavedChanges()) {
			state.say("Unsaved changes: use Save & Close, or Discard (click it twice).");
			return;
		}
		closeScreen();
	}

	private void closeScreen() {
		if (target.kind() == PainterTarget.Kind.NEW_WORLD && PaintDimension.anyPaint(target.paintRoot())) {
			DraftSession.arm();
		}
		if (world3d != null && world3d.camera() != null) {
			lastCamera = world3d.camera().copy();
		}
		mc.setScreen(target.parent());
	}

	/** From the Structures tool's 3D mode on the map: the editor opens looking at that spot. */
	private void openStructureEditorAt(int x, int z) {
		if (world3d == null) {
			openStructureEditor(null);
			return;
		}
		OrbitCamera c = world3d.cameraAt(x + 0.5, z + 0.5);
		c.distance = 18;
		openStructureEditor(c);
	}

	/** The structure editor opened from here was closed without coming back (the world was left): clean up now. */
	void editorAbandoned() {
		suspended = false;
		removed();
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
		if (suspended || cleanedUp) {
			// The structure editor is shown; the painter comes back when it closes.
			return;
		}
		cleanedUp = true;
		if (!discarded && state.world.hasUnsavedChanges()) {
			// Left some other way (e.g. the game closed): keep the work.
			save();
		}
		if (world3d != null) {
			world3d.exit();
		}
		LiveRegen.setListener(null);
		if (target.kind() == PainterTarget.Kind.RUNNING_WORLD && target.worldRoot() != null) {
			// Generation goes back to the saved design.
			PaintBindings.useDesign(target.worldRoot(), null);
		}
		renderer.close();
		if (state.existing != null) {
			state.existing.close();
		}
	}
}
