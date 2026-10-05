package com.daniel.worldpainter.client.screen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.Icons;
import com.daniel.worldpainter.client.editor.BlockRay;
import com.daniel.worldpainter.client.editor.OrbitCamera;
import com.daniel.worldpainter.client.editor.Projection;
import com.daniel.worldpainter.client.map.StructureInfo;
import com.daniel.worldpainter.client.map.SurfaceColors;
import com.daniel.worldpainter.editor.EditorOps;
import com.daniel.worldpainter.editor.EditorSessions;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * The 3D structure editor: the real world, seen through a 3D-software style camera (orbit, pan,
 * zoom around a pivot) with a free mouse cursor. Blocks can be selected, placed, broken and picked,
 * and the side panel has quick edits for chests, spawners, stronghold eyes and ruined portals.
 */
public final class StructureEditorScreen extends Screen {
	private static final int TOP = 22;
	private static final int TOOLBAR_W = 26;
	private static final int PANEL_W = 184;
	private static final int ROW_H = 11;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A8;
	private static final int DIM = 0xFF70707A;
	private static final int ACCENT = 0xFF3E6FB0;
	private static final int PANEL_BG = 0xE018181E;

	private enum EditTool {
		SELECT("Select", 'V', Icons.SELECT, "Click a block to select it (chests, spawners, portal frames show their options)."),
		PLACE("Place", 'B', Icons.PLACE, "Click a block face to place the chosen block there."),
		BREAK("Break", 'X', Icons.BREAK, "Click a block to remove it."),
		PICK("Pick", 'I', Icons.PICK, "Click a block to use it for placing (Alt+click works with any tool).");

		final String label;
		final char key;
		final int icon;
		final String help;

		EditTool(String label, char key, int icon, String help) {
			this.label = label;
			this.key = key;
			this.icon = icon;
			this.help = help;
		}
	}

	private enum SearchMode { NONE, BLOCKS, ITEMS, MOBS }

	/** Everything the panel shows, computed on the server thread. */
	private record Snapshot(EditorOps.FoundStructure structure, String blockId, EditorOps.ContainerView container,
							String spawnerMob, EditorOps.EndPortalInfo endPortal, EditorOps.PortalInfo portal) {
	}

	private final OrbitCamera cam;
	private final Projection proj = new Projection();
	private final Hits hits = new Hits();
	private final UUID playerId;
	private final ResourceKey<Level> dimension;
	private final CameraType oldCameraType;
	private boolean hidHud;

	private EditTool tool = EditTool.SELECT;
	private String placeBlock = "minecraft:stone";
	private BlockRay.Hit hover;
	private BlockPos selected;
	private Snapshot info;
	private int selectedSlot = -1;
	private List<String> lootTables = List.of();
	private int lootIndex;
	private List<String> mobs;
	private List<String> items;
	private final Map<String, ItemStack> iconCache = new HashMap<>();

	private final Deque<List<EditorOps.BlockChange>> undo = new ArrayDeque<>();
	private final Deque<List<EditorOps.BlockChange>> redo = new ArrayDeque<>();

	private EditBox search;
	private SearchMode searchMode = SearchMode.NONE;
	private String query = "";
	private int listScroll;
	private List<String> blockResults = List.of();
	private String blockResultsQuery;

	private boolean orbiting;
	private boolean panning;
	private double mouseX, mouseY;
	private final Set<Integer> heldKeys = new HashSet<>();
	private long lastNanos;
	private boolean showHelp;
	private boolean returnToStart = true;
	private boolean ended;
	private String message;
	private long messageUntil;

	public StructureEditorScreen(OrbitCamera cam) {
		super(Component.literal("Structure Editor"));
		Minecraft mc = Minecraft.getInstance();
		this.cam = cam;
		this.playerId = mc.player.getUUID();
		this.dimension = mc.level.dimension();
		// Like a 3D program: first-person view from the camera, no hotbar or crosshair.
		this.oldCameraType = mc.options.getCameraType();
		mc.options.setCameraType(CameraType.FIRST_PERSON);
		if (!mc.gui.hud.isHidden()) {
			mc.gui.hud.toggle();
			hidHud = true;
		}
	}

	// ------------------------------------------------------------------ server access

