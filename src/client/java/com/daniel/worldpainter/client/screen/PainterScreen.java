package com.daniel.worldpainter.client.screen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.DraftSession;
import com.daniel.worldpainter.client.PainterTarget;
import com.daniel.worldpainter.client.Settings;
import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.editor.StructureEditorLauncher;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.existing.ExistingWorldLayer;
import com.daniel.worldpainter.client.map.BiomeColors;
import com.daniel.worldpainter.client.map.MapColors;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapRenderer;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.map.StructureInfo;
import com.daniel.worldpainter.client.map.SurfaceColors;
import com.daniel.worldpainter.client.existing.ExistingStructure;
import com.daniel.worldpainter.client.edit.VolumeOps;
import com.daniel.worldpainter.client.PainterResume;
import com.daniel.worldpainter.client.edit.EditSession;
import com.daniel.worldpainter.client.editor.BlockRay;
import com.daniel.worldpainter.client.editor.LiveWorldView;
import com.daniel.worldpainter.client.editor.OrbitCamera;
import com.daniel.worldpainter.client.tools.Hit3D;
import com.daniel.worldpainter.editor.EditorOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import com.daniel.worldpainter.client.tools.Sculpt3DTool;
import com.daniel.worldpainter.client.tools.Tool3D;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.StructurePlan;
import com.daniel.worldpainter.gen.Dimensions;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.FormattedCharSequence;
import com.daniel.worldpainter.client.templates.ProceduralTemplates;
import com.daniel.worldpainter.client.templates.Template;
import com.daniel.worldpainter.client.templates.TemplateData;
import com.daniel.worldpainter.client.templates.TemplateLibrary;
import com.daniel.worldpainter.client.tools.Brush;
import com.daniel.worldpainter.client.tools.HeightTool;
import com.daniel.worldpainter.client.tools.SmoothEdgesTool;
import com.daniel.worldpainter.client.tools.Tool;
import com.daniel.worldpainter.client.tools.Tools;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.data.DirtyChunks;
import com.daniel.worldpainter.live.LiveRegen;
import net.minecraft.world.level.Level;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The world painter. Every map pixel is one block; the map can show the whole 60,000,000 block world.
 * Instead of the map, the painter can show the real world in 3D (like the 3D structure editor) and be
 * used in it: every tool works on the block under the mouse.
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

	private final PainterTarget target;
	private final PainterState state;
	private final MapView view = new MapView();
	private final MapRenderer renderer = new MapRenderer();
	private final Hits hits = new Hits();
	private static final Identifier TOOLS_TEXTURE = com.daniel.worldpainter.client.Icons.TEXTURE;
	private static final int ICON_SHEET_W = com.daniel.worldpainter.client.Icons.SHEET_W;
	private static final int STRUCTURE_ICON = com.daniel.worldpainter.client.Icons.STRUCTURE;

	private final List<String> allBiomes;
	private final List<String> allStructures;
	private List<Template> templates;

	private EditBox biomeSearch;
	private EditBox blockSearch;
	private EditBox templateName;
	private EditBox structureSearch;
	private String structureQuery = "";
	private String biomeQuery = "";
	private String blockQuery = "";
	private String templateNameValue = "My template";
	private Button undoButton;
	private Button redoButton;
	private Button viewButton;
	private Button gridButton;
	private Button existingButton;
	private Button layoutButton;
	private int topButtonsLeft;

	private Layout layout = Layout.MAP;
	/** The 3D view (the real world); null where it cannot be shown (world not open, another dimension). */
	private final LiveWorldView world3d;
	/** Camera to restore after the world was reloaded from the 3D view. */
	private PainterResume.State resume;
	private final MapView miniView = new MapView();
	private boolean showMiniMap = true;
	private boolean orbiting3d;
	private boolean panning3d;
	private boolean painting3d;
	private Hit3D hover3d;
	private Hit3D lastPaintHit;
	private final PainterState.LiveSculpt liveSculptHook = this::liveSculpt;

	private int biomeScroll, blockScroll, templateScroll, structureScroll;
	private List<String> blockResults = List.of();
	private String blockResultsQuery;

	private boolean painting;
	private boolean panning;
	private boolean spaceDown;
	private double mouseX, mouseY;
	private final Set<Integer> heldKeys = new HashSet<>();
	private long lastFrameNanos;
	private boolean showHelp;
	/** The settings panel (gear button in the top bar). */
	private boolean showSettings;
	private Button gearButton;
	private int settingsX, settingsY, settingsW, settingsH;
	/** Live changes: when the design last changed, to save it a moment after you stop. */
	private int seenChangeCount = -1;
	private long lastChangeMillis;
	private long discardArmedUntil;
	private long clearArmedUntil;
	private long deleteTemplateArmedUntil;
	private boolean discarded;
	private boolean cleanedUp;
	/** Live changes: a slice of regeneration work is waiting on the server thread. */
	private final java.util.concurrent.atomic.AtomicBoolean liveWorkQueued = new java.util.concurrent.atomic.AtomicBoolean();
	/** Live changes: the world generates with this painter's design (as it is painted). */
	private boolean liveDesignBound;
	/** Server time per frame for live regeneration: much on the map (the game is paused), a little in the 3D view. */
	private static final long LIVE_PAUSED_BUDGET = 40_000_000L, LIVE_PLAYING_BUDGET = 8_000_000L;

	public PainterScreen(PainterTarget target) {
		this(target, null);
	}

	/** @param resume opens straight in the 3D view with this camera (after a reload from the 3D view) */
	public PainterScreen(PainterTarget target, PainterResume.State resume) {
		super(Component.literal("World Painter"));
		this.target = target;
		PaintWorld world = PaintWorld.open(target.paintDir(), 4096);
		ExistingWorldLayer existing = null;
		if (target.worldRoot() != null) {
			// Each dimension keeps its chunks in its own folder (region files of the Nether, the End, ...).
			Path regionDir = DimensionType.getStorageFolder(Dimensions.key(target.dimension()), target.worldRoot()).resolve("region");
			existing = new ExistingWorldLayer(regionDir);
		}
		this.state = new PainterState(target, world, existing);
		this.allBiomes = buildBiomeList(minecraft, target.dimension());
		this.allStructures = buildStructureList(minecraft, target.dimension());
		this.templates = loadTemplates();
		this.state.template = ProceduralTemplates.ALL.getFirst();

		PaintDimension playerDim = playerDimension();
		if (target.kind() == PainterTarget.Kind.RUNNING_WORLD && minecraft.player != null && playerDim != null) {
			// Start where the player is (Nether coordinates are 1/8 of the Overworld's).
			double scale = 1;
			if (playerDim == PaintDimension.OVERWORLD && target.dimension() == PaintDimension.NETHER) {
				scale = 1 / 8.0;
			} else if (playerDim == PaintDimension.NETHER && target.dimension() == PaintDimension.OVERWORLD) {
				scale = 8;
			} else if (playerDim != target.dimension()) {
				scale = 0;
			}
			view.centerX = minecraft.player.getX() * scale;
			view.centerZ = minecraft.player.getZ() * scale;
			view.zoom = 0;
		} else {
			view.zoom = existing != null && !existing.isEmpty() ? 1 : 2;
		}
		if (target.kind() == PainterTarget.Kind.NEW_WORLD && PaintWorld.hasPaint(target.paintDir())) {
			state.say("Loaded your earlier " + target.dimension().displayName + " design for a new world. Use Clear All to start fresh.");
		} else if (target.dimension() != PaintDimension.OVERWORLD) {
			state.say(target.dimension() == PaintDimension.NETHER
					? "Painting the Nether: biomes, structures and 3D sculpting (it has a roof, so no heights or water)."
					: "Painting the End: biomes, surface blocks, structures and 3D sculpting (floating islands!).");
		}

		this.world3d = worldViewPossible() ? new LiveWorldView(Dimensions.key(target.dimension())) : null;
		if (resume != null && world3d != null) {
			this.resume = resume;
			view.centerX = resume.pivotX();
			view.centerZ = resume.pivotZ();
			view.zoom = resume.mapZoom();
			layout = Layout.WORLD;
		} else if (lastLayout == Layout.WORLD && world3d != null) {
			layout = Layout.WORLD;
		}
		miniView.zoom = 1;
	}

	/** The 3D view shows the real world: the world must be open, and the painted dimension the one the player is in. */
	private boolean worldViewPossible() {
		return target.kind() == PainterTarget.Kind.RUNNING_WORLD && minecraft.getSingleplayerServer() != null
				&& minecraft.player != null && playerDimension() == target.dimension();
	}

	/** The dimension the player is in (when the painter is opened in game), or null. */
	private PaintDimension playerDimension() {
		return minecraft.level == null ? null : Dimensions.of(minecraft.level.dimension());
	}

	/** True if the player stands in the dimension being painted (their position can be shown). */
	private boolean playerHere() {
		return target.kind() == PainterTarget.Kind.RUNNING_WORLD && minecraft.player != null && playerDimension() == target.dimension();
	}

	/** All biomes, the ones of the painted dimension first. */
	private static List<String> buildBiomeList(Minecraft mc, PaintDimension dimension) {
		List<String> ids = new ArrayList<>(BiomeColors.vanillaIds());
		addRegistryIds(mc, Registries.BIOME, ids);
		List<String> sorted = new ArrayList<>();
		for (String id : ids) {
			if (dimension.displayName.equals(BiomeColors.dimension(id))) {
				sorted.add(id);
			}
		}
		for (String id : ids) {
			if (!sorted.contains(id)) {
				sorted.add(id);
			}
		}
		return sorted;
	}

	/** All structures, the ones of the painted dimension first. */
	private static List<String> buildStructureList(Minecraft mc, PaintDimension dimension) {
		List<String> ids = new ArrayList<>(StructureInfo.vanillaIds());
		addRegistryIds(mc, Registries.STRUCTURE, ids);
		List<String> sorted = new ArrayList<>();
		for (String id : ids) {
			if (StructureInfo.dimension(id) == dimension) {
				sorted.add(id);
			}
		}
		for (String id : ids) {
			if (!sorted.contains(id)) {
				sorted.add(id);
			}
		}
		return sorted;
	}

	/**
	 * Adds modded entries of a worldgen registry. The singleplayer server has every registry; the
	 * client world only has the ones the server syncs (biomes yes, structures no), so the server is
	 * asked first and a missing registry is simply skipped.
	 */
	private static <T> void addRegistryIds(Minecraft mc, ResourceKey<? extends Registry<? extends T>> key, List<String> ids) {
		RegistryAccess access = null;
		IntegratedServer server = mc.getSingleplayerServer();
		if (server != null) {
			access = server.registryAccess();
		} else if (mc.level != null) {
			access = mc.level.registryAccess();
		}
		if (access == null) {
			return;
		}
		Optional<Registry<T>> registry = access.lookup(key);
		if (registry.isEmpty()) {
			return;
		}
		for (Identifier id : registry.get().keySet()) {
			String s = id.toString();
			if (!ids.contains(s)) {
				ids.add(s);
			}
		}
	}

	private List<Template> loadTemplates() {
		List<Template> l = new ArrayList<>(ProceduralTemplates.ALL);
		l.addAll(TemplateLibrary.list(minecraft.gameDirectory.toPath()));
		return l;
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		layoutView();
		if (layout == Layout.WORLD) {
			openWorldView();
		}
		int x = width - 4;
		if (live()) {
			// Live changes: the design saves itself and the world follows it, so no Save buttons.
			x = topButton(x, "Done", "Close the painter (your design is saved automatically)", this::closeLive);
		} else {
			x = topButton(x, "Discard", "Close without saving (click twice)", this::discard);
			x = topButton(x, "Save & Close", "Save the design and close the painter", () -> {
				if (save()) {
					closeScreen();
				}
			});
			x = topButton(x, "Save", "Save the design (Ctrl+S)", this::save);
			if (target.kind() == PainterTarget.Kind.RUNNING_WORLD) {
				x = topButton(x, "Save & Reload", "Save, then leave and rejoin the world so painted chunks you already visited regenerate", this::saveAndReload);
			}
		}
		if (target.kind() == PainterTarget.Kind.NEW_WORLD) {
			x = topButton(x, "Clear All", "Delete the whole design of this dimension (click twice)", this::clearAll);
		}
		x -= 6;
		redoButton = addTop(x, "Redo", "Redo (Ctrl+Y)", () -> state.say(label("Redid", state.session.redo())));
		x = redoButton.getX() - 2;
		undoButton = addTop(x, "Undo", "Undo (Ctrl+Z)", () -> state.say(label("Undid", state.session.undo())));
		x = undoButton.getX() - 8;
		layoutButton = addTop(x, layoutLabel(), "Switch between the 2D map and the real world in 3D (F5)", this::cycleLayout);
		x = layoutButton.getX() - 2;
		Button dimButton = addTop(x, target.dimension().displayName,
				"Which dimension you are painting: click to switch to the " + target.dimension().next().displayName
						+ " (your design is saved first). Every dimension has its own design.", this::switchDimension);
		x = dimButton.getX() - 2;
		if (state.existing != null) {
			existingButton = addTop(x, existingLabel(), "Show or hide the existing world under the paint", () -> {
				state.showExisting = !state.showExisting;
				existingButton.setMessage(Component.literal(existingLabel()));
			});
			x = existingButton.getX() - 2;
		}
		gridButton = addTop(x, gridLabel(), "Show chunk / region grid lines", () -> {
			state.showGrid = !state.showGrid;
			gridButton.setMessage(Component.literal(gridLabel()));
		});
		x = gridButton.getX() - 2;
		viewButton = addTop(x, "View: " + state.viewMode.label, "What the map shows", () -> {
			PainterState.ViewMode[] modes = PainterState.ViewMode.values();
			state.viewMode = modes[(state.viewMode.ordinal() + 1) % modes.length];
			viewButton.setMessage(Component.literal("View: " + state.viewMode.label));
		});
		x = viewButton.getX() - 2;
		Button help = addTop(x, "?", "Controls (F1)", () -> showHelp = !showHelp);
		x = help.getX() - 2;
		gearButton = addTop(x, "", "Settings", () -> showSettings = !showSettings);
		topButtonsLeft = gearButton.getX();

		biomeSearch = searchBox("Search biomes", biomeQuery, v -> {
			biomeQuery = v;
			biomeScroll = 0;
		});
		blockSearch = searchBox("Search blocks", blockQuery, v -> {
			blockQuery = v;
			blockScroll = 0;
		});
		templateName = searchBox("Template name", templateNameValue, v -> templateNameValue = v);
		templateName.setMaxLength(48);
		structureSearch = searchBox("Search structures", structureQuery, v -> {
			structureQuery = v;
			structureScroll = 0;
		});
	}

	private EditBox searchBox(String hint, String value, Consumer<String> responder) {
		EditBox box = new EditBox(font, 0, 0, PANEL_W - 12, 14, Component.literal(hint));
		box.setMaxLength(64);
		box.setValue(value);
		box.setHint(Component.literal(hint));
		box.setResponder(responder);
		box.visible = false;
		addRenderableWidget(box);
		return box;
	}

	private int topButton(int right, String text, String tip, Runnable action) {
		Button b = addTop(right, text, tip, action);
		return b.getX() - 2;
	}

	private Button addTop(int right, String text, String tip, Runnable action) {
		int w = Math.max(20, font.width(text) + 10);
		Button b = Button.builder(Component.literal(text), btn -> action.run())
				.bounds(right - w, 2, w, 18)
				.tooltip(Tooltip.create(Component.literal(tip)))
				.build();
		addRenderableWidget(b);
		return b;
	}

	/** Saves, then opens the painter on the next dimension of the same world. */
	private void switchDimension() {
		PaintDimension next = target.dimension().next();
		if (state.world.hasUnsavedChanges() && !save()) {
			return;
		}
		minecraft.gui.setScreen(new PainterScreen(target.withDimension(next)));
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
			explainNoWorldView();
			return;
		}
		orbiting3d = panning3d = painting3d = false;
		if (l == Layout.MAP && world3d != null) {
			OrbitCamera c = world3d.camera();
			if (c != null) {
				view.centerX = c.pivotX;
				view.centerZ = c.pivotZ;
			}
			world3d.exit(minecraft);
			state.liveSculpt = null;
		}
		layout = l;
		lastLayout = l;
		if (layoutButton != null) {
			layoutButton.setMessage(Component.literal(layoutLabel()));
		}
		if (l == Layout.WORLD) {
			openWorldView();
		}
	}

	/** Opens the 3D view at the middle of the map (or with the camera from before a reload). */
	private void openWorldView() {
		OrbitCamera from = null;
		if (resume != null) {
			from = new OrbitCamera(resume.pivotX(), resume.pivotY(), resume.pivotZ());
			from.yaw = resume.yaw();
			from.pitch = resume.pitch();
			from.distance = resume.distance();
			resume = null;
		}
		world3d.enter(minecraft, view.centerX, view.centerZ, from);
	}

	/** Why the 3D view is not available here, and what to do (open a saved world straight in 3D). */
	private void explainNoWorldView() {
		switch (target.kind()) {
			case NEW_WORLD -> state.say("The 3D view is the real world: create the world, then press O in it and switch to 3D.");
			case SAVED_WORLD -> openSavedWorldIn3d();
			case RUNNING_WORLD -> state.say("The 3D view shows the dimension you are in: go to the " + target.dimension().displayName
					+ " to see it in 3D.");
		}
	}

	/** Saves, opens this world (painted chunks regenerate while it loads) and comes back in the 3D view. */
	private void openSavedWorldIn3d() {
		if (!save()) {
			return;
		}
		PainterResume.set(new PainterResume.State(target.levelId(), target.dimension(), view.centerX, Double.NaN, view.centerZ,
				180f, 40f, 48, view.zoom, System.currentTimeMillis()));
		String levelId = target.levelId();
		Screen parent = target.parent();
		Minecraft mc = minecraft;
		closeScreen();
		mc.createWorldOpenFlows().openWorld(levelId, () -> mc.gui.setScreen(parent));
	}

	/** While the 3D view is open, sculpted blocks change in the world right away (with undo). */
	private void liveSculpt(long[] positions, int count, boolean add) {
		if (world3d == null || !world3d.open()) {
			return;
		}
		LiveEdit edit = new LiveEdit();
		state.session.attach(edit);
		world3d.sculpt(minecraft, positions, count, add, edit.changes::addAll);
	}

	/** Blocks changed in the world by one sculpt dab; undone and redone with the design. */
	private final class LiveEdit implements EditSession.Undoable {
		final List<EditorOps.BlockChange> changes = new ArrayList<>();

		@Override
		public void undo() {
			if (world3d != null && !changes.isEmpty()) {
				world3d.revert(minecraft, List.copyOf(changes), true);
			}
		}

		@Override
		public void redo() {
			if (world3d != null && !changes.isEmpty()) {
				world3d.revert(minecraft, List.copyOf(changes), false);
			}
		}
	}

	private boolean worldShown() {
		return layout == Layout.WORLD && world3d != null;
	}

	/** The part of the screen where the world is visible (between the side bars). */
	private boolean inWorldViewport(double x, double y) {
		return x >= TOOLBAR_W && x < width - PANEL_W && y >= TOP && y < height - STATUS_H;
	}

	private boolean overMiniMap(double x, double y) {
		return showMiniMap && miniView.contains(x, y);
	}

	private static Hit3D toHit(BlockRay.Hit h) {
		if (h == null) {
			return null;
		}
		BlockPos p = h.pos(), n = h.pos().relative(h.face());
		return new Hit3D(p.getX(), p.getY(), p.getZ(), n.getX() - p.getX(), n.getY() - p.getY(), n.getZ() - p.getZ());
	}

	private String gridLabel() {
		return state.showGrid ? "Grid: On" : "Grid: Off";
	}

	private String existingLabel() {
		return state.showExisting ? "World: On" : "World: Off";
	}

	private static String label(String verb, String what) {
		return what == null ? "Nothing to " + verb.toLowerCase().replace("did", "do") : verb + ": " + what;
	}

	private void layoutView() {
		int areaW = Math.max(10, width - TOOLBAR_W - PANEL_W);
		int areaH = Math.max(10, height - TOP - STATUS_H);
		double guiScale = minecraft.getWindow().getGuiScale();
		view.left = TOOLBAR_W;
		view.top = TOP;
		view.width = areaW;
		view.height = areaH;
		view.guiScale = guiScale;
		// The small map in a corner of the 3D view.
		int mw = Math.max(60, Math.min(220, areaW / 3));
		int mh = mw * 3 / 4;
		miniView.left = TOOLBAR_W + 6;
		miniView.top = height - STATUS_H - 6 - mh;
		miniView.width = mw;
		miniView.height = mh;
		miniView.guiScale = guiScale;
	}

	/** The game keeps running in the 3D view (the world loads and moves around the camera). */
	@Override
	public boolean isPauseScreen() {
		return !worldShown();
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (worldShown()) {
			// Transparent: the world is the 3D view.
			return;
		}
		g.fill(0, 0, width, height, 0xFF111116);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		mouseX = mx;
		mouseY = my;
		liveFrame();
		if (state.pendingEdit3d != null) {
			int[] at = state.pendingEdit3d;
			state.pendingEdit3d = null;
			openStructureEditor(at[0], at[1]);
			if (minecraft.gui.screen() != this) {
				return;
			}
		}
		applyKeyboardPan();
		layoutView();
		state.blocksPerGuiPixel = view.blocksPerGuiPixel();
		hits.clear();
		undoButton.active = state.session.canUndo();
		redoButton.active = state.session.canRedo();

		hover3d = null;
		if (worldShown()) {
			world3d.frame(minecraft, width, height);
			state.liveSculpt = world3d.open() ? liveSculptHook : null;
			OrbitCamera c = world3d.camera();
			if (c != null) {
				// The map follows the camera, so switching back shows the same place.
				view.centerX = c.pivotX;
				view.centerZ = c.pivotZ;
				if (inWorldViewport(mx, my) && !overMiniMap(mx, my) && !orbiting3d && !panning3d) {
					hover3d = toHit(world3d.pick(minecraft, mx, my));
				}
			}
			g.enableScissor(TOOLBAR_W, TOP, width - PANEL_W, height - STATUS_H);
			drawWorldOverlays(g);
			g.disableScissor();
			if (showMiniMap) {
				drawMiniMap(g, mx, my);
			}
			drawWorldButtons(g);
		} else {
			renderer.update(state, view);
			renderer.blit(g, view);
			g.enableScissor(view.left, view.top, view.left + view.width, view.top + view.height);
			drawMapOverlays(g, mx, my);
			g.disableScissor();
		}

		drawTopBar(g);
		drawToolbar(g);
		drawPanel(g);
		drawStatus(g);
		drawMessage(g);

		super.extractRenderState(g, mx, my, a);
		g.blit(RenderPipelines.GUI_TEXTURED, TOOLS_TEXTURE, gearButton.getX() + 2, gearButton.getY() + 1,
				com.daniel.worldpainter.client.Icons.GEAR * 16f, 0f, 16, 16, ICON_SHEET_W, 16);
		if (showSettings) {
			drawSettings(g);
		}

		String tip = hits.tooltip(mx, my);
		if (tip != null) {
			g.setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mx, my);
		}
		if (showHelp) {
			drawHelp(g);
		}
	}

	private void drawMapOverlays(GuiGraphicsExtractor g, int mx, int my) {
		double bpg = view.blocksPerGuiPixel();
		if (state.showGrid) {
			if (bpg <= 0.75) {
				gridLines(g, 16, 0x28FFFFFF);
			}
			if (bpg <= 24) {
				gridLines(g, 512, 0x50FFFFFF);
			} else if (bpg <= 800) {
				gridLines(g, 16384, 0x40FFFFFF);
			} else {
				gridLines(g, 1_000_000, 0x40FFFFFF);
			}
		}
		// World border (the world is 60,000,000 blocks wide)
		int lim = PaintWorld.WORLD_LIMIT;
		MapDraw.rect(g, view, -lim, -lim, lim - 1, lim - 1, 0xFFFF4040);
		MapDraw.cross(g, view, 0.5, 0.5, 0xFFFFD040);
		if (playerHere()) {
			MapDraw.cross(g, view, minecraft.player.getX(), minecraft.player.getZ(), 0xFF40FF60);
		}
		if (state.showStructures || state.tool == Tools.STRUCTURES) {
			drawStructures(g);
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
	private void drawWorldOverlays(GuiGraphicsExtractor g) {
		LiveWorldView w = world3d;
		OrbitCamera c = w.camera();
		if (c == null) {
			String text = w.opening() ? "Opening the 3D view..." : "The 3D view could not open (see the log).";
			int tw = font.width(text);
			int cx = TOOLBAR_W + (width - TOOLBAR_W - PANEL_W - tw) / 2;
			g.fill(cx - 6, height / 2 - 6, cx + tw + 6, height / 2 + 10, 0xC0101018);
			g.text(font, text, cx, height / 2, WHITE, false);
			return;
		}
		StructurePlan plan = state.world.structures();
		if (state.showStructures || state.tool == Tools.STRUCTURES) {
			for (StructurePlan.Placed placed : plan.placed) {
				double cx = placed.chunkX() * 16 + 8, cz = placed.chunkZ() * 16 + 8;
				double dx = cx - c.pivotX, dz = cz - c.pivotZ;
				if (dx * dx + dz * dz > 400 * 400) {
					continue;
				}
				int top = minecraft.level == null ? 64 : minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
						(int) cx, (int) cz);
				double[] at = w.project(cx, top + 2.5, cz);
				if (at != null && inWorldViewport(at[0], at[1])) {
					int gx = (int) Math.round(at[0]), gy = (int) Math.round(at[1]);
					g.blit(RenderPipelines.GUI_TEXTURED, TOOLS_TEXTURE, gx - 8, gy - 16, STRUCTURE_ICON * 16f, 0f, 16, 16, ICON_SHEET_W, 16);
				}
			}
		}
		if (state.selection != null) {
			int[] s = state.selection;
			w.groundRect(g, minecraft, s[0], s[1], s[2], s[3], 0xFF00E5FF);
		}
		int[] drag = state.tool.dragRect();
		if (drag != null) {
			w.groundRect(g, minecraft, drag[0], drag[1], drag[2], drag[3], state.tool == Tools.STRUCTURES ? 0xFFFF5050 : 0xFFFFFF00);
		}
		Hit3D h = painting3d ? lastPaintHit : hover3d;
		if (h == null || orbiting3d || panning3d) {
			return;
		}
		boolean shift = minecraft.hasShiftDown();
		if (state.tool instanceof Tool3D t3) {
			double[] t = t3.target(state, h, shift);
			if (state.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
				w.line(g, h.x() + 0.5, h.y() + 1, h.z() + 0.5, t[0], t[1], t[2], 0x80D0A0FF);
				w.ring(g, t[0], t[1], t[2], state.islandSize / 2.0, 0xFFD0A0FF);
			} else {
				int color = switch (state.sculpt3dMode) {
					case ADD -> shift ? 0xFFFF8060 : 0xFF80FF80;
					case CARVE -> shift ? 0xFF80FF80 : 0xFFFF8060;
					default -> 0xFFD0A0FF;
				};
				w.ball(g, t[0], t[1], t[2], Sculpt3DTool.radius(state) + 0.5, color);
			}
		} else if (state.tool.usesBrush()) {
			Brush b = state.brush;
			if (b.square) {
				w.groundRect(g, minecraft, h.x() - b.radius, h.z() - b.radius, h.x() + b.radius, h.z() + b.radius, 0xFFFFFFFF);
			} else {
				w.groundCircle(g, minecraft, h.x() + 0.5, h.z() + 0.5, b.radius + 0.5, 0xFFFFFFFF);
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
			w.groundRect(g, minecraft, x0, z0, x0 + tw - 1, z0 + td - 1, 0xFF7FFFFF);
		}
		w.block(g, h.x(), h.y(), h.z(), 0xFFFFFF60);
	}

	/** A small map in the corner of the 3D view: where you are; click to go somewhere else. */
	private void drawMiniMap(GuiGraphicsExtractor g, int mx, int my) {
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
			// Where the camera is and which way it looks.
			double ex = c.eye().x, ez = c.eye().z;
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
		g.outline(miniView.left - 1, miniView.top - 1, miniView.width + 2, miniView.height + 2, 0xFF3E6FB0);
		hits.add(miniView.left, miniView.top, miniView.width, miniView.height, new Hits.Hit() {
			@Override
			public void click(double x, double y, int button) {
				int bx = (int) Math.floor(miniView.toWorldX(x)), bz = (int) Math.floor(miniView.toWorldZ(y));
				world3d.flyTo(minecraft, bx, bz);
				state.say("Flying to X " + bx + "  Z " + bz);
			}

			@Override
			public boolean scroll(double amount) {
				miniView.zoom = Math.clamp(miniView.zoom + (amount > 0 ? -1 : 1), MapView.MIN_ZOOM, 6);
				return true;
			}
		}, "Map: click to fly there, mouse wheel to zoom");
	}

	/** Small buttons in the corner of the 3D view. */
	private void drawWorldButtons(GuiGraphicsExtractor g) {
		int x0 = TOOLBAR_W + 4, x = x0, y = TOP + 4;
		int right = width - PANEL_W - 4;
		String[][] items = {
				{"Top", "Look straight down"},
				{"Front", "Look north (like the map)"},
				{"Side", "Look west"},
				{"Map", "Show or hide the small map"},
		};
		for (int i = 0; i < items.length; i++) {
			String text = items[i][0];
			int w = font.width(text) + 10;
			if (x + w > right && x > x0) {
				x = x0;
				y += 17;
			}
			int index = i;
			button(g, x, y, w, text, i == 3 && showMiniMap, () -> worldButton(index), items[i][1]);
			x += w + 2;
		}
		// How painted changes reach the world that is shown.
		long pending = state.world.regen().countChunks();
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
			if (pending > 0) {
				lines.add("Save & Reload: " + pending + " painted chunk" + (pending == 1 ? "" : "s") + " already here regenerate");
			}
		}
		int ly = y + 21;
		for (String text : lines) {
			int tw = font.width(text);
			int tx = width - PANEL_W - tw - 8;
			g.fill(tx - 4, ly - 3, tx + tw + 4, ly + 11, 0xC0101018);
			g.text(font, text, tx, ly, 0xFFFFD040, false);
			ly += 14;
		}
	}

	private void worldButton(int index) {
		OrbitCamera c = world3d.camera();
		switch (index) {
			case 0 -> {
				if (c != null) {
					c.viewTop();
				}
			}
			case 1 -> {
				if (c != null) {
					c.yaw = 180;
					c.pitch = 10;
				}
			}
			case 2 -> {
				if (c != null) {
					c.yaw = 90;
					c.pitch = 10;
				}
			}
			case 3 -> showMiniMap = !showMiniMap;
			default -> {
			}
		}
	}

	private void gridLines(GuiGraphicsExtractor g, int spacing, int color) {
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

	private void drawTopBar(GuiGraphicsExtractor g) {
		g.fill(0, 0, width, TOP - 2, 0xFF1B1B23);
		g.fill(0, TOP - 2, width, TOP - 1, 0xFF2C2C38);
		String title = "World Painter - " + target.displayName() + " - " + target.dimension().displayName
				+ (state.world.hasUnsavedChanges() ? " *" : "");
		int room = topButtonsLeft - 10;
		if (room > 40) {
			g.text(font, fit(title, room), 6, 7, WHITE, false);
		}
	}

	private void drawToolbar(GuiGraphicsExtractor g) {
		g.fill(0, TOP, TOOLBAR_W - 2, height - STATUS_H, 0xFF18181E);
		int y = TOP + 3;
		for (Tool tool : Tools.ALL) {
			int x = 3;
			boolean sel = state.tool == tool;
			boolean hover = mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 20;
			boolean available = toolAvailable(tool);
			g.fill(x, y, x + 22, y + 20, sel ? ACCENT : hover ? 0xFF3A3A48 : 0xFF2A2A34);
			if (sel) {
				g.outline(x, y, 22, 20, 0xFF9CC2FF);
			}
			g.blit(RenderPipelines.GUI_TEXTURED, TOOLS_TEXTURE, x + 3, y + 2, Tools.icon(tool) * 16f, 0f, 16, 16, ICON_SHEET_W, 16);
			if (!available) {
				g.fill(x, y, x + 22, y + 20, 0xB018181E);
			}
			String tip = tool.name() + " (" + tool.hotkey() + ") - " + tool.help()
					+ (available ? "" : " Not available in the " + target.dimension().displayName + ".");
			hits.add(x, y, 22, 20, (mx, my, b) -> selectTool(tool), tip);
			y += 22;
		}
	}

	/** The height sculpt tool needs the height layer, which the Nether and the End do not have. */
	private boolean toolAvailable(Tool tool) {
		return tool != Tools.SCULPT || state.dimension.allows(Layer.HEIGHT);
	}

	private void selectTool(Tool tool) {
		if (!toolAvailable(tool)) {
			state.say(tool.name() + " shapes painted heights, which the " + target.dimension().displayName
					+ " does not have. Use 3D Sculpt (C) instead.");
			return;
		}
		state.tool = tool;
		if (tool == Tools.SCULPT && state.layer != Layer.HEIGHT) {
			state.layer = Layer.HEIGHT;
		}
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		int y = height - STATUS_H;
		g.fill(0, y, width, height, 0xFF1B1B23);
		String scale = worldShown() ? "3D world" : view.scaleText();
		String saved;
		if (live()) {
			String st = LiveRegen.status();
			saved = st.isEmpty() ? "Live changes" : st;
		} else {
			saved = state.world.hasUnsavedChanges() ? "Unsaved changes" : "Saved";
		}
		String right = scale + "   " + saved;
		int rw = font.width(right);
		g.text(font, right, width - rw - 6, y + 3, GRAY, false);
		if (hover3d != null) {
			g.text(font, fit(hoverInfo(hover3d.x(), hover3d.y(), hover3d.z()), width - rw - 20), 6, y + 3, WHITE, false);
		} else if (!worldShown() && view.contains(mouseX, mouseY)) {
			int bx = (int) Math.floor(view.toWorldX(mouseX));
			int bz = (int) Math.floor(view.toWorldZ(mouseY));
			g.text(font, fit(hoverInfo(bx, Integer.MIN_VALUE, bz), width - rw - 20), 6, y + 3, WHITE, false);
		}
	}

	/** Status line text for a block column ({@code y} is the block under the mouse in the 3D view, or MIN_VALUE). */
	private String hoverInfo(int x, int y, int z) {
		StringBuilder sb = new StringBuilder();
		sb.append("X ").append(x);
		if (y != Integer.MIN_VALUE) {
			sb.append("  Y ").append(y);
		}
		sb.append("  Z ").append(z);
		if (!PaintWorld.inWorld(x, z)) {
			return sb.append("  (outside the world)").toString();
		}
		String b = state.paintedBiome(x, z);
		if (b != null) {
			sb.append("  | ").append(BiomeColors.pretty(b));
		} else {
			String e = state.existingBiome(x, z);
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
			sb.append("  | ").append(su.replace("minecraft:", ""));
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

	private void drawMessage(GuiGraphicsExtractor g) {
		if (state.message == null || System.currentTimeMillis() > state.messageUntil) {
			return;
		}
		String msg = fit(state.message, view.width - 20);
		int w = font.width(msg) + 12;
		int x = view.left + (view.width - w) / 2;
		int y = view.top + view.height - 22;
		g.fill(x, y, x + w, y + 16, 0xE0101018);
		g.outline(x, y, w, 16, 0xFF3E6FB0);
		g.text(font, msg, x + 6, y + 4, WHITE, false);
	}

	private void drawHelp(GuiGraphicsExtractor g) {
		String[] lines = {
				"World Painter - controls",
				"",
				"Left mouse: use the tool      Shift + left mouse: erase / invert",
				"Right or middle mouse drag, or Space + drag, or WASD / arrows: move the map",
				"Mouse wheel: zoom (whole 60,000,000 block world at the far end)",
				"Ctrl + wheel or [ ]: brush size      1-4: layer (biome, height, surface, water/lava)",
				"B brush  E eraser  H sculpt  M smooth edges  G fill  R rectangle",
				"I pick  L select  T stamp template  Q rotate template  P structures",
				"Ctrl+Z undo   Ctrl+Y redo   Ctrl+S save   Home: go to 0,0   F1: this help",
				"C 3D sculpt (add ground, carve caves, restore, floating islands)",
				"The Overworld / Nether / End button switches dimension: each one has its own design.",
				"",
				"F5: 2D map / 3D world (the real world, like the K editor). In 3D: right or middle drag",
				"orbits, Shift + drag pans, Ctrl + drag or wheel zooms, WASD moves, F focuses on the",
				"block under the mouse. Every tool works on the block you point at. With live changes",
				"(gear button, on by default) painted chunks regenerate in a few seconds; without them",
				"painted changes show after Save & Reload (you come back to the same view).",
				"",
				"Each map pixel is one block. Unpainted land is generated normally by Minecraft.",
				"In existing worlds, painted areas that were already generated are regenerated: right",
				"away with live changes, otherwise the next time the world loads (with a backup).",
				"Structures keep their original places: painting biomes never adds or removes them.",
				"Use the Structures tool (P) to place, remove or forbid structures.",
				"",
				"Click anywhere to close"
		};
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, font.width(l));
		}
		w += 20;
		int h = lines.length * 11 + 16;
		int x = (width - w) / 2, y = (height - h) / 2;
		g.fill(x, y, x + w, y + h, 0xF0101018);
		g.outline(x, y, w, h, ACCENT);
		for (int i = 0; i < lines.length; i++) {
			g.text(font, lines[i], x + 10, y + 8 + i * 11, i == 0 ? 0xFFFFD040 : WHITE, false);
		}
	}

	// ------------------------------------------------------------------ side panel

	private void drawPanel(GuiGraphicsExtractor g) {
		int px = width - PANEL_W;
		int bottom = height - STATUS_H;
		g.fill(px, TOP, width, bottom, 0xFF18181E);
		g.fill(px, TOP, px + 1, bottom, 0xFF2C2C38);
		int x = px + 6;
		int w = PANEL_W - 12;
		int y = TOP + 4;

		biomeSearch.visible = false;
		blockSearch.visible = false;
		templateName.visible = false;
		structureSearch.visible = false;

		g.text(font, state.tool.name(), x, y, 0xFFFFD040, false);
		y += 12;

		if (state.tool == Tools.STRUCTURES) {
			int listBottom = bottom - 4 - bottomSectionsHeight();
			int sy = structureSection(g, x, y, w, listBottom);
			if (state.selection != null) {
				selectionSection(g, x, Math.max(sy + 4, listBottom + 4), w);
			}
			return;
		}
		if (state.tool == Tools.SCULPT_3D) {
			int sy = sculpt3dSection(g, x, y, w);
			int by = Math.max(sy + 4, bottom - 4 - bottomSectionsHeight());
			if (state.sculpt3dMode != PainterState.Sculpt3DMode.ISLAND) {
				by = brushSection(g, x, by, w);
			}
			if (state.selection != null) {
				selectionSection(g, x, by, w);
			}
			return;
		}

		// Layer tabs (only the layers this dimension has)
		List<Layer> layers = state.layers();
		if (!layers.contains(state.layer)) {
			state.layer = layers.getFirst();
		}
		int tabW = w / layers.size();
		for (int i = 0; i < layers.size(); i++) {
			Layer l = layers.get(i);
			String name = switch (l) {
				case BIOME -> "Biome";
				case HEIGHT -> "Height";
				case SURFACE -> "Surface";
				case FLUID -> "Fluid";
			};
			int tx = x + i * tabW;
			button(g, tx, y, tabW - 2, name, state.layer == l, () -> state.layer = l, l.displayName + " layer (" + (i + 1) + ")");
		}
		y += 18;

		// The bottom sections are laid out from the bottom up so lists can take the remaining space.
		int bottomHeight = bottomSectionsHeight();
		int listBottom = bottom - 4 - bottomHeight;

		switch (state.layer) {
			case BIOME -> y = biomeSection(g, x, y, w, listBottom);
			case SURFACE -> y = surfaceSection(g, x, y, w, listBottom);
			case HEIGHT -> y = heightSection(g, x, y, w);
			case FLUID -> y = fluidSection(g, x, y, w);
		}

		int by = Math.max(y + 4, listBottom + 4);
		if (state.tool.usesBrush()) {
			by = brushSection(g, x, by, w);
		}
		if (state.tool == Tools.STAMP) {
			by = templateSection(g, x, by, w);
		}
		if (state.selection != null) {
			selectionSection(g, x, by, w);
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

	private int sculpt3dSection(GuiGraphicsExtractor g, int x, int y, int w) {
		PainterState.Sculpt3DMode[] modes = PainterState.Sculpt3DMode.values();
		int bw = w / modes.length;
		for (int i = 0; i < modes.length; i++) {
			PainterState.Sculpt3DMode m = modes[i];
			String tip = switch (m) {
				case ADD -> "Add ground: grows out of the surface you point at (overhangs, arches, pillars). Shift carves.";
				case CARVE -> "Carve: digs into the surface you point at (caves, tunnels, holes). Hold to dig deeper. Shift adds.";
				case RESTORE -> "Remove 3D edits: back to what the 2D design makes there.";
				case ISLAND -> "Click the ground to place a floating island above it.";
			};
			button(g, x + i * bw, y, bw - 2, m.label, state.sculpt3dMode == m, () -> state.sculpt3dMode = m, tip);
		}
		y += 18;
		if (state.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
			y = slider(g, x, y, w, "Island size: " + state.islandSize + " blocks", (state.islandSize - 8) / 248.0,
					f -> state.islandSize = (int) Math.round(8 + f * 248), step -> state.islandSize = Math.clamp(state.islandSize + step * 4, 8, 256),
					"Width of the island");
			y = slider(g, x, y, w, "Height above ground: " + state.islandHeight, (state.islandHeight - 4) / 196.0,
					f -> state.islandHeight = (int) Math.round(4 + f * 196), step -> state.islandHeight = Math.clamp(state.islandHeight + step * 2, 4, 200),
					"How high the island's top floats above the block you click");
		} else {
			g.text(font, "Ball size: " + (Sculpt3DTool.radius(state) * 2 + 1) + " blocks", x, y + 1, GRAY, false);
			y += 12;
		}
		String text = layout == Layout.MAP
				? "Press F5 for the 3D view (the real world): you can point at cliffs, cave walls and the sky there. On the map this tool works on the top of the ground."
				: "Point at the ground, a cliff or a cave wall. The world changes right away; Save & Reload grows grass, trees and ores on it. Hold to keep going. Ball up to "
				+ (Sculpt3DTool.MAX_RADIUS * 2 + 1) + " blocks (brush size).";
		for (FormattedCharSequence line : font.split(Component.literal(text), w)) {
			g.text(font, line, x, y, DIM, false);
			y += 10;
		}
		return y;
	}

	private int structureSection(GuiGraphicsExtractor g, int x, int y, int w, int listBottom) {
		PainterState.StructureMode[] modes = PainterState.StructureMode.values();
		int bw = w / modes.length;
		for (int i = 0; i < modes.length; i++) {
			PainterState.StructureMode m = modes[i];
			String label = switch (m) {
				case ZONE -> "Zones";
				case EDIT -> "3D";
				default -> m.label;
			};
			String tip = switch (m) {
				case PLACE -> "Click to place the chosen structure (it generates there whatever the biome). Shift+click removes.";
				case REMOVE -> "Click a structure to remove it: placed ones, or ones already generated in the world. Click a red X to bring it back.";
				case ZONE -> "Drag a rectangle where no vanilla structure may generate. Shift+click a zone to delete it.";
				case EDIT -> "Click a structure (or any spot) to edit it in 3D: chest loot, spawners, stronghold eyes, ruined portals, blocks.";
			};
			button(g, x + i * bw, y, bw - 2, label, state.structureMode == m, () -> state.structureMode = m, tip);
		}
		y += 18;
		StructurePlan plan = state.world.structures();
		button(g, x, y, w, "Vanilla structures: " + (plan.vanillaStructures ? "On" : "Off"), !plan.vanillaStructures, () -> {
			state.session.begin("Toggle vanilla structures");
			state.session.editStructures(p -> p.vanillaStructures = !p.vanillaStructures);
			state.session.end();
			state.say(state.world.structures().vanillaStructures
					? "Vanilla structures generate normally again."
					: "No vanilla structures in new chunks - only the ones you place.");
		}, "Off: only structures you place generate (in chunks generated from now on)");
		y += 17;
		button(g, x, y, w, "Show structures on map: " + (state.showStructures ? "On" : "Off"), false,
				() -> state.showStructures = !state.showStructures, "Show placed, generated and removed structures and zones on the map");
		y += 17;
		g.text(font, "Placed " + plan.placed.size() + "   Removed " + plan.removed.size() + "   Zones " + plan.zones.size(), x, y, GRAY, false);
		y += 12;

		if (state.structureMode != PainterState.StructureMode.PLACE) {
			String text = switch (state.structureMode) {
				case REMOVE -> "Click a structure to remove it. Generated structures show when you zoom in (they are read from the world). Their chunks regenerate without them when the world loads.";
				case EDIT -> target.kind() == PainterTarget.Kind.RUNNING_WORLD
						? "Click a structure to open it in the 3D editor (your design is saved first). In the world you can also press K to edit what you look at. Structures you placed must be generated first: visit them once."
						: "The 3D editor works inside the world: open the world, press O and use this mode, or press K while looking at a structure.";
				default -> "Drag to draw a zone where vanilla structures can not start. Structures you place still generate inside. Shift+click a zone to delete it.";
			};
			for (FormattedCharSequence line : font.split(Component.literal(text), w)) {
				g.text(font, line, x, y, DIM, false);
				y += 10;
			}
			return y;
		}
		swatchLine(g, x, y, w, StructureInfo.color(state.structure), StructureInfo.pretty(state.structure));
		y += 13;
		placeBox(structureSearch, x, y, w);
		y += 17;
		String q = structureQuery.trim().toLowerCase();
		List<String> items = new ArrayList<>();
		for (String id : allStructures) {
			if (q.isEmpty() || id.contains(q) || StructureInfo.pretty(id).toLowerCase().contains(q)) {
				items.add(id);
			}
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		structureScroll = list(g, x, y, w, h, items, structureScroll, v -> structureScroll = v,
				id -> StructureInfo.pretty(id) + (StructureInfo.dimension(id) == target.dimension() ? "" : "  (" + StructureInfo.dimension(id).displayName + ")"),
				StructureInfo::color, id -> id.equals(state.structure), id -> state.structure = id);
		return y + h;
	}

	// ---- structures on the map ----

	private void drawStructures(GuiGraphicsExtractor g) {
		StructurePlan plan = state.world.structures();
		double bpg = view.blocksPerGuiPixel();
		int wx0 = (int) Math.floor(view.toWorldX(view.left)), wx1 = (int) Math.ceil(view.toWorldX(view.left + view.width));
		int wz0 = (int) Math.floor(view.toWorldZ(view.top)), wz1 = (int) Math.ceil(view.toWorldZ(view.top + view.height));

		for (StructurePlan.Zone z : plan.zones) {
			if (z.maxX() < wx0 || z.minX() > wx1 || z.maxZ() < wz0 || z.minZ() > wz1) {
				continue;
			}
			fillWorldRect(g, z.minX(), z.minZ(), z.maxX(), z.maxZ(), 0x30FF3030);
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
				double cx = es.chunkX() * 16 + 8, cz = es.chunkZ() * 16 + 8;
				dot(g, cx, cz, color);
				if (bpg <= 1.5) {
					label(g, cx, cz, StructureInfo.pretty(es.id()), color);
				}
			}
		}

		for (StructurePlan.Removed r : plan.removed) {
			double cx = r.chunkX() * 16 + 8, cz = r.chunkZ() * 16 + 8;
			if (cx >= wx0 - 16 && cx <= wx1 + 16 && cz >= wz0 - 16 && cz <= wz1 + 16) {
				redX(g, cx, cz);
			}
		}

		for (StructurePlan.Placed p : plan.placed) {
			double cx = p.chunkX() * 16 + 8, cz = p.chunkZ() * 16 + 8;
			if (cx < wx0 - 16 || cx > wx1 + 16 || cz < wz0 - 16 || cz > wz1 + 16) {
				continue;
			}
			if (bpg <= 1) {
				MapDraw.rect(g, view, p.chunkX() << 4, p.chunkZ() << 4, (p.chunkX() << 4) + 15, (p.chunkZ() << 4) + 15, 0xFF7FFF7F);
			}
			int gx = (int) Math.round(view.toGuiX(cx)), gy = (int) Math.round(view.toGuiY(cz));
			g.blit(RenderPipelines.GUI_TEXTURED, TOOLS_TEXTURE, gx - 8, gy - 8, STRUCTURE_ICON * 16f, 0f, 16, 16, ICON_SHEET_W, 16);
			if (bpg <= 4) {
				label(g, cx, cz, StructureInfo.pretty(p.structure()), 0xFF9CFF9C);
			}
		}
	}

	private void fillWorldRect(GuiGraphicsExtractor g, int minX, int minZ, int maxX, int maxZ, int color) {
		int x0 = (int) Math.max(view.left, Math.floor(view.toGuiX(minX)));
		int y0 = (int) Math.max(view.top, Math.floor(view.toGuiY(minZ)));
		int x1 = (int) Math.min(view.left + view.width, Math.ceil(view.toGuiX(maxX + 1.0)));
		int y1 = (int) Math.min(view.top + view.height, Math.ceil(view.toGuiY(maxZ + 1.0)));
		if (x1 > x0 && y1 > y0) {
			g.fill(x0, y0, x1, y1, color);
		}
	}

	private void dot(GuiGraphicsExtractor g, double wx, double wz, int color) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		g.fill(gx - 3, gy - 3, gx + 4, gy + 4, 0xFF101014);
		g.fill(gx - 2, gy - 2, gx + 3, gy + 3, color);
	}

	private void redX(GuiGraphicsExtractor g, double wx, double wz) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		for (int i = -4; i <= 4; i++) {
			g.fill(gx + i, gy + i, gx + i + 2, gy + i + 1, 0xFFFF3030);
			g.fill(gx + i, gy - i, gx + i + 2, gy - i + 1, 0xFFFF3030);
		}
	}

	private void label(GuiGraphicsExtractor g, double wx, double wz, String text, int color) {
		int gx = (int) Math.round(view.toGuiX(wx)), gy = (int) Math.round(view.toGuiY(wz));
		g.text(font, text, gx + 10, gy - 4, color, true);
	}

	private int biomeSection(GuiGraphicsExtractor g, int x, int y, int w, int listBottom) {
		swatchLine(g, x, y, w, BiomeColors.color(state.biome), BiomeColors.pretty(state.biome));
		y += 13;
		placeBox(biomeSearch, x, y, w);
		y += 17;
		String q = biomeQuery.trim().toLowerCase();
		List<String> items = new ArrayList<>();
		for (String id : allBiomes) {
			if (q.isEmpty() || id.contains(q) || BiomeColors.pretty(id).toLowerCase().contains(q)) {
				items.add(id);
			}
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		biomeScroll = list(g, x, y, w, h, items, biomeScroll, v -> biomeScroll = v,
				id -> BiomeColors.pretty(id) + (target.dimension().displayName.equals(BiomeColors.dimension(id)) ? "" : "  (" + BiomeColors.dimension(id) + ")"),
				BiomeColors::color, id -> id.equals(state.biome), id -> {
					state.biome = id;
					if (state.tool == Tools.PICK || state.tool == Tools.SELECT) {
						state.tool = Tools.BRUSH;
					}
				});
		return y + h;
	}

	private int surfaceSection(GuiGraphicsExtractor g, int x, int y, int w, int listBottom) {
		swatchLine(g, x, y, w, SurfaceColors.color(state.surface), state.surface.replace("minecraft:", ""));
		y += 13;
		placeBox(blockSearch, x, y, w);
		y += 17;
		if (!blockQuery.equals(blockResultsQuery)) {
			blockResultsQuery = blockQuery;
			blockResults = SurfaceColors.search(blockQuery, 300);
		}
		int h = Math.max(ROW_H * 3, listBottom - y);
		blockScroll = list(g, x, y, w, h, blockResults, blockScroll, v -> blockScroll = v, id -> id.replace("minecraft:", ""),
				SurfaceColors::color, id -> id.equals(state.surface), id -> state.surface = id);
		return y + h;
	}

	private int heightSection(GuiGraphicsExtractor g, int x, int y, int w) {
		y = slider(g, x, y, w, "Paint height: Y " + state.height, (state.height - PainterState.MIN_Y) / 383.0,
				f -> state.height = (int) Math.round(PainterState.MIN_Y + f * 383), step -> state.height = Math.clamp(state.height + step, PainterState.MIN_Y, PainterState.MAX_Y),
				"Height used by the Brush and Rectangle tools on the height layer (sea level is 63)");
		y = slider(g, x, y, w, "Base height: Y " + state.baseHeight, (state.baseHeight - PainterState.MIN_Y) / 383.0,
				f -> state.baseHeight = (int) Math.round(PainterState.MIN_Y + f * 383), step -> state.baseHeight = Math.clamp(state.baseHeight + step, PainterState.MIN_Y, PainterState.MAX_Y),
				"Where sculpting starts on land that is not painted and not known from an existing world");
		g.text(font, "Sculpt mode", x, y + 1, GRAY, false);
		y += 11;
		PainterState.HeightMode[] modes = PainterState.HeightMode.values();
		int bw = w / 3;
		for (int i = 0; i < modes.length; i++) {
			PainterState.HeightMode m = modes[i];
			button(g, x + (i % 3) * bw, y + (i / 3) * 17, bw - 2, m.label, state.heightMode == m, () -> {
				state.heightMode = m;
				state.tool = Tools.SCULPT;
			}, "Sculpt tool: " + m.label.toLowerCase());
		}
		y += 2 * 17 + 2;
		g.text(font, "Below Y 62 unpainted water fills in", x, y, DIM, false);
		return y + 11;
	}

	private int fluidSection(GuiGraphicsExtractor g, int x, int y, int w) {
		FluidType[] types = FluidType.values();
		int bw = w / 3;
		for (int i = 0; i < types.length; i++) {
			FluidType t = types[i];
			String name = switch (t) {
				case WATER -> "Water";
				case LAVA -> "Lava";
				case DRY -> "Dry";
			};
			button(g, x + i * bw, y, bw - 2, name, state.fluidType == t, () -> state.fluidType = t,
					t == FluidType.DRY ? "Removes water/lava above the ground (e.g. dry land below sea level)" : name + " up to the level below");
		}
		y += 18;
		if (state.fluidType != FluidType.DRY) {
			y = slider(g, x, y, w, "Surface level: Y " + state.fluidLevel, (state.fluidLevel - PainterState.MIN_Y) / 383.0,
					f -> state.fluidLevel = (int) Math.round(PainterState.MIN_Y + f * 383), step -> state.fluidLevel = Math.clamp(state.fluidLevel + step, PainterState.MIN_Y, PainterState.MAX_Y),
					"The top water/lava block. The sea is at Y 62.");
		}
		g.text(font, "Fills from the ground up to the level", x, y, DIM, false);
		return y + 11;
	}

	private int brushSection(GuiGraphicsExtractor g, int x, int y, int w) {
		Brush b = state.brush;
		g.text(font, "Brush", x, y + 1, GRAY, false);
		button(g, x + w - 2 * 44, y - 1, 42, "Circle", !b.square, () -> b.square = false, "Round brush");
		button(g, x + w - 44, y - 1, 42, "Square", b.square, () -> b.square = true, "Square brush");
		y += 14;
		y = slider(g, x, y, w, "Size: " + (b.radius * 2 + 1) + " blocks", Math.log(b.radius + 1) / Math.log(Brush.MAX_RADIUS + 1),
				f -> b.radius = Math.clamp(Math.round(Math.pow(Brush.MAX_RADIUS + 1, f)) - 1, 0, Brush.MAX_RADIUS),
				step -> b.radius = Math.clamp(b.radius + step * Math.max(1, b.radius / 8), 0, Brush.MAX_RADIUS),
				"Brush diameter in blocks (Ctrl + mouse wheel or [ ])");
		y = slider(g, x, y, w, "Hardness: " + Math.round(b.hardness * 100) + "%", b.hardness,
				f -> b.hardness = (float) f, step -> b.hardness = Math.clamp(b.hardness + step * 0.05f, 0f, 1f),
				"Soft brushes fade out towards the edge (height tools)");
		y = slider(g, x, y, w, "Strength: " + b.strength, (b.strength - 1) / 63.0,
				f -> b.strength = 1 + (int) Math.round(f * 63), step -> b.strength = Math.clamp(b.strength + step, 1, 64),
				"How fast height tools work / how far smoothing looks");
		return y + 2;
	}

	private int templateSection(GuiGraphicsExtractor g, int x, int y, int w) {
		g.text(font, "Templates (Q rotates: " + state.templateRotation * 90 + "°)", x, y + 1, GRAY, false);
		y += 12;
		int h = 6 * ROW_H;
		templateScroll = list(g, x, y, w, h, templates, templateScroll, v -> templateScroll = v,
				t -> t.name() + (t.resizable() ? "" : " *"),
				t -> t.resizable() ? 0xFF6FA0D8 : 0xFFD8B06F,
				t -> t == state.template, t -> state.template = t);
		y += h + 4;
		Template t = state.template;
		if (t != null && t.resizable()) {
			y = slider(g, x, y, w, "Size: " + state.templateSize + " blocks",
					(state.templateSize - ProceduralTemplates.MIN_SIZE) / (double) (ProceduralTemplates.MAX_SIZE - ProceduralTemplates.MIN_SIZE),
					f -> state.templateSize = (int) Math.round(ProceduralTemplates.MIN_SIZE + f * (ProceduralTemplates.MAX_SIZE - ProceduralTemplates.MIN_SIZE)),
					step -> state.templateSize = Math.clamp(state.templateSize + step * 32, ProceduralTemplates.MIN_SIZE, ProceduralTemplates.MAX_SIZE),
					"Width of the generated area");
		} else if (t != null) {
			button(g, x, y, w, "Delete this saved template", false, this::deleteTemplate, "Deletes the template file (click twice)");
			y += 17;
		}
		if (t != null) {
			g.text(font, fit(t.description(), w), x, y, DIM, false);
		}
		return y + 12 + 6;
	}

	private void selectionSection(GuiGraphicsExtractor g, int x, int y, int w) {
		int[] s = state.selection;
		g.text(font, "Selection " + (s[2] - s[0] + 1) + " x " + (s[3] - s[1] + 1), x, y + 1, GRAY, false);
		y += 12;
		int bw = (w - 2) / 2;
		button(g, x, y, bw, "Fill", false, () -> {
			state.session.begin("Fill selection");
			Ops.fillRect(state.session, s[0], s[1], s[2], s[3], state.currentValue());
			state.session.end();
			state.say("Filled the selection");
		}, "Fill the selection with the chosen " + state.layer.displayName.toLowerCase());
		button(g, x + bw + 2, y, bw, "Erase", false, () -> {
			state.session.begin("Erase selection");
			Ops.clearRect(state.session, s[0], s[1], s[2], s[3], state.layer);
			state.session.end();
			state.say("Erased the " + state.layer.displayName.toLowerCase() + " layer in the selection");
		}, "Erase the chosen layer inside the selection");
		y += 18;
		int bw3 = (w - 4) / 3;
		button(g, x, y, bw3, "Smooth", false, () -> smoothSelection(s), "Smooth borders / heights inside the selection");
		button(g, x + bw3 + 2, y, bw3, "Clear 3D", false, () -> {
			state.session.begin("Clear 3D edits");
			VolumeOps.clearRect(state.session, s[0], s[1], s[2], s[3]);
			state.session.end();
			state.say("Removed the 3D edits in the selection");
		}, "Remove caves, overhangs and floating islands sculpted in 3D inside the selection");
		button(g, x + 2 * (bw3 + 2), y, w - 2 * (bw3 + 2), "Deselect", false, () -> state.selection = null, "Clear the selection");
		y += 18;
		if (target.existingWorld()) {
			button(g, x, y, w, "Regenerate these chunks", false, () -> {
				state.session.regenChunks(s[0] >> 4, s[1] >> 4, s[2] >> 4, s[3] >> 4);
				state.say(live() && target.kind() == PainterTarget.Kind.RUNNING_WORLD
						? "The selected chunks regenerate in a moment (live changes)."
						: "The selected chunks regenerate the next time the world loads (after you save).");
			}, "Generate the selected area again with the current design next time the world loads (a backup is kept)");
			y += 18;
		}
		placeBox(templateName, x, y, w);
		y += 17;
		button(g, x, y, w, "Save as template", false, () -> saveSelectionAsTemplate(s), "Save the painted content of the selection so you can stamp it anywhere");
	}

	private void smoothSelection(int[] s) {
		long area = (s[2] - s[0] + 1L) * (s[3] - s[1] + 1L);
		if (area > 16_000_000L) {
			state.say("Selection too large to smooth at once (max 4000 x 4000).");
			return;
		}
		int k = Math.clamp(1 + state.brush.strength / 6, 1, 10);
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
			TemplateLibrary.save(minecraft.gameDirectory.toPath(), name, data);
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
		state.template = ProceduralTemplates.ALL.getFirst();
		state.say("Template deleted");
	}

	// ---- small custom widgets ----

	private void placeBox(EditBox box, int x, int y, int w) {
		box.setX(x);
		box.setY(y);
		box.setWidth(w);
		box.visible = true;
	}

	private void swatchLine(GuiGraphicsExtractor g, int x, int y, int w, int color, String text) {
		g.fill(x, y, x + 10, y + 10, color);
		g.outline(x, y, 10, 10, 0xFF000000);
		g.text(font, fit(text, w - 14), x + 14, y + 1, WHITE, false);
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, String text, boolean selected, Runnable action, String tip) {
		if (w <= 0) {
			return;
		}
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 15;
		g.fill(x, y, x + w, y + 15, selected ? ACCENT : hover ? 0xFF3A3A48 : 0xFF2A2A34);
		String t = fit(text, w - 4);
		g.text(font, t, x + (w - font.width(t)) / 2, y + 4, WHITE, false);
		hits.add(x, y, w, 15, (mx, my, b) -> action.run(), tip);
	}

	/** Draws a slider and returns the y below it. */
	private int slider(GuiGraphicsExtractor g, int x, int y, int w, String text, double fraction,
					   DoubleConsumer onSet, IntConsumer onStep, String tip) {
		double f = Math.clamp(fraction, 0.0, 1.0);
		g.fill(x, y, x + w, y + 14, 0xFF26262F);
		g.fill(x, y, x + (int) Math.round(w * f), y + 14, 0xFF34507A);
		g.outline(x, y, w, 14, 0xFF3A3A48);
		g.text(font, fit(text, w - 6), x + 4, y + 3, WHITE, false);
		hits.add(x, y, w, 14, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				onSet.accept(Math.clamp((mx - x) / w, 0.0, 1.0));
			}

			@Override
			public void drag(double mx, double my) {
				onSet.accept(Math.clamp((mx - x) / w, 0.0, 1.0));
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
	private <T> int list(GuiGraphicsExtractor g, int x, int y, int w, int h, List<T> items, int scroll, IntConsumer setScroll,
						 Function<T, String> label, ToIntFunction<T> color, Predicate<T> selected, Consumer<T> onPick) {
		int visible = Math.max(1, h / ROW_H);
		int maxScroll = Math.max(0, items.size() - visible);
		int sc = Math.clamp(scroll, 0, maxScroll);
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
			g.text(font, fit(label.apply(item), w - 18), x + 13, ry + 2, sel ? WHITE : 0xFFD0D0D8, false);
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
				setScroll.accept(Math.clamp(sc - (amount > 0 ? 3 : -3), 0, maxScroll));
				return true;
			}
		});
		return sc;
	}

	private String fit(String s, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(s) <= maxWidth) {
			return s;
		}
		String ell = "...";
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end) + ell) > maxWidth) {
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

	private boolean editBoxFocused() {
		return getFocused() instanceof EditBox box && box.isFocused() && box.visible;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		if (showHelp) {
			showHelp = false;
			return true;
		}
		if (showSettings) {
			// Clicks inside the panel use its buttons; a click anywhere else closes it.
			if (mx >= settingsX && mx < settingsX + settingsW && my >= settingsY && my < settingsY + settingsH) {
				hits.click(mx, my, button);
			} else {
				showSettings = false;
			}
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		setFocused(null);
		if (hits.click(mx, my, button)) {
			return true;
		}
		if (worldShown()) {
			return inWorldViewport(mx, my) && worldClicked(event, mx, my, button);
		}
		if (view.contains(mx, my)) {
			if (button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE
					|| (button == InputConstants.MOUSE_BUTTON_LEFT && spaceDown)) {
				panning = true;
				return true;
			}
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				painting = true;
				state.tool.press(state, blockX(mx), blockZ(my), event.hasShiftDown());
				return true;
			}
		}
		return false;
	}

	private boolean worldClicked(MouseButtonEvent event, double mx, double my, int button) {
		if (world3d.camera() == null) {
			return true;
		}
		if (button == InputConstants.MOUSE_BUTTON_RIGHT || button == InputConstants.MOUSE_BUTTON_MIDDLE
				|| (button == InputConstants.MOUSE_BUTTON_LEFT && spaceDown)) {
			if (event.hasShiftDown()) {
				panning3d = true;
			} else {
				orbiting3d = true;
			}
			return true;
		}
		if (button != InputConstants.MOUSE_BUTTON_LEFT) {
			return false;
		}
		Hit3D hit = toHit(world3d.pick(minecraft, mx, my));
		if (hit == null) {
			state.say("Point at the ground to use the tool (right or middle drag turns the view).");
			return true;
		}
		painting3d = true;
		lastPaintHit = hit;
		state.blocksPerGuiPixel = world3d.blocksPerPixelAt(hit.x() + 0.5, hit.y() + 0.5, hit.z() + 0.5, height);
		if (state.tool instanceof Tool3D t3) {
			t3.press3d(state, hit, event.hasShiftDown());
		} else {
			state.tool.press(state, hit.x(), hit.z(), event.hasShiftDown());
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		OrbitCamera cam3d = world3d == null ? null : world3d.camera();
		if (orbiting3d && cam3d != null) {
			if (event.hasControlDown()) {
				cam3d.zoom(-dy * 0.05);
			} else {
				cam3d.orbit(dx, dy);
			}
			return true;
		}
		if (panning3d && cam3d != null) {
			cam3d.pan(dx, dy, height);
			return true;
		}
		if (painting3d) {
			Hit3D hit = toHit(world3d.pick(minecraft, event.x(), event.y()));
			if (hit != null) {
				lastPaintHit = hit;
			}
			if (state.tool instanceof Tool3D t3) {
				t3.drag3d(state, hit, event.hasShiftDown());
			} else if (hit != null) {
				state.tool.drag(state, hit.x(), hit.z(), event.hasShiftDown());
			}
			return true;
		}
		if (panning) {
			view.panGui(dx, dy);
			return true;
		}
		if (hits.drag(event.x(), event.y())) {
			return true;
		}
		if (painting) {
			state.tool.drag(state, blockX(event.x()), blockZ(event.y()), event.hasShiftDown());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		boolean handled = hits.release();
		if (orbiting3d || panning3d) {
			orbiting3d = false;
			panning3d = false;
			return true;
		}
		if (painting3d && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			painting3d = false;
			if (state.tool instanceof Tool3D t3) {
				t3.release3d(state, event.hasShiftDown());
			} else if (lastPaintHit != null) {
				state.tool.release(state, lastPaintHit.x(), lastPaintHit.z(), event.hasShiftDown());
			}
			return true;
		}
		if (panning) {
			panning = false;
			return true;
		}
		if (painting && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			painting = false;
			state.tool.release(state, blockX(event.x()), blockZ(event.y()), event.hasShiftDown());
			return true;
		}
		return super.mouseReleased(event) || handled;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (showHelp || showSettings || scrollY == 0) {
			return true;
		}
		if (hits.scroll(mx, my, scrollY)) {
			return true;
		}
		if (worldShown()) {
			if (inWorldViewport(mx, my) && world3d.camera() != null) {
				if (minecraft.hasControlDown()) {
					resizeBrush(scrollY > 0 ? 1 : -1);
				} else {
					world3d.camera().zoom(scrollY > 0 ? 1 : -1);
				}
				return true;
			}
			return super.mouseScrolled(mx, my, scrollX, scrollY);
		}
		if (view.contains(mx, my)) {
			if (minecraft.hasControlDown()) {
				resizeBrush(scrollY > 0 ? 1 : -1);
			} else {
				view.zoomAt(mx, my, scrollY > 0 ? -1 : 1);
			}
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
	}

	private void resizeBrush(int dir) {
		Brush b = state.brush;
		int step = Math.max(1, b.radius / 6);
		b.radius = Math.clamp(b.radius + dir * step, 0, Brush.MAX_RADIUS);
		state.say("Brush size " + (b.radius * 2 + 1));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (editBoxFocused()) {
			if (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_RETURN) {
				setFocused(null);
				return true;
			}
			return super.keyPressed(event);
		}
		if (showHelp && (key == InputConstants.KEY_ESCAPE || key == InputConstants.KEY_F1)) {
			showHelp = false;
			return true;
		}
		if (showSettings && key == InputConstants.KEY_ESCAPE) {
			showSettings = false;
			return true;
		}
		if (event.hasControlDown()) {
			if (key == InputConstants.KEY_Z) {
				state.say(event.hasShiftDown() ? label("Redid", state.session.redo()) : label("Undid", state.session.undo()));
				return true;
			}
			if (key == InputConstants.KEY_Y) {
				state.say(label("Redid", state.session.redo()));
				return true;
			}
			if (key == InputConstants.KEY_S) {
				save();
				return true;
			}
		}
		for (Tool tool : Tools.ALL) {
			if (key == keyFor(tool.hotkey()) && !event.hasControlDown()) {
				selectTool(tool);
				return true;
			}
		}
		if (key == InputConstants.KEY_1 || key == InputConstants.KEY_2 || key == InputConstants.KEY_3 || key == InputConstants.KEY_4) {
			int idx = key == InputConstants.KEY_1 ? 0 : key == InputConstants.KEY_2 ? 1 : key == InputConstants.KEY_3 ? 2 : 3;
			List<Layer> layers = state.layers();
			if (idx < layers.size()) {
				state.layer = layers.get(idx);
			}
			return true;
		}
		if (key == InputConstants.KEY_Q) {
			state.templateRotation = (state.templateRotation + 1) & 3;
			return true;
		}
		if (key == InputConstants.KEY_LBRACKET) {
			resizeBrush(-1);
			return true;
		}
		if (key == InputConstants.KEY_RBRACKET) {
			resizeBrush(1);
			return true;
		}
		if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
			view.zoomAt(view.left + view.width / 2.0, view.top + view.height / 2.0, -1);
			return true;
		}
		if (key == InputConstants.KEY_MINUS) {
			view.zoomAt(view.left + view.width / 2.0, view.top + view.height / 2.0, 1);
			return true;
		}
		if (key == InputConstants.KEY_F1) {
			showHelp = !showHelp;
			return true;
		}
		if (key == InputConstants.KEY_F5) {
			cycleLayout();
			return true;
		}
		if (key == InputConstants.KEY_F && hover3d != null && world3d.camera() != null) {
			world3d.camera().focus(hover3d.x() + 0.5, hover3d.y() + 0.5, hover3d.z() + 0.5);
			return true;
		}
		if (key == InputConstants.KEY_HOME && worldShown()) {
			world3d.flyTo(minecraft, 0, 0);
			return true;
		}
		if (key == InputConstants.KEY_HOME) {
			view.centerX = 0;
			view.centerZ = 0;
			return true;
		}
		if (key == InputConstants.KEY_SPACE) {
			spaceDown = true;
			return true;
		}
		if (isPanKey(key)) {
			heldKeys.add(key);
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		int key = event.key();
		heldKeys.remove(key);
		if (key == InputConstants.KEY_SPACE) {
			spaceDown = false;
		}
		return super.keyReleased(event);
	}

	private static int keyFor(char c) {
		return switch (c) {
			case 'B' -> InputConstants.KEY_B;
			case 'E' -> InputConstants.KEY_E;
			case 'H' -> InputConstants.KEY_H;
			case 'M' -> InputConstants.KEY_M;
			case 'G' -> InputConstants.KEY_G;
			case 'R' -> InputConstants.KEY_R;
			case 'I' -> InputConstants.KEY_I;
			case 'L' -> InputConstants.KEY_L;
			case 'T' -> InputConstants.KEY_T;
			case 'P' -> InputConstants.KEY_P;
			case 'C' -> InputConstants.KEY_C;
			default -> -1;
		};
	}

	private static boolean isPanKey(int key) {
		return key == InputConstants.KEY_W || key == InputConstants.KEY_A || key == InputConstants.KEY_S || key == InputConstants.KEY_D
				|| key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN || key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT;
	}

	private void applyKeyboardPan() {
		long now = System.nanoTime();
		double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
		lastFrameNanos = now;
		if (heldKeys.isEmpty() || editBoxFocused()) {
			return;
		}
		if (worldShown()) {
			flyWithKeys(dt);
			return;
		}
		double speed = 500 * dt * (minecraft.hasShiftDown() ? 3 : 1);
		double dx = 0, dy = 0;
		if (heldKeys.contains(InputConstants.KEY_W) || heldKeys.contains(InputConstants.KEY_UP)) {
			dy += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_S) || heldKeys.contains(InputConstants.KEY_DOWN)) {
			dy -= speed;
		}
		if (heldKeys.contains(InputConstants.KEY_A) || heldKeys.contains(InputConstants.KEY_LEFT)) {
			dx += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_D) || heldKeys.contains(InputConstants.KEY_RIGHT)) {
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
		double speed = Math.max(4, c.distance * 0.8) * dt * (minecraft.hasShiftDown() ? 3 : 1);
		double f = 0, r = 0;
		if (heldKeys.contains(InputConstants.KEY_W) || heldKeys.contains(InputConstants.KEY_UP)) {
			f += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_S) || heldKeys.contains(InputConstants.KEY_DOWN)) {
			f -= speed;
		}
		if (heldKeys.contains(InputConstants.KEY_D) || heldKeys.contains(InputConstants.KEY_RIGHT)) {
			r += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_A) || heldKeys.contains(InputConstants.KEY_LEFT)) {
			r -= speed;
		}
		if (f != 0 || r != 0) {
			c.fly(f, r, 0);
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (live()) {
			tickLive();
		}
		if (painting && !worldShown() && view.contains(mouseX, mouseY)) {
			state.tool.hold(state, blockX(mouseX), blockZ(mouseY), minecraft.hasShiftDown());
		}
		if (world3d != null) {
			world3d.tick(minecraft);
		}
		if (painting3d && worldShown()) {
			Hit3D hit = toHit(world3d.pick(minecraft, mouseX, mouseY));
			if (hit != null) {
				lastPaintHit = hit;
			}
			if (state.tool instanceof Tool3D t3) {
				t3.hold3d(state, hit, minecraft.hasShiftDown());
			} else if (hit != null) {
				state.tool.hold(state, hit.x(), hit.z(), minecraft.hasShiftDown());
			}
		}
	}

	// ------------------------------------------------------------------ live changes and settings

	/** Live changes (the setting): the design saves itself and the running world follows it. */
	private boolean live() {
		return Settings.liveChanges();
	}

	/**
	 * Every tick with live changes on: saves the design a moment after the last change (not in the
	 * middle of a stroke), and keeps the regeneration going while the game is paused (the 2D map
	 * pauses it; the server then does not tick).
	 */
	private void tickLive() {
		long now = System.currentTimeMillis();
		int count = state.session.changeCount();
		if (count != seenChangeCount) {
			seenChangeCount = count;
			lastChangeMillis = now;
		}
		if (state.world.hasUnsavedChanges() && !painting && !painting3d && now - lastChangeMillis >= 400) {
			if (!liveCommit()) {
				// Saving failed (message shown): try again in a while rather than every tick.
				lastChangeMillis = now + 5000;
			}
		}
	}

	/** Live changes in the world you are playing: the world follows the design as you paint. */
	private boolean liveInWorld() {
		return live() && target.kind() == PainterTarget.Kind.RUNNING_WORLD && minecraft.getSingleplayerServer() != null;
	}

	/** The world generates with this painter's design as it is painted ({@code false}: with the saved design again). */
	private void useLiveDesign(boolean on) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || target.kind() != PainterTarget.Kind.RUNNING_WORLD || on == liveDesignBound) {
			return;
		}
		liveDesignBound = on;
		PaintDimension dimension = target.dimension();
		PaintWorld design = on ? state.world : null;
		server.execute(() -> WorldPainter.useLiveDesign(server, dimension, design));
	}

	/**
	 * Every frame with live changes in the world you are playing: the chunks just painted go to the
	 * server right away (no need to wait for a save, the world generates with this design), and the
	 * server regenerates them, the ones nearest to where you look first. On the map the game is
	 * paused, so the server has time for it.
	 */
	private void liveFrame() {
		if (!liveInWorld()) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		useLiveDesign(true);
		ResourceKey<Level> key = Dimensions.key(target.dimension());
		LiveRegen.setFocus(key, view.centerX, view.centerZ);
		DirtyChunks changed = state.world.takeLiveChanges();
		if (changed != null) {
			server.execute(() -> LiveRegen.request(server, key, changed));
		}
		if (liveWorkQueued.compareAndSet(false, true)) {
			long budget = isPauseScreen() ? LIVE_PAUSED_BUDGET : LIVE_PLAYING_BUDGET;
			server.execute(() -> {
				try {
					LiveRegen.work(server, budget);
				} finally {
					liveWorkQueued.set(false);
				}
			});
		}
	}

	/**
	 * Saves the design and, in the running world, has the chunks it changed regenerate there right away
	 * (loaded ones in a moment, the others when you get near them).
	 */
	private boolean liveCommit() {
		state.session.end();
		try {
			state.world.save(MapColors::summarize, target.existingWorld());
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not save World Painter design", e);
			state.say("Could not save: " + e.getMessage());
			return false;
		}
		switch (target.kind()) {
			case NEW_WORLD -> DraftSession.arm();
			case SAVED_WORLD -> {
				// Painted chunks that already exist regenerate when the world is opened.
			}
			case RUNNING_WORLD -> {
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (server != null) {
					ResourceKey<Level> key = Dimensions.key(target.dimension());
					// Usually handed over while painting already (see liveFrame).
					DirtyChunks fresh = state.world.takeLiveChanges();
					boolean bound = liveDesignBound;
					server.execute(() -> {
						if (!bound) {
							// Not generating with the painter's design: use the one just saved.
							WorldPainter.rebind(server);
						}
						if (fresh != null) {
							LiveRegen.request(server, key, fresh);
						}
						LiveRegen.afterSave(server, key);
					});
				}
			}
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
		Settings.setLiveChanges(on);
		if (!on) {
			// The world generates with the saved design again.
			useLiveDesign(false);
		}
		// The top bar changes: Done instead of the Save buttons, or the other way round.
		rebuildWidgets();
		if (on) {
			seenChangeCount = -1;
			state.say(target.kind() == PainterTarget.Kind.RUNNING_WORLD
					? "Live changes on: what you paint changes the world right away."
					: "Live changes on: your design saves itself.");
		} else {
			state.say("Live changes off: press Save to keep your changes, Save & Reload to see them in visited land.");
		}
	}

	private void drawSettings(GuiGraphicsExtractor g) {
		boolean on = Settings.liveChanges();
		int w = Math.min(360, width - 40);
		int textW = w - 20;
		List<String> about = new ArrayList<>();
		if (on) {
			about.addAll(wrap("On: everything you paint changes the world right away. The world generates with your design as"
					+ " you paint it: painted chunks near you regenerate while you paint (the ones you look at first), and"
					+ " painted chunks further away regenerate when you get near them. The design saves itself. There are no"
					+ " Save buttons: press Done (or Esc) to close.", textW));
			about.add("");
			about.addAll(wrap("Builds in painted chunks are replaced by what the design generates there, without a backup."
					+ " Undo regenerates the chunks again.", textW));
		} else {
			about.addAll(wrap("Off: changes stay in the design until you press Save. Land that already exists regenerates"
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
		g.text(font, "Settings", x + 10, y + 8, 0xFFFFD040, false);
		int ty = y + 24;
		button(g, x + 10, ty, w - 20, "Live changes: " + (on ? "On" : "Off"), on, this::toggleLive,
				"Click to turn live changes " + (on ? "off" : "on"));
		ty += 20;
		for (String line : about) {
			g.text(font, line, x + 10, ty, GRAY, false);
			ty += 11;
		}
		button(g, x + w - 70, y + h - 22, 60, "Done", false, () -> showSettings = false, "Close the settings");
	}

	/** Splits text into lines that fit a width. */
	private List<String> wrap(String text, int w) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (font.width(next) > w && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
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
			case NEW_WORLD -> {
				DraftSession.arm();
				state.say("Saved. The design is used when you create the world.");
			}
			case SAVED_WORLD -> state.say("Saved. Painted chunks that already exist regenerate when the world is opened (with a backup).");
			case RUNNING_WORLD -> {
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (server != null) {
					server.execute(() -> WorldPainter.rebind(server));
				}
				state.say("Saved. New chunks use it now; visited painted chunks regenerate after Save & Reload.");
			}
		}
		return true;
	}

	/** Leaves the painter (saving first) and opens the 3D structure editor at a map position. */
	private void openStructureEditor(int x, int z) {
		if (target.kind() != PainterTarget.Kind.RUNNING_WORLD || minecraft.getSingleplayerServer() == null) {
			state.say("The 3D editor works inside the world: open the world, press O, then click here again.");
			return;
		}
		if (playerDimension() != target.dimension()) {
			state.say("The 3D editor works in the dimension you are in: go to the " + target.dimension().displayName + " first.");
			return;
		}
		if (state.world.hasUnsavedChanges() && !save()) {
			return;
		}
		Minecraft mc = minecraft;
		closeScreen();
		StructureEditorLauncher.openAtColumn(mc, x, z);
	}

	private void saveAndReload() {
		if (!save()) {
			return;
		}
		OrbitCamera c = worldShown() ? world3d.camera() : null;
		if (c != null) {
			// Come back to the same 3D view once the world is open again.
			PainterResume.set(new PainterResume.State(target.levelId(), target.dimension(), c.pivotX, c.pivotY, c.pivotZ,
					c.yaw, c.pitch, c.distance, view.zoom, System.currentTimeMillis()));
		}
		String levelId = target.levelId();
		Minecraft mc = minecraft;
		closeScreen();
		mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
		mc.createWorldOpenFlows().openWorld(levelId, () -> mc.gui.setScreen(new TitleScreen()));
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
			state.say("The " + target.dimension().displayName + " design was cleared (other dimensions are kept).");
		} catch (IOException e) {
			state.say("Could not clear: " + e.getMessage());
		}
	}

	@Override
	public void onClose() {
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
		minecraft.gui.setScreen(target.parent());
	}

	@Override
	public void removed() {
		super.removed();
		if (cleanedUp) {
			return;
		}
		cleanedUp = true;
		if (!discarded && state.world.hasUnsavedChanges()) {
			// Left some other way (e.g. disconnected): keep the work.
			save();
		}
		// The world generates with the saved design again (everything was saved just above).
		useLiveDesign(false);
		renderer.close();
		state.liveSculpt = null;
		if (world3d != null) {
			world3d.exit(minecraft);
		}
		if (state.existing != null) {
			state.existing.close();
		}
	}
}