	private <T> void onServer(Function<ServerLevel, T> work, Consumer<T> then) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		server.submit(() -> {
			ServerLevel level = server.getLevel(dimension);
			return level == null ? null : work.apply(level);
		}).thenAcceptAsync(result -> {
			if (result != null && !ended) {
				then.accept(result);
			}
		}, minecraft).exceptionally(e -> {
			WorldPainter.LOGGER.error("Structure editor action failed", e);
			say("That did not work: " + e.getMessage());
			return null;
		});
	}

	private void refresh(boolean detectStructure) {
		BlockPos sel = selected;
		BlockPos pivot = BlockPos.containing(cam.pivotX, cam.pivotY, cam.pivotZ);
		EditorOps.FoundStructure known = info == null ? null : info.structure();
		List<BlockPos> knownFrames = info == null || info.endPortal() == null ? List.of() : info.endPortal().frames();
		onServer(level -> {
			EditorOps.FoundStructure s = detectStructure ? EditorOps.findStructure(level, pivot) : known;
			if (s == null && sel != null && detectStructure) {
				s = EditorOps.findStructure(level, sel);
			}
			String blockId = null;
			EditorOps.ContainerView container = null;
			String mob = null;
			BlockState selState = null;
			if (sel != null) {
				selState = level.getBlockState(sel);
				Identifier id = BuiltInRegistries.BLOCK.getKey(selState.getBlock());
				blockId = id == null ? "?" : id.toString();
				container = EditorOps.readContainer(level, sel);
				mob = EditorOps.spawnerMob(level, sel);
			}
			EditorOps.EndPortalInfo end = null;
			EditorOps.PortalInfo portal = null;
			if (!knownFrames.isEmpty() && !detectStructure) {
				// Rescan only around the portal found before (a stronghold is big).
				end = EditorOps.scanEndPortal(level, around(knownFrames, -1), around(knownFrames, 1));
			} else if (s != null && s.id().contains("stronghold")) {
				end = EditorOps.scanEndPortal(level, new BlockPos(s.minX(), s.minY(), s.minZ()), new BlockPos(s.maxX(), s.maxY(), s.maxZ()));
			} else if (selState != null && selState.is(Blocks.END_PORTAL_FRAME)) {
				end = EditorOps.scanEndPortal(level, sel.offset(-6, -2, -6), sel.offset(6, 2, 6));
			}
			if (s != null && s.id().contains("ruined_portal")) {
				portal = EditorOps.scanPortal(level, new BlockPos(s.minX(), s.minY(), s.minZ()), new BlockPos(s.maxX(), s.maxY(), s.maxZ()));
			} else if (selState != null && (selState.is(Blocks.OBSIDIAN) || selState.is(Blocks.CRYING_OBSIDIAN))) {
				portal = EditorOps.scanPortal(level, sel.offset(-8, -10, -8), sel.offset(8, 10, 8));
			}
			return new Snapshot(s, blockId, container, mob, end, portal);
		}, snap -> {
			info = snap;
			if (snap.container() != null && snap.container().lootTable() != null) {
				int i = lootTables.indexOf(snap.container().lootTable());
				if (i >= 0) {
					lootIndex = i;
				}
			}
		});
	}

	/** Corner of the box around some positions, grown by one block ({@code sign} -1 = min corner, 1 = max corner). */
	private static BlockPos around(List<BlockPos> positions, int sign) {
		int x = sign < 0 ? Integer.MAX_VALUE : Integer.MIN_VALUE, y = x, z = x;
		for (BlockPos p : positions) {
			x = sign < 0 ? Math.min(x, p.getX()) : Math.max(x, p.getX());
			y = sign < 0 ? Math.min(y, p.getY()) : Math.max(y, p.getY());
			z = sign < 0 ? Math.min(z, p.getZ()) : Math.max(z, p.getZ());
		}
		return new BlockPos(x + sign, y + sign, z + sign);
	}

	/** Runs a block-changing action on the server and records it for undo. */
	private void edit(Function<ServerLevel, List<EditorOps.BlockChange>> action, String done) {
		onServer(action, changes -> {
			if (!changes.isEmpty()) {
				undo.push(changes);
				redo.clear();
				while (undo.size() > 200) {
					undo.removeLast();
				}
			}
			if (done != null) {
				say(done);
			}
			refresh(false);
		});
	}

	// ------------------------------------------------------------------ setup

	@Override
	protected void init() {
		search = new EditBox(font, 0, 0, PANEL_W - 12, 14, Component.literal("Search"));
		search.setMaxLength(64);
		search.setValue(query);
		search.setHint(Component.literal("Search..."));
		search.setResponder(v -> {
			query = v;
			listScroll = 0;
		});
		search.visible = false;
		addRenderableWidget(search);
		if (mobs == null) {
			mobs = EditorOps.spawnableMobs();
			items = new ArrayList<>();
			for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
				items.add(id.toString());
			}
			items.sort(String::compareTo);
			onServer(level -> EditorOps.chestLootTables(level.getServer()), l -> lootTables = l);
			refresh(true);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		// Transparent: the world is the editor's viewport.
	}

	private boolean inViewport(double x, double y) {
		return x >= TOOLBAR_W && x < width - PANEL_W && y >= TOP && y < height - 12;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		mouseX = mx;
		mouseY = my;
		flyWithKeys();
		if (minecraft.player != null) {
			cam.apply(minecraft.player);
		}
		proj.update(minecraft.gameRenderer.mainCamera(), width, height);
		hits.clear();

		hover = null;
		if (proj.valid() && inViewport(mx, my) && minecraft.level != null) {
			hover = BlockRay.cast(minecraft.level, proj.cameraPos(), proj.ray(mx, my), 300, false);
		}
		drawWorldOverlays(g);
		drawTopBar(g);
		drawToolbar(g);
		drawPanel(g);
		drawStatus(g);

		super.extractRenderState(g, mx, my, a);

		String tip = hits.tooltip(mx, my);
		if (tip != null) {
			g.setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mx, my);
		}
		if (showHelp) {
			drawHelp(g);
		}
	}

	private void drawWorldOverlays(GuiGraphicsExtractor g) {
		if (!proj.valid()) {
			return;
		}
		g.enableScissor(TOOLBAR_W, TOP, width - PANEL_W, height - 12);
		if (info != null && info.structure() != null) {
			EditorOps.FoundStructure s = info.structure();
			proj.box(g, s.minX(), s.minY(), s.minZ(), s.maxX() + 1, s.maxY() + 1, s.maxZ() + 1, 0xFF40E0FF);
		}
		if (info != null && info.endPortal() != null) {
			for (BlockPos p : info.endPortal().frames()) {
				proj.block(g, p.getX(), p.getY(), p.getZ(), 0xFFB070FF);
			}
		}
		if (info != null && info.portal() != null) {
			for (BlockPos p : info.portal().missing()) {
				proj.block(g, p.getX(), p.getY(), p.getZ(), 0xFFFF50FF);
			}
		}
		if (selected != null) {
			proj.block(g, selected.getX(), selected.getY(), selected.getZ(), 0xFFFFE040);
		}
		if (hover != null) {
			if (tool == EditTool.PLACE) {
				BlockPos p = hover.placePos();
				proj.block(g, p.getX(), p.getY(), p.getZ(), 0xFF60FF60);
			} else {
				proj.block(g, hover.pos().getX(), hover.pos().getY(), hover.pos().getZ(), tool == EditTool.BREAK ? 0xFFFF5050 : WHITE);
			}
		}
		// Pivot marker
		double[] c = proj.project(cam.pivotX, cam.pivotY, cam.pivotZ);
		if (c != null) {
			int cx = (int) c[0], cy = (int) c[1];
			g.fill(cx - 3, cy, cx + 4, cy + 1, 0xC0FFFFFF);
			g.fill(cx, cy - 3, cx + 1, cy + 4, 0xC0FFFFFF);
		}
		g.disableScissor();
	}

	private void drawTopBar(GuiGraphicsExtractor g) {
		g.fill(0, 0, width, TOP, PANEL_BG);
		String name = info == null ? "..." : info.structure() == null ? "no structure here" : StructureInfo.pretty(info.structure().id());
		g.text(font, "Structure Editor - " + name, 6, 7, WHITE, false);
		int x = width - 4;
		x = topButton(g, x, "Done", "Close the editor and go back to where you started (Esc)", () -> close(true));
		x = topButton(g, x, "Stay here", "Close the editor and stay at the camera position", () -> close(false));
		x -= 6;
		x = topButton(g, x, "Redo", "Redo (Ctrl+Y)", this::redoAction);
		x = topButton(g, x, "Undo", "Undo block changes (Ctrl+Z)", this::undoAction);
		x -= 6;
		x = topButton(g, x, "Side", "Side view (3)", cam::viewSide);
		x = topButton(g, x, "Front", "Front view (1)", cam::viewFront);
		x = topButton(g, x, "Top", "Top view (7)", cam::viewTop);
		x = topButton(g, x, "Focus", "Center the view on the hovered or selected block (F)", this::focusHovered);
		x -= 6;
		topButton(g, x, "?", "Controls (F1)", () -> showHelp = !showHelp);
	}

	private int topButton(GuiGraphicsExtractor g, int right, String text, String tip, Runnable action) {
		int w = font.width(text) + 10;
		button(g, right - w, 3, w, text, false, action, tip);
		return right - w - 2;
	}

	private void drawToolbar(GuiGraphicsExtractor g) {
		g.fill(0, TOP, TOOLBAR_W - 2, height - 12, PANEL_BG);
		int y = TOP + 3;
		for (EditTool t : EditTool.values()) {
			int x = 2;
			boolean sel = tool == t;
			boolean hov = mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 20;
			g.fill(x, y, x + 22, y + 20, sel ? ACCENT : hov ? 0xFF3A3A48 : 0xFF2A2A34);
			g.blit(RenderPipelines.GUI_TEXTURED, Icons.TEXTURE, x + 3, y + 2, t.icon * 16f, 0f, 16, 16, Icons.SHEET_W, Icons.SHEET_H);
			hits.add(x, y, 22, 20, (mx, my, b) -> tool = t, t.label + " (" + t.key + ") - " + t.help);
			y += 22;
		}
	}

	private void drawStatus(GuiGraphicsExtractor g) {
		g.fill(0, height - 12, width, height, PANEL_BG);
		String left;
		if (message != null && System.currentTimeMillis() < messageUntil) {
			left = message;
		} else if (hover != null && minecraft.level != null) {
			BlockPos p = hover.pos();
			Identifier id = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(p).getBlock());
			left = (id == null ? "?" : id.toString().replace("minecraft:", "")) + "  at " + p.getX() + " " + p.getY() + " " + p.getZ();
		} else {
			left = "Middle/right drag: orbit   Shift+drag: pan   Wheel: zoom   WASD/QE: move   F1: help";
		}
		g.text(font, fit(left, width - 12), 6, height - 10, message != null && System.currentTimeMillis() < messageUntil ? 0xFFFFD040 : GRAY, false);
	}

	private void drawHelp(GuiGraphicsExtractor g) {
		String[] lines = {
				"Structure Editor - controls",
				"",
				"Middle or right mouse drag: orbit around the cross      Shift + drag: pan      Ctrl + drag: zoom",
				"Mouse wheel: zoom      WASD: move      Q / E: down / up      Shift: faster",
				"F: focus on the block under the mouse      Home: focus on the structure",
				"1 / 3 / 7 (or numpad): front / side / top view",
				"",
				"V select   B place   X break   I pick   (Alt + click picks with any tool)",
				"Delete: break the selected block      Ctrl+Z / Ctrl+Y: undo / redo block changes",
				"",
				"Select a chest to edit its loot, a spawner to change its mob, an end portal frame",
				"or a stronghold for the eyes, and obsidian or a ruined portal for the portal frame.",
				"",
				"Esc or Done: back to where you started.   Stay here: keep the camera position.",
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
		int bottom = height - 12;
		g.fill(px, TOP, width, bottom, PANEL_BG);
		int x = px + 6, w = PANEL_W - 12, y = TOP + 4;
		SearchMode wanted = SearchMode.NONE;
		search.visible = false;

		// Structure
		if (info != null && info.structure() != null) {
			EditorOps.FoundStructure s = info.structure();
			g.text(font, fit(StructureInfo.pretty(s.id()), w), x, y, 0xFFFFD040, false);
			y += 10;
			g.text(font, (s.maxX() - s.minX() + 1) + " x " + (s.maxY() - s.minY() + 1) + " x " + (s.maxZ() - s.minZ() + 1) + " blocks", x, y, DIM, false);
			y += 11;
		} else {
			g.text(font, info == null ? "Looking for a structure..." : "No structure at the cross", x, y, GRAY, false);
			y += 11;
		}
		button(g, x, y, w, "Find structure at the cross", false, () -> refresh(true), "Look for a structure where the orbit cross is");
		y += 18;

		// Quick edits for the structure
		if (info != null && info.endPortal() != null && !info.endPortal().frames().isEmpty()) {
			y = endPortalSection(g, x, y, w);
		}
		if (info != null && info.portal() != null && info.portal().upright()) {
			y = portalSection(g, x, y, w);
		} else if (info != null && info.portal() != null && info.portal().obsidian() + info.portal().crying() > 0) {
			g.text(font, "Portal: " + info.portal().obsidian() + " obsidian, " + info.portal().crying() + " crying (lying down)", x, y, GRAY, false);
			y += 12;
		}

		// Selected block
		if (selected != null && info != null && info.blockId() != null) {
			g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
			y += 4;
			g.text(font, fit("Selected: " + info.blockId().replace("minecraft:", ""), w), x, y, WHITE, false);
			y += 10;
			g.text(font, selected.getX() + " " + selected.getY() + " " + selected.getZ(), x, y, DIM, false);
			y += 11;
			if (info.container() != null) {
				y = containerSection(g, x, y, w, bottom);
				wanted = SearchMode.ITEMS;
			} else if (info.spawnerMob() != null) {
				y = spawnerSection(g, x, y, w);
				wanted = SearchMode.MOBS;
			}
		}
		if (wanted == SearchMode.NONE && tool == EditTool.PLACE) {
			g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
			y += 4;
			swatch(g, x, y, w, SurfaceColors.color(placeBlock), "Place: " + placeBlock.replace("minecraft:", ""));
			y += 13;
			wanted = SearchMode.BLOCKS;
		}
		if (searchMode != wanted) {
			searchMode = wanted;
			search.setValue("");
			query = "";
			listScroll = 0;
		}
		if (wanted != SearchMode.NONE) {
			search.setX(x);
			search.setY(y);
			search.setWidth(w);
			search.visible = true;
			y += 17;
			int h = Math.max(ROW_H * 3, bottom - 4 - y);
			switch (wanted) {
				case BLOCKS -> {
					if (!query.equals(blockResultsQuery)) {
						blockResultsQuery = query;
						blockResults = SurfaceColors.search(query, 400);
					}
					listScroll = list(g, x, y, w, h, blockResults, listScroll, id -> id.replace("minecraft:", ""),
							SurfaceColors::color, id -> id.equals(placeBlock), id -> placeBlock = id);
				}
				case ITEMS -> listScroll = itemList(g, x, y, w, h);
				case MOBS -> {
					List<String> filtered = filter(mobs);
					listScroll = list(g, x, y, w, h, filtered, listScroll, id -> id.replace("minecraft:", ""),
							id -> 0xFF8FBF6F, id -> id.equals(info.spawnerMob()), this::setSpawnerMob);
				}
				default -> {
				}
			}
		}
	}

	private List<String> filter(List<String> all) {
		String q = query.trim().toLowerCase();
		if (q.isEmpty()) {
			return all;
		}
		List<String> out = new ArrayList<>();
		for (String s : all) {
			if (s.contains(q)) {
				out.add(s);
			}
		}
		return out;
	}

	private int endPortalSection(GuiGraphicsExtractor g, int x, int y, int w) {
		EditorOps.EndPortalInfo e = info.endPortal();
		g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
		y += 4;
		g.text(font, "End portal: " + e.eyes() + " / " + e.frames().size() + " eyes" + (e.open() ? " (open)" : ""), x, y, 0xFFC8A0FF, false);
		y += 11;
		int bw = (w - 6) / 4;
		List<BlockPos> frames = e.frames();
		button(g, x, y, bw, "-", false, () -> setEyes(frames, Math.max(0, e.eyes() - 1)), "Remove one eye");
		button(g, x + bw + 2, y, bw, "+", false, () -> setEyes(frames, Math.min(frames.size(), e.eyes() + 1)), "Add one eye (12 opens the portal)");
		button(g, x + 2 * (bw + 2), y, bw, "All", false, () -> setEyes(frames, frames.size()), "Fill every frame - opens the portal");
		button(g, x + 3 * (bw + 2), y, bw, "None", false, () -> setEyes(frames, 0), "Take all eyes out");
		return y + 19;
	}

	private void setEyes(List<BlockPos> frames, int count) {
		edit(level -> EditorOps.setEndPortalEyes(level, frames, count), count + " eyes in the end portal");
	}

	private int portalSection(GuiGraphicsExtractor g, int x, int y, int w) {
		EditorOps.PortalInfo p = info.portal();
		g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
		y += 4;
		g.text(font, "Nether portal frame" + (p.lit() ? " (lit)" : ""), x, y, 0xFFFF9090, false);
		y += 10;
		g.text(font, p.obsidian() + " obsidian, " + p.crying() + " crying, " + p.missing().size() + " missing", x, y, GRAY, false);
		y += 11;
		int bw = (w - 2) / 2;
		button(g, x, y, bw, "+1 obsidian", false, () -> edit(l -> EditorOps.changeObsidian(l, p, 1), "Added obsidian"), "Add one obsidian where the frame is missing (from the bottom up)");
		button(g, x + bw + 2, y, bw, "-1 obsidian", false, () -> edit(l -> EditorOps.changeObsidian(l, p, -1), "Removed obsidian"), "Remove one frame block (from the top down)");
		y += 17;
		button(g, x, y, bw, "Crying -> normal", false, () -> edit(l -> EditorOps.cryingToObsidian(l, p), "Crying obsidian replaced"), "Replace crying obsidian in the frame with normal obsidian");
		button(g, x + bw + 2, y, bw, "Complete", false, () -> edit(l -> EditorOps.completeFrame(l, p), "Frame completed"), "Fill the whole frame with obsidian");
		y += 17;
		button(g, x, y, w, "Complete and light portal", false, () -> edit(l -> EditorOps.lightPortal(l, p), "Portal lit"), "Complete the frame and fill it with nether portal");
		return y + 19;
	}

	private int spawnerSection(GuiGraphicsExtractor g, int x, int y, int w) {
		String mob = info.spawnerMob();
		g.text(font, "Spawner: " + (mob == null || mob.isEmpty() ? "nothing" : mob.replace("minecraft:", "")), x, y, 0xFF8FDF6F, false);
		y += 11;
		g.text(font, "Pick a mob below to change it", x, y, DIM, false);
		return y + 12;
	}

	private void setSpawnerMob(String id) {
		BlockPos pos = selected;
		onServer(level -> EditorOps.setSpawnerMob(level, pos, id), ok -> {
			say(ok ? "Spawner now spawns " + id.replace("minecraft:", "") : "Could not change the spawner");
			refresh(false);
		});
	}

	private int containerSection(GuiGraphicsExtractor g, int x, int y, int w, int bottom) {
		List<ItemStack> stacks = info.container().items();
		int cols = 9;
		int rows = (stacks.size() + cols - 1) / cols;
		int slot = 18;
		for (int i = 0; i < stacks.size(); i++) {
			int sx = x + (i % cols) * slot, sy = y + (i / cols) * slot;
			boolean sel = i == selectedSlot;
			g.fill(sx, sy, sx + 17, sy + 17, sel ? ACCENT : 0xFF2A2A34);
			g.outline(sx, sy, 17, 17, 0xFF101014);
			ItemStack st = stacks.get(i);
			if (!st.isEmpty()) {
				g.item(st, sx + 1, sy + 1);
				g.itemDecorations(font, st, sx + 1, sy + 1);
			}
			int index = i;
			hits.add(sx, sy, 17, 17, (mx, my, b) -> {
				if (b == InputConstants.MOUSE_BUTTON_RIGHT) {
					setSlot(index, ItemStack.EMPTY);
				} else {
					selectedSlot = index;
				}
			}, st.isEmpty() ? "Empty slot - pick an item below to put it here" : st.getHoverName().getString() + " x" + st.getCount() + " (right-click to remove)");
		}
		y += rows * slot + 3;
		int bw = (w - 6) / 4;
		ItemStack cur = selectedSlot >= 0 && selectedSlot < stacks.size() ? stacks.get(selectedSlot) : ItemStack.EMPTY;
		button(g, x, y, bw, "-", false, () -> changeCount(cur, -1), "One less of the selected item");
		button(g, x + bw + 2, y, bw, "+", false, () -> changeCount(cur, 1), "One more of the selected item");
		button(g, x + 2 * (bw + 2), y, bw, "Max", false, () -> changeCount(cur, 999), "A full stack");
		button(g, x + 3 * (bw + 2), y, bw, "Clear", false, this::clearContainer, "Empty the whole container");
		y += 17;
		String table = lootTables.isEmpty() ? "(no loot tables)" : lootTables.get(Math.clamp(lootIndex, 0, lootTables.size() - 1)).replace("minecraft:chests/", "");
		button(g, x, y, 14, "<", false, () -> lootIndex = lootTables.isEmpty() ? 0 : (lootIndex - 1 + lootTables.size()) % lootTables.size(), "Previous loot table");
		button(g, x + 16, y, w - 32, fit(table, w - 36), false, () -> {
		}, "Loot table used by Reroll");
		button(g, x + w - 14, y, 14, ">", false, () -> lootIndex = lootTables.isEmpty() ? 0 : (lootIndex + 1) % lootTables.size(), "Next loot table");
		y += 17;
		button(g, x, y, w, "Reroll loot", false, this::reroll, "Replace the contents with a new random roll of the loot table above");
		y += 18;
		g.text(font, "Add item (to the selected slot):", x, y, DIM, false);
		return y + 11;
	}

	private void setSlot(int slot, ItemStack stack) {
		BlockPos pos = selected;
		onServer(level -> {
			EditorOps.setContainerItem(level, pos, slot, stack);
			return true;
		}, ok -> refresh(false));
	}

	private void changeCount(ItemStack cur, int delta) {
		if (cur.isEmpty() || selectedSlot < 0) {
			say("Select a slot with an item first.");
			return;
		}
		ItemStack copy = cur.copy();
		copy.setCount(Math.clamp(copy.getCount() + delta, 1, copy.getMaxStackSize()));
		setSlot(selectedSlot, copy);
	}

	private void clearContainer() {
		BlockPos pos = selected;
		onServer(level -> {
			EditorOps.clearContainer(level, pos);
			return true;
		}, ok -> {
			say("Container emptied");
			refresh(false);
		});
	}

	private void reroll() {
		if (lootTables.isEmpty()) {
			return;
		}
		String table = lootTables.get(Math.clamp(lootIndex, 0, lootTables.size() - 1));
		BlockPos pos = selected;
		onServer(level -> {
			EditorOps.rerollContainer(level, pos, table);
			return true;
		}, ok -> {
			say("New loot from " + table.replace("minecraft:", ""));
			refresh(false);
		});
	}

	private void addItem(String id, boolean fullStack) {
		Identifier rl = Identifier.tryParse(id);
		Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
		if (item == null || info == null || info.container() == null) {
			return;
		}
		ItemStack stack = new ItemStack(item, 1);
		if (fullStack) {
			stack.setCount(stack.getMaxStackSize());
		}
		int slot = selectedSlot;
		List<ItemStack> stacks = info.container().items();
		if (slot < 0 || slot >= stacks.size()) {
			slot = -1;
			for (int i = 0; i < stacks.size(); i++) {
				if (stacks.get(i).isEmpty()) {
					slot = i;
					break;
				}
			}
		}
		if (slot < 0) {
			say("The container is full - select a slot to replace.");
			return;
		}
		selectedSlot = slot;
		setSlot(slot, stack);
	}

	private int itemList(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		List<String> filtered = filter(items);
		int visible = Math.max(1, h / 18);
		int max = Math.max(0, filtered.size() - visible);
		int sc = Math.clamp(listScroll, 0, max);
		g.fill(x, y, x + w, y + h, 0xFF141419);
		g.enableScissor(x, y, x + w, y + h);
		for (int i = 0; i < visible + 1 && sc + i < filtered.size(); i++) {
			String id = filtered.get(sc + i);
			int ry = y + i * 18;
			if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 18) {
				g.fill(x, ry, x + w, ry + 18, 0xFF262633);
			}
			ItemStack icon = iconCache.computeIfAbsent(id, k -> {
				Identifier rl = Identifier.tryParse(k);
				Item item = rl == null ? null : BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
				return item == null ? ItemStack.EMPTY : new ItemStack(item);
			});
			if (!icon.isEmpty()) {
				g.item(icon, x + 1, ry + 1);
			}
			g.text(font, fit(id.replace("minecraft:", ""), w - 22), x + 20, ry + 5, 0xFFD0D0D8, false);
		}
		g.disableScissor();
		hits.add(x, y, w, h, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				int idx = sc + (int) ((my - y) / 18);
				if (idx >= 0 && idx < filtered.size()) {
					addItem(filtered.get(idx), minecraft.hasShiftDown());
				}
			}

			@Override
			public boolean scroll(double amount) {
				listScroll = Math.clamp(sc - (amount > 0 ? 3 : -3), 0, max);
				return true;
			}
		}, "Click: add one to the selected slot (or the first empty one). Shift+click: a full stack.");
		return sc;
	}

	// ---- small widgets ----

	private void swatch(GuiGraphicsExtractor g, int x, int y, int w, int color, String text) {
		g.fill(x, y, x + 10, y + 10, color);
		g.outline(x, y, 10, 10, 0xFF000000);
		g.text(font, fit(text, w - 14), x + 14, y + 1, WHITE, false);
	}

	private void button(GuiGraphicsExtractor g, int x, int y, int w, String text, boolean selectedState, Runnable action, String tip) {
		boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 15;
		g.fill(x, y, x + w, y + 15, selectedState ? ACCENT : hov ? 0xFF3A3A48 : 0xFF2A2A34);
		String t = fit(text, w - 4);
		g.text(font, t, x + (w - font.width(t)) / 2, y + 4, WHITE, false);
		hits.add(x, y, w, 15, (mx, my, b) -> action.run(), tip);
	}

	private <T> int list(GuiGraphicsExtractor g, int x, int y, int w, int h, List<T> entries, int scroll,
						 Function<T, String> label, ToIntFunction<T> color, Predicate<T> isSelected, Consumer<T> onPick) {
		int visible = Math.max(1, h / ROW_H);
		int max = Math.max(0, entries.size() - visible);
		int sc = Math.clamp(scroll, 0, max);
		g.fill(x, y, x + w, y + h, 0xFF141419);
		g.enableScissor(x, y, x + w, y + h);
		for (int i = 0; i < visible + 1 && sc + i < entries.size(); i++) {
			T e = entries.get(sc + i);
			int ry = y + 1 + i * ROW_H;
			boolean sel = isSelected.test(e);
			if (sel) {
				g.fill(x, ry, x + w, ry + ROW_H, 0xFF2F4A73);
			} else if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H) {
				g.fill(x, ry, x + w, ry + ROW_H, 0xFF262633);
			}
			g.fill(x + 3, ry + 2, x + 10, ry + 9, color.applyAsInt(e));
			g.text(font, fit(label.apply(e), w - 16), x + 13, ry + 2, sel ? WHITE : 0xFFD0D0D8, false);
		}
		g.disableScissor();
		hits.add(x, y, w, h, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				int idx = sc + (int) ((my - y - 1) / ROW_H);
				if (idx >= 0 && idx < entries.size()) {
					onPick.accept(entries.get(idx));
				}
			}

			@Override
			public boolean scroll(double amount) {
				listScroll = Math.clamp(sc - (amount > 0 ? 3 : -3), 0, max);
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
		int end = s.length();
		while (end > 0 && font.width(s.substring(0, end) + "...") > maxWidth) {
			end--;
		}
		return s.substring(0, end) + "...";
	}

	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 4000;
	}

	// ------------------------------------------------------------------ actions

	private void focusHovered() {
		if (hover != null) {
			cam.focus(hover.pos().getX() + 0.5, hover.pos().getY() + 0.5, hover.pos().getZ() + 0.5);
		} else if (selected != null) {
			cam.focus(selected.getX() + 0.5, selected.getY() + 0.5, selected.getZ() + 0.5);
		}
	}

	private void focusStructure() {
		if (info != null && info.structure() != null) {
			EditorOps.FoundStructure s = info.structure();
			cam.focus((s.minX() + s.maxX() + 1) / 2.0, (s.minY() + s.maxY() + 1) / 2.0, (s.minZ() + s.maxZ() + 1) / 2.0);
			int size = Math.max(s.maxX() - s.minX(), Math.max(s.maxY() - s.minY(), s.maxZ() - s.minZ()));
			cam.distance = Math.clamp(size * 1.3 + 8, OrbitCamera.MIN_DISTANCE, OrbitCamera.MAX_DISTANCE);
		}
	}

	private void useTool(BlockRay.Hit hit, boolean alt) {
		if (hit == null) {
			return;
		}
		if (alt || tool == EditTool.PICK) {
			BlockState state = minecraft.level.getBlockState(hit.pos());
			Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
			if (id != null) {
				placeBlock = id.toString();
				tool = EditTool.PLACE;
				say("Placing " + placeBlock.replace("minecraft:", ""));
			}
			return;
		}
		switch (tool) {
			case SELECT -> {
				selected = hit.pos();
				selectedSlot = -1;
				refresh(info == null || info.structure() == null);
			}
			case PLACE -> {
				BlockPos target = hit.placePos();
				String blockId = placeBlock;
				edit(level -> {
					Identifier rl = Identifier.tryParse(blockId);
					Block block = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
					return block == null ? List.of() : EditorOps.setBlock(level, target, block.defaultBlockState());
				}, null);
			}
			case BREAK -> breakBlock(hit.pos());
			default -> {
			}
		}
	}

	private void breakBlock(BlockPos pos) {
		if (pos.equals(selected)) {
			selected = null;
		}
		edit(level -> EditorOps.setBlock(level, pos, Blocks.AIR.defaultBlockState()), null);
	}

	private void undoAction() {
		List<EditorOps.BlockChange> changes = undo.poll();
		if (changes == null) {
			say("Nothing to undo");
			return;
		}
		redo.push(changes);
		onServer(level -> {
			EditorOps.apply(level, changes, true);
			return true;
		}, ok -> {
			say("Undone");
			refresh(false);
		});
	}

	private void redoAction() {
		List<EditorOps.BlockChange> changes = redo.poll();
		if (changes == null) {
			say("Nothing to redo");
			return;
		}
		undo.push(changes);
		onServer(level -> {
			EditorOps.apply(level, changes, false);
			return true;
		}, ok -> {
			say("Redone");
			refresh(false);
		});
	}

	// ------------------------------------------------------------------ input

	private boolean editBoxFocused() {
		return search.visible && search.isFocused();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (showHelp) {
			showHelp = false;
			return true;
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		setFocused(null);
		if (hits.click(mx, my, event.button())) {
			return true;
		}
		if (!inViewport(mx, my)) {
			return false;
		}
		int b = event.button();
		if (b == InputConstants.MOUSE_BUTTON_MIDDLE || b == InputConstants.MOUSE_BUTTON_RIGHT) {
			if (event.hasShiftDown()) {
				panning = true;
			} else {
				orbiting = true;
			}
			return true;
		}
		if (b == InputConstants.MOUSE_BUTTON_LEFT) {
			useTool(hover, event.hasAltDown());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (orbiting) {
			if (event.hasControlDown()) {
				cam.zoom(-dy * 0.05);
			} else {
				cam.orbit(dx, dy);
			}
			return true;
		}
		if (panning) {
			cam.pan(dx, dy, height);
			return true;
		}
		if (hits.drag(event.x(), event.y())) {
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		hits.release();
		if (orbiting || panning) {
			orbiting = false;
			panning = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		if (hits.scroll(mx, my, scrollY)) {
			return true;
		}
		if (inViewport(mx, my) && scrollY != 0) {
			cam.zoom(scrollY > 0 ? 1 : -1);
			return true;
		}
		return super.mouseScrolled(mx, my, scrollX, scrollY);
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
		if (event.hasControlDown()) {
			if (key == InputConstants.KEY_Z) {
				if (event.hasShiftDown()) {
					redoAction();
				} else {
					undoAction();
				}
				return true;
			}
			if (key == InputConstants.KEY_Y) {
				redoAction();
				return true;
			}
		}
		for (EditTool t : EditTool.values()) {
			if (key == keyFor(t.key)) {
				tool = t;
				return true;
			}
		}
		if (key == InputConstants.KEY_F) {
			focusHovered();
			return true;
		}
		if (key == InputConstants.KEY_HOME) {
			focusStructure();
			return true;
		}
		if (key == InputConstants.KEY_1 || key == InputConstants.KEY_NUMPAD1) {
			cam.viewFront();
			return true;
		}
		if (key == InputConstants.KEY_3 || key == InputConstants.KEY_NUMPAD3) {
			cam.viewSide();
			return true;
		}
		if (key == InputConstants.KEY_7 || key == InputConstants.KEY_NUMPAD7) {
			cam.viewTop();
			return true;
		}
		if (key == InputConstants.KEY_DELETE && selected != null) {
			breakBlock(selected);
			return true;
		}
		if (key == InputConstants.KEY_F1) {
			showHelp = !showHelp;
			return true;
		}
		if (isMoveKey(key)) {
			heldKeys.add(key);
			return true;
		}
		if (key == InputConstants.KEY_ESCAPE) {
			close(true);
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		heldKeys.remove(event.key());
		return super.keyReleased(event);
	}

	private static int keyFor(char c) {
		return switch (c) {
			case 'V' -> InputConstants.KEY_V;
			case 'B' -> InputConstants.KEY_B;
			case 'X' -> InputConstants.KEY_X;
			case 'I' -> InputConstants.KEY_I;
			default -> -1;
		};
	}

	private static boolean isMoveKey(int key) {
		return key == InputConstants.KEY_W || key == InputConstants.KEY_A || key == InputConstants.KEY_S || key == InputConstants.KEY_D
				|| key == InputConstants.KEY_Q || key == InputConstants.KEY_E;
	}

	private void flyWithKeys() {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;
		if (heldKeys.isEmpty() || editBoxFocused()) {
			return;
		}
		double speed = Math.max(4, cam.distance * 0.8) * dt * (minecraft.hasShiftDown() ? 3 : 1);
		double f = 0, r = 0, u = 0;
		if (heldKeys.contains(InputConstants.KEY_W)) {
			f += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_S)) {
			f -= speed;
		}
		if (heldKeys.contains(InputConstants.KEY_D)) {
			r += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_A)) {
			r -= speed;
		}
		if (heldKeys.contains(InputConstants.KEY_E)) {
			u += speed;
		}
		if (heldKeys.contains(InputConstants.KEY_Q)) {
			u -= speed;
		}
		cam.fly(f, r, u);
	}

	@Override
	public void tick() {
		super.tick();
		if (minecraft.player != null) {
			cam.apply(minecraft.player);
		}
	}

	// ------------------------------------------------------------------ closing

	private void close(boolean backToStart) {
		returnToStart = backToStart;
		minecraft.gui.setScreen(null);
	}

	@Override
	public void onClose() {
		close(true);
	}

	@Override
	public void removed() {
		super.removed();
		if (ended) {
			return;
		}
		ended = true;
		minecraft.options.setCameraType(oldCameraType);
		if (hidHud && minecraft.gui.hud.isHidden()) {
			minecraft.gui.hud.toggle();
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			boolean back = returnToStart;
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player != null) {
					EditorSessions.end(server, player, back);
				}
			});
		}
	}
}
