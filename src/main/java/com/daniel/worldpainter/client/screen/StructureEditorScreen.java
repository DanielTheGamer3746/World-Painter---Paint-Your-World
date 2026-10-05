package com.daniel.worldpainter.client.screen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.Icons;
import com.daniel.worldpainter.client.WorldPainterClient;
import com.daniel.worldpainter.client.editor.BlockRay;
import com.daniel.worldpainter.client.editor.Camera3D;
import com.daniel.worldpainter.client.editor.OrbitCamera;
import com.daniel.worldpainter.client.gui.Gfx;
import com.daniel.worldpainter.client.gui.TextBox;
import com.daniel.worldpainter.data.BetaBlocks;
import com.daniel.worldpainter.mixin.DungeonFeatureAccessor;
import com.daniel.worldpainter.util.MathUtil;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.TranslationStorage;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.DungeonFeature;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * A small 3D editor for the world you are playing (Beta 1.7.3): fly around like in a 3D program,
 * select, place and break blocks, change what is in a chest and what a spawner spawns. Opened from
 * the painter's 3D view (Edit 3D) or with K in the world. The game is paused; every change is made
 * in the world right away and can be undone while the editor is open.
 */
public final class StructureEditorScreen extends Screen {
	private static final int TOP = 22;
	private static final int TOOLBAR_W = 26;
	private static final int PANEL_W = 184;
	private static final int STATUS_H = 12;
	private static final int ROW_H = 11;
	private static final int ICON_ROW_H = 18;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A8;
	private static final int DIM = 0xFF70707A;
	private static final int ACCENT = 0xFF3E6FB0;
	private static final int PANEL_BG = 0xE018181E;
	private static final int MOUSE_LEFT = 0, MOUSE_RIGHT = 1, MOUSE_MIDDLE = 2;
	private static final int FIND_RADIUS = 24;

	private enum EditTool {
		SELECT("Select", 'V', Icons.SELECT, "Click a block: chests show their items, spawners their mob"),
		PLACE("Place", 'B', Icons.PLACE, "Click a block to put the chosen block against that side"),
		BREAK("Break", 'X', Icons.BREAK, "Click a block to remove it"),
		PICK("Pick", 'I', Icons.PICK, "Click a block to place more of it (Alt + click does this with any tool)");

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

	private enum ListMode {NONE, BLOCKS, ITEMS, MOBS}

	/** A block with what is inside it (chest items, spawner mob), to undo a change. */
	private record Snapshot(int id, int meta, ItemStack[] items, String mob) {
	}

	private record Change(int x, int y, int z, Snapshot before, Snapshot after) {
	}

	/** A block or item to choose, with its data value (wool color, wood type...). */
	private record Entry(int id, int meta, String name) {
		ItemStack stack(int count) {
			return new ItemStack(id, count, meta);
		}
	}

	/** Mobs a Beta spawner can spawn (the game's own names for them). */
	private static final String[] MOBS = {"Zombie", "Skeleton", "Spider", "Creeper", "Slime", "Ghast", "PigZombie",
			"Giant", "Pig", "Sheep", "Cow", "Chicken", "Squid", "Wolf"};
	/** Blocks that cannot simply be placed alone (two-part blocks, technical blocks). */
	private static final Set<Integer> NOT_PLACEABLE = Set.of(8, 10, 26, 34, 36, 64, 71, 90);
	/** Block forms that are not real items (they show up as their own item instead). */
	private static final Set<Integer> NOT_ITEMS = Set.of(8, 9, 10, 11, 26, 34, 36, 51, 52, 55, 59, 60, 62, 63, 64, 68, 71,
			74, 75, 83, 90, 92, 93, 94);
	private static final Map<Integer, String> VARIANT_NAMES = new HashMap<>();

	static {
		String[] wood = {"Oak", "Spruce", "Birch"};
		for (int i = 0; i < 3; i++) {
			VARIANT_NAMES.put(17 << 4 | i, wood[i] + " Log");
			VARIANT_NAMES.put(6 << 4 | i, wood[i] + " Sapling");
			VARIANT_NAMES.put(18 << 4 | i, wood[i] + " Leaves");
		}
		String[] slab = {"Stone", "Sandstone", "Wooden", "Cobblestone"};
		for (int i = 0; i < 4; i++) {
			VARIANT_NAMES.put(44 << 4 | i, slab[i] + " Slab");
		}
		VARIANT_NAMES.put(31 << 4 | 1, "Tall Grass");
		VARIANT_NAMES.put(31 << 4 | 2, "Fern");
	}

	private static List<Entry> blockEntries;
	private static List<Entry> itemEntries;

	private final Screen parent;
	private final Minecraft mc;
	private final Gfx g;
	private final Hits hits = new Hits();
	private final Camera3D view;
	/** True when the editor was opened in the world (K): it starts and ends the 3D view itself. */
	private final boolean ownsView;
	private final OrbitCamera cam;
	private boolean started;
	private boolean closing;

	private EditTool tool = EditTool.SELECT;
	private Entry placeBlock;
	private BlockRay.Hit hover;
	private int[] selected;
	private int selectedSlot = -1;
	private final Deque<List<Change>> undo = new ArrayDeque<>();
	private final Deque<List<Change>> redo = new ArrayDeque<>();

	private final TextBox search;
	private ListMode listMode = ListMode.NONE;
	private String query = "";
	private int listScroll;

	private boolean orbiting;
	private boolean panning;
	private double mouseX, mouseY;
	private final Set<Integer> heldKeys = new HashSet<>();
	private long lastNanos;
	private long ticks;
	private boolean showHelp;
	private String message;
	private long messageUntil;

	/**
	 * @param parent the painter to go back to, or null when opened in the world
	 * @param view   the painter's 3D view, or null to use one of its own
	 * @param start  where the camera starts, or null for where the view looks now (or the player)
	 */
	public StructureEditorScreen(Screen parent, Camera3D view, OrbitCamera start) {
		this.parent = parent;
		this.mc = WorldPainterClient.mc();
		this.g = new Gfx(mc);
		this.ownsView = view == null;
		this.view = view != null ? view : new Camera3D(mc);
		OrbitCamera c = start;
		if (c == null && this.view.camera() != null) {
			c = this.view.camera();
		}
		if (c == null) {
			double x = mc.player != null ? mc.player.x : 0, z = mc.player != null ? mc.player.z : 0;
			c = this.view.cameraAt(x, z);
			c.distance = 18;
		}
		this.cam = c;
		this.search = new TextBox("Search", "", v -> {
			query = v;
			listScroll = 0;
		});
	}

	/** A camera at the player's eyes looking where they look, around the block they look at (K in the world). */
	public static OrbitCamera fromPlayer(Minecraft mc) {
		double ex = mc.player.x, ey = mc.player.y, ez = mc.player.z;
		float yaw = mc.player.yaw, pitch = mc.player.pitch;
		double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
		com.daniel.worldpainter.client.editor.V3 dir = new com.daniel.worldpainter.client.editor.V3(
				-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
		BlockRay.Hit hit = BlockRay.cast(mc.world, new com.daniel.worldpainter.client.editor.V3(ex, ey, ez), dir, 64, false, true);
		double distance = 10;
		double px = ex + dir.x() * distance, py = ey + dir.y() * distance, pz = ez + dir.z() * distance;
		if (hit != null) {
			px = hit.x() + 0.5;
			py = hit.y() + 0.5;
			pz = hit.z() + 0.5;
			distance = Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez));
		}
		OrbitCamera c = new OrbitCamera(px, py, pz);
		c.yaw = yaw;
		c.pitch = pitch;
		c.distance = MathUtil.clamp(distance, OrbitCamera.MIN_DISTANCE, OrbitCamera.MAX_DISTANCE);
		return c;
	}

	// ------------------------------------------------------------------ setup

	@Override
	public void init() {
		buttons.clear();
		Keyboard.enableRepeatEvents(true);
		view.enter(cam);
		if (!started) {
			started = true;
			if (!view.active()) {
				say("The editor needs the world you are playing.");
			} else {
				findNearby(false);
			}
		}
	}

	@Override
	public boolean shouldPause() {
		return true;
	}

	private boolean inViewport(double x, double y) {
		return x >= TOOLBAR_W && x < width - PANEL_W && y >= TOP && y < height - STATUS_H;
	}

	private World world() {
		return mc.world;
	}

	// ------------------------------------------------------------------ drawing

	@Override
	public void render(int mx, int my, float delta) {
		g.begin();
		mouseX = mx;
		mouseY = my;
		hits.clear();
		search.visible = false;
		if (world() == null || !view.active()) {
			g.fill(0, 0, width, height, 0xFF111116);
			g.centeredText("The structure editor needs the world you are playing. Press Esc.", width / 2, height / 2, WHITE);
			return;
		}
		view.frame(width, height);
		flyWithKeys();

		hover = null;
		if (inViewport(mx, my) && !orbiting && !panning && !showHelp) {
			hover = view.pick(mx, my, false, true);
		}
		g.enableScissor(TOOLBAR_W, TOP, width - PANEL_W, height - STATUS_H);
		drawWorldOverlays();
		g.disableScissor();
		drawTopBar();
		drawToolbar();
		drawPanel();
		drawStatus();
		search.draw(g, ticks);

		String tip = hits.tooltip(mx, my);
		if (tip != null && !showHelp) {
			g.tooltip(g.wrap(tip, 220), mx, my, width, height);
		}
		if (showHelp) {
			drawHelp();
		}
	}

	private void drawWorldOverlays() {
		if (selected != null) {
			view.block(g, selected[0], selected[1], selected[2], 0xFFFFE040);
		}
		if (hover != null) {
			if (tool == EditTool.PLACE && !altDown()) {
				view.block(g, hover.placeX(), hover.placeY(), hover.placeZ(), 0xFF60FF60);
			} else {
				view.block(g, hover.x(), hover.y(), hover.z(), tool == EditTool.BREAK && !altDown() ? 0xFFFF5050 : WHITE);
			}
		}
		// The point the camera turns around.
		double[] c = view.project(cam.pivotX, cam.pivotY, cam.pivotZ);
		if (c != null) {
			int cx = (int) c[0], cy = (int) c[1];
			g.fill(cx - 3, cy, cx + 4, cy + 1, 0xC0FFFFFF);
			g.fill(cx, cy - 3, cx + 1, cy + 4, 0xC0FFFFFF);
		}
	}

	private void drawTopBar() {
		g.fill(0, 0, width, TOP, PANEL_BG);
		int x = width - 4;
		x = topButton(x, "Done", parent != null ? "Back to the painter (Esc)" : "Close the editor and go back to playing (Esc)", false, this::close);
		x -= 6;
		x = topButton(x, redo.isEmpty() ? "§7Redo" : "Redo", "Redo (Ctrl+Y)", false, this::redoAction);
		x = topButton(x, undo.isEmpty() ? "§7Undo" : "Undo", "Undo (Ctrl+Z)", false, this::undoAction);
		x -= 6;
		x = topButton(x, "Side", "Side view (3)", false, cam::viewSide);
		x = topButton(x, "Front", "Front view (1)", false, cam::viewFront);
		x = topButton(x, "Top", "Top view (7)", false, cam::viewTop);
		x = topButton(x, "Focus", "Turn around the block under the mouse or the selected one (F)", false, this::focusHovered);
		x = topButton(x, "Light", "See in the dark: show caves and dungeons lit (L)", view.seeInDark(), this::toggleLight);
		x -= 6;
		x = topButton(x, "?", "Controls (F1)", showHelp, () -> showHelp = !showHelp);
		String title = "Structure Editor";
		if (x - 10 > g.width(title)) {
			g.text(title, 6, 7, WHITE, false);
		}
	}

	private int topButton(int right, String text, String tip, boolean on, Runnable action) {
		int w = Math.max(20, g.width(text) + 10);
		button(right - w, 3, w, text, on, action, tip);
		return right - w - 2;
	}

	private void drawToolbar() {
		g.fill(0, TOP, TOOLBAR_W - 2, height - STATUS_H, PANEL_BG);
		int y = TOP + 3;
		for (EditTool t : EditTool.values()) {
			int x = 2;
			boolean sel = tool == t;
			boolean hov = mouseX >= x && mouseX < x + 22 && mouseY >= y && mouseY < y + 20;
			g.fill(x, y, x + 22, y + 20, sel ? ACCENT : hov ? 0xFF3A3A48 : 0xFF2A2A34);
			if (sel) {
				g.outline(x, y, 22, 20, 0xFF9CC2FF);
			}
			g.icon(t.icon, x + 3, y + 2);
			hits.add(x, y, 22, 20, (mx, my, b) -> tool = t, t.label + " (" + t.key + ") - " + t.help);
			y += 22;
		}
	}

	private void drawStatus() {
		g.fill(0, height - STATUS_H, width, height, PANEL_BG);
		boolean showMessage = message != null && System.currentTimeMillis() < messageUntil;
		String left;
		if (showMessage) {
			left = message;
		} else if (hover != null) {
			World w = world();
			int id = w.getBlockId(hover.x(), hover.y(), hover.z());
			left = blockName(id, w.getBlockMeta(hover.x(), hover.y(), hover.z())) + "  at " + hover.x() + " " + hover.y() + " " + hover.z();
		} else {
			left = "Right or middle drag: turn   Shift+drag: move   Wheel: zoom   WASD/QE: fly   F1: help";
		}
		g.text(fit(left, width - 12), 6, height - 10, showMessage ? 0xFFFFD040 : GRAY, false);
	}

	private void drawHelp() {
		String[] lines = {
				"Structure Editor - controls",
				"",
				"Right or middle mouse drag: turn around the cross     Shift + drag: move     Ctrl + drag: zoom",
				"Mouse wheel: zoom     WASD: fly     Q / E: down / up     Shift: faster",
				"F: turn around the block under the mouse     Home: look for a dungeon nearby",
				"1 / 3 / 7: front / side / top view     L: see in the dark",
				"",
				"V select   B place   X break   I pick   (Alt + click picks with any tool)",
				"Delete: break the selected block     Ctrl+Z / Ctrl+Y: undo / redo",
				"",
				"Select a chest to change what is inside: click a slot, then an item in the list",
				"(Shift + click: a whole stack). Select a spawner to choose its mob.",
				"The game is paused while you edit; Esc or Done goes back.",
				"",
				"Click anywhere to close"
		};
		int w = 0;
		for (String l : lines) {
			w = Math.max(w, g.width(l));
		}
		w = Math.min(width - 10, w + 20);
		int h = lines.length * 11 + 16;
		int x = (width - w) / 2, y = Math.max(2, (height - h) / 2);
		g.fill(x, y, x + w, y + h, 0xF0101018);
		g.outline(x, y, w, h, ACCENT);
		for (int i = 0; i < lines.length; i++) {
			g.text(fit(lines[i], w - 20), x + 10, y + 8 + i * 11, i == 0 ? 0xFFFFD040 : WHITE, false);
		}
	}

	// ------------------------------------------------------------------ side panel

	private void drawPanel() {
		int px = width - PANEL_W;
		int bottom = height - STATUS_H;
		g.fill(px, TOP, width, bottom, PANEL_BG);
		hits.add(px, TOP, PANEL_W, bottom - TOP, (mx, my, b) -> {
		});
		int x = px + 6, w = PANEL_W - 12, y = TOP + 5;
		ListMode wanted = ListMode.NONE;
		World world = world();

		BlockEntity be = null;
		if (selected != null) {
			int id = world.getBlockId(selected[0], selected[1], selected[2]);
			int meta = world.getBlockMeta(selected[0], selected[1], selected[2]);
			g.text(fit("Selected: " + blockName(id, meta), w), x, y, WHITE, false);
			y += 10;
			g.text(selected[0] + " " + selected[1] + " " + selected[2], x, y, DIM, false);
			y += 12;
			if (id == BetaBlocks.CHEST || id == BetaBlocks.SPAWNER) {
				be = blockEntity(selected[0], selected[1], selected[2]);
			}
		} else {
			for (String line : g.wrap("Select a block (V) to edit it. Chests: change their items. Spawners: change their mob.", w)) {
				g.text(line, x, y, GRAY, false);
				y += 10;
			}
			y += 3;
		}
		if (be instanceof ChestBlockEntity chest) {
			y = chestSection(chest, x, y, w);
			wanted = ListMode.ITEMS;
		} else if (be instanceof MobSpawnerBlockEntity spawner) {
			String mob = spawnerMob(spawner);
			g.text(fit("Spawns: " + (mob == null || mob.isEmpty() ? "nothing" : mob), w), x, y, 0xFF8FDF6F, false);
			y += 11;
			g.text("Pick a mob below to change it", x, y, DIM, false);
			y += 12;
			wanted = ListMode.MOBS;
		} else if (tool == EditTool.PLACE) {
			g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
			y += 4;
			Entry p = placing();
			g.fill(x, y, x + 18, y + 18, 0xFF2A2A34);
			g.item(p.stack(1), x + 1, y + 1);
			g.text(fit("Place: " + p.name(), w - 22), x + 22, y + 5, WHITE, false);
			y += 22;
			wanted = ListMode.BLOCKS;
		}
		if (wanted == ListMode.NONE) {
			g.fill(x, y, x + w, y + 1, 0xFF2C2C38);
			y += 5;
			button(x, y, w, "Find a dungeon nearby", false, () -> findNearby(true),
					"Look for a spawner or chest near the cross (Home)");
			y += 18;
			button(x, y, w, "See in the dark: " + (view.seeInDark() ? "On" : "Off"), view.seeInDark(), this::toggleLight,
					"Dungeons are underground and dark: show them lit (L)");
			y += 22;
			for (String line : g.wrap("Dungeons are under the ground. Fly into the ground (S, Q) or click Find: the"
					+ " view goes through the stone to the room.", w)) {
				g.text(line, x, y, DIM, false);
				y += 10;
			}
		}
		if (listMode != wanted) {
			listMode = wanted;
			search.setFocused(false);
			listScroll = 0;
			resetSearch();
		}
		if (wanted == ListMode.NONE) {
			return;
		}
		search.place(x, y, w);
		y += 17;
		int h = Math.max(ICON_ROW_H * 3, bottom - 4 - y);
		switch (wanted) {
			case BLOCKS -> listScroll = iconList(x, y, w, h, filter(blocks()), e -> e.equals(placing()), e -> {
				placeBlock = e;
				say("Placing " + e.name());
			}, "Click a block to place it with the Place tool");
			case ITEMS -> listScroll = iconList(x, y, w, h, filter(items()), e -> false, e -> addItem(e, shiftDown()),
					"Click: put one in the selected slot (or the first empty one). Shift + click: a whole stack.");
			case MOBS -> {
				List<String> mobs = new ArrayList<>();
				String q = query.trim().toLowerCase(Locale.ROOT);
				for (String m : MOBS) {
					if (q.isEmpty() || m.toLowerCase(Locale.ROOT).contains(q)) {
						mobs.add(m);
					}
				}
				MobSpawnerBlockEntity spawner = (MobSpawnerBlockEntity) be;
				String now = spawnerMob(spawner);
				listScroll = list(x, y, w, h, mobs, listScroll, m -> m, m -> 0xFF8FBF6F, m -> m.equals(now), this::setMob);
			}
			default -> {
			}
		}
	}

	private void resetSearch() {
		search.setText("");
		query = "";
	}

	private int chestSection(ChestBlockEntity chest, int x, int y, int w) {
		int size = Math.min(chest.size(), 27);
		int cols = 9, slot = 18;
		for (int i = 0; i < size; i++) {
			int sx = x + (i % cols) * slot, sy = y + (i / cols) * slot;
			boolean sel = i == selectedSlot;
			g.fill(sx, sy, sx + 17, sy + 17, sel ? ACCENT : 0xFF2A2A34);
			g.outline(sx, sy, 17, 17, 0xFF101014);
			ItemStack st = chest.getStack(i);
			if (st != null) {
				g.item(st, sx + 1, sy + 1);
			}
			int index = i;
			hits.add(sx, sy, 17, 17, (mx, my, b) -> {
				if (b == MOUSE_RIGHT) {
					setSlot(index, null, "Removed the item");
				} else {
					selectedSlot = index;
				}
			}, st == null ? "Empty slot: click it, then an item below" : itemName(st) + " x" + st.count + " (right-click: remove)");
		}
		y += ((size + cols - 1) / cols) * slot + 3;
		int bw = (w - 6) / 4;
		ItemStack cur = selectedSlot >= 0 && selectedSlot < size ? chest.getStack(selectedSlot) : null;
		button(x, y, bw, "-", false, () -> changeCount(cur, -1), "One less of the selected item");
		button(x + bw + 2, y, bw, "+", false, () -> changeCount(cur, 1), "One more of the selected item");
		button(x + 2 * (bw + 2), y, bw, "Stack", false, () -> changeCount(cur, 64), "A whole stack of the selected item");
		button(x + 3 * (bw + 2), y, bw, "Remove", false, () -> {
			if (selectedSlot >= 0 && cur != null) {
				setSlot(selectedSlot, null, "Removed the item");
			}
		}, "Take the selected item out");
		y += 17;
		int hw = (w - 2) / 2;
		button(x, y, hw, "Empty chest", false, this::emptyChest, "Take everything out");
		button(x + hw + 2, y, w - hw - 2, "Dungeon loot", false, this::rollLoot, "Fill it like a Beta dungeon chest (new random loot each click)");
		y += 19;
		g.text("Add an item:", x, y, DIM, false);
		return y + 11;
	}

	private List<Entry> filter(List<Entry> all) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return all;
		}
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) {
			if (e.name().toLowerCase(Locale.ROOT).contains(q) || String.valueOf(e.id()).equals(q)) {
				out.add(e);
			}
		}
		return out;
	}

	// ---- small widgets ----

	private void button(int x, int y, int w, String text, boolean on, Runnable action, String tip) {
		if (w <= 0) {
			return;
		}
		boolean hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 15;
		g.fill(x, y, x + w, y + 15, on ? ACCENT : hov ? 0xFF3A3A48 : 0xFF2A2A34);
		String t = fit(text, w - 4);
		g.text(t, x + (w - g.width(t)) / 2, y + 4, WHITE, false);
		hits.add(x, y, w, 15, (mx, my, b) -> action.run(), tip);
	}

	/** A list of blocks or items with their pictures; returns the (clamped) scroll position. */
	private int iconList(int x, int y, int w, int h, List<Entry> entries, Predicate<Entry> isSelected, Consumer<Entry> onPick, String tip) {
		int visible = Math.max(1, h / ICON_ROW_H);
		int max = Math.max(0, entries.size() - visible);
		int sc = MathUtil.clamp(listScroll, 0, max);
		g.fill(x, y, x + w, y + h, 0xFF141419);
		g.outline(x, y, w, h, 0xFF2C2C38);
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		for (int i = 0; i < visible + 1 && sc + i < entries.size(); i++) {
			Entry e = entries.get(sc + i);
			int ry = y + 1 + i * ICON_ROW_H;
			if (isSelected.test(e)) {
				g.fill(x + 1, ry, x + w - 1, ry + ICON_ROW_H, 0xFF2F4A73);
			} else if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ICON_ROW_H) {
				g.fill(x + 1, ry, x + w - 1, ry + ICON_ROW_H, 0xFF262633);
			}
			g.item(e.stack(1), x + 2, ry + 1);
			g.text(fit(e.name(), w - 26), x + 21, ry + 5, 0xFFD0D0D8, false);
		}
		g.disableScissor();
		if (entries.isEmpty()) {
			g.text("Nothing found", x + 6, y + 6, DIM, false);
		}
		if (max > 0) {
			int barH = Math.max(8, h * visible / entries.size());
			int barY = y + (int) ((h - barH) * (sc / (double) max));
			g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF5A5A6A);
		}
		hits.add(x, y, w, h, new Hits.Hit() {
			@Override
			public void click(double mx, double my, int button) {
				int idx = sc + (int) ((my - y - 1) / ICON_ROW_H);
				if (idx >= 0 && idx < entries.size()) {
					onPick.accept(entries.get(idx));
				}
			}

			@Override
			public boolean scroll(double amount) {
				listScroll = MathUtil.clamp(sc - (amount > 0 ? 3 : -3), 0, max);
				return true;
			}
		}, tip);
		return sc;
	}

	private <T> int list(int x, int y, int w, int h, List<T> entries, int scroll, Function<T, String> label,
						 ToIntFunction<T> color, Predicate<T> isSelected, Consumer<T> onPick) {
		int visible = Math.max(1, h / ROW_H);
		int max = Math.max(0, entries.size() - visible);
		int sc = MathUtil.clamp(scroll, 0, max);
		g.fill(x, y, x + w, y + h, 0xFF141419);
		g.outline(x, y, w, h, 0xFF2C2C38);
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		for (int i = 0; i < visible + 1 && sc + i < entries.size(); i++) {
			T e = entries.get(sc + i);
			int ry = y + 1 + i * ROW_H;
			boolean sel = isSelected.test(e);
			if (sel) {
				g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0xFF2F4A73);
			} else if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW_H) {
				g.fill(x + 1, ry, x + w - 1, ry + ROW_H, 0xFF262633);
			}
			g.fill(x + 3, ry + 2, x + 10, ry + 9, color.applyAsInt(e));
			g.text(fit(label.apply(e), w - 18), x + 13, ry + 2, sel ? WHITE : 0xFFD0D0D8, false);
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
				listScroll = MathUtil.clamp(sc - (amount > 0 ? 3 : -3), 0, max);
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
		int end = s.length();
		while (end > 0 && g.width(s.substring(0, end) + "...") > maxWidth) {
			end--;
		}
		return s.substring(0, end) + "...";
	}

	private void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 4000;
	}

	// ------------------------------------------------------------------ blocks, items and names

	private Entry placing() {
		if (placeBlock == null) {
			placeBlock = new Entry(BetaBlocks.COBBLESTONE, 0, blockName(BetaBlocks.COBBLESTONE, 0));
		}
		return placeBlock;
	}

	private static int[] variants(int id) {
		switch (id) {
			case 35:
			case 351:
				return range(0, 15);
			case 6:
			case 17:
			case 18:
				return range(0, 2);
			case 44:
				return range(0, 3);
			case 31:
				return range(1, 2);
			default:
				return new int[]{0};
		}
	}

	private static int[] range(int from, int to) {
		int[] r = new int[to - from + 1];
		for (int i = 0; i < r.length; i++) {
			r[i] = from + i;
		}
		return r;
	}

	/** Every block that can be placed alone. */
	private static List<Entry> blocks() {
		if (blockEntries == null) {
			List<Entry> out = new ArrayList<>();
			for (int id = 1; id < Math.min(256, Block.BLOCKS.length); id++) {
				if (Block.BLOCKS[id] == null || NOT_PLACEABLE.contains(id) || id >= Item.ITEMS.length || Item.ITEMS[id] == null) {
					continue;
				}
				for (int meta : variants(id)) {
					out.add(new Entry(id, meta, blockName(id, meta)));
				}
			}
			blockEntries = out;
		}
		return blockEntries;
	}

	/** Every item (and block item) that can be in a chest. */
	private static List<Entry> items() {
		if (itemEntries == null) {
			List<Entry> out = new ArrayList<>();
			for (int id = 1; id < Item.ITEMS.length; id++) {
				if (Item.ITEMS[id] == null) {
					continue;
				}
				if (id < 256 && (id >= Block.BLOCKS.length || Block.BLOCKS[id] == null || NOT_ITEMS.contains(id))) {
					continue;
				}
				for (int meta : variants(id)) {
					out.add(new Entry(id, meta, blockName(id, meta)));
				}
			}
			itemEntries = out;
		}
		return itemEntries;
	}

	private static final Map<Integer, String> NAMES = new HashMap<>();

	/** The game's name of a block or item ("Cobblestone", "Golden Apple"...). */
	static String blockName(int id, int meta) {
		if (id == 0) {
			return "Air";
		}
		int key = id << 4 | (meta & 15);
		String known = NAMES.get(key);
		if (known != null) {
			return known;
		}
		String name = VARIANT_NAMES.get(key);
		if (name == null) {
			try {
				if (id < Item.ITEMS.length && Item.ITEMS[id] != null) {
					String tk = new ItemStack(id, 1, meta).getTranslationKey();
					String t = TranslationStorage.getInstance().getClientTranslation(tk);
					if (t != null && !t.trim().isEmpty()) {
						name = t.trim();
					} else if (tk != null) {
						name = pretty(tk);
					}
				}
			} catch (RuntimeException | LinkageError e) {
				// Fall back to the number below.
			}
		}
		if (name == null) {
			name = "Block " + id;
		}
		NAMES.put(key, name);
		return name;
	}

	private static String itemName(ItemStack stack) {
		return blockName(stack.itemId, stack.getDamage());
	}

	/** "tile.stoneMoss" -> "Stone Moss". */
	private static String pretty(String key) {
		String k = key.substring(key.lastIndexOf('.') + 1);
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < k.length(); i++) {
			char c = k.charAt(i);
			if (i == 0) {
				b.append(Character.toUpperCase(c));
			} else {
				if (Character.isUpperCase(c)) {
					b.append(' ');
				}
				b.append(c);
			}
		}
		return b.toString();
	}

	// ------------------------------------------------------------------ editing the world

	private BlockEntity blockEntity(int x, int y, int z) {
		try {
			return world().getBlockEntity(x, y, z);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String spawnerMob(MobSpawnerBlockEntity spawner) {
		try {
			return spawner.getSpawnedEntityId();
		} catch (LinkageError e) {
			return null;
		}
	}

	private Snapshot read(int x, int y, int z) {
		World w = world();
		int id = w.getBlockId(x, y, z);
		int meta = w.getBlockMeta(x, y, z);
		ItemStack[] items = null;
		String mob = null;
		if (id == BetaBlocks.CHEST || id == BetaBlocks.SPAWNER) {
			BlockEntity be = blockEntity(x, y, z);
			if (be instanceof ChestBlockEntity chest) {
				items = new ItemStack[chest.size()];
				for (int i = 0; i < items.length; i++) {
					ItemStack s = chest.getStack(i);
					items[i] = s == null ? null : s.copy();
				}
			} else if (be instanceof MobSpawnerBlockEntity spawner) {
				mob = spawnerMob(spawner);
			}
		}
		return new Snapshot(id, meta, items, mob);
	}

	/** Puts a block (and what was in it) back exactly. A chest is emptied first so nothing drops. */
	private void write(int x, int y, int z, Snapshot s) {
		World w = world();
		int id = w.getBlockId(x, y, z);
		if (id == BetaBlocks.CHEST && (s.id() != id)) {
			if (blockEntity(x, y, z) instanceof ChestBlockEntity chest) {
				for (int i = 0; i < chest.size(); i++) {
					chest.setStack(i, null);
				}
			}
		}
		if (id != s.id() || w.getBlockMeta(x, y, z) != s.meta()) {
			w.setBlock(x, y, z, s.id(), s.meta());
		}
		if (s.items() != null && blockEntity(x, y, z) instanceof ChestBlockEntity chest) {
			for (int i = 0; i < chest.size(); i++) {
				ItemStack it = i < s.items().length ? s.items()[i] : null;
				chest.setStack(i, it == null ? null : it.copy());
			}
		}
		if (s.mob() != null && blockEntity(x, y, z) instanceof MobSpawnerBlockEntity spawner) {
			spawner.setSpawnedEntityId(s.mob());
		}
	}

	/** Changes one block (or its contents) as one undo step. */
	private void edit(int x, int y, int z, Consumer<Snapshot[]> change) {
		if (y < 0 || y > 127) {
			say("Blocks go from height 0 to 127.");
			return;
		}
		Snapshot before = read(x, y, z);
		Snapshot[] next = {before};
		change.accept(next);
		Snapshot after = next[0];
		if (after == null || after.equals(before) && after.items() == null) {
			return;
		}
		try {
			write(x, y, z, after);
		} catch (RuntimeException e) {
			WorldPainter.LOGGER.error("Structure editor could not change the block at {} {} {}", x, y, z, e);
			say("Could not change that block: " + e.getMessage());
			return;
		}
		undo.push(List.of(new Change(x, y, z, before, read(x, y, z))));
		while (undo.size() > 200) {
			undo.removeLast();
		}
		redo.clear();
	}

	private void setSlot(int slot, ItemStack stack, String done) {
		int[] s = selected;
		if (s == null) {
			return;
		}
		edit(s[0], s[1], s[2], snap -> {
			ItemStack[] items = snap[0].items();
			if (items == null || slot < 0 || slot >= items.length) {
				snap[0] = null;
				return;
			}
			ItemStack[] copy = items.clone();
			copy[slot] = stack;
			snap[0] = new Snapshot(snap[0].id(), snap[0].meta(), copy, null);
		});
		if (done != null) {
			say(done);
		}
	}

	private void changeCount(ItemStack cur, int delta) {
		if (cur == null || selectedSlot < 0) {
			say("Click a slot with an item first.");
			return;
		}
		int max = Math.max(1, cur.getMaxCount());
		ItemStack copy = cur.copy();
		copy.count = MathUtil.clamp(copy.count + delta, 1, max);
		setSlot(selectedSlot, copy, null);
	}

	private void emptyChest() {
		int[] s = selected;
		if (s == null) {
			return;
		}
		edit(s[0], s[1], s[2], snap -> {
			if (snap[0].items() == null) {
				snap[0] = null;
				return;
			}
			snap[0] = new Snapshot(snap[0].id(), snap[0].meta(), new ItemStack[snap[0].items().length], null);
		});
		say("The chest is empty");
	}

	/** New random loot like a Beta dungeon chest (eight tries, each item in a random slot). */
	private void rollLoot() {
		int[] s = selected;
		if (s == null) {
			return;
		}
		DungeonFeatureAccessor loot;
		try {
			loot = (DungeonFeatureAccessor) new DungeonFeature();
		} catch (RuntimeException | LinkageError e) {
			say("Dungeon loot is not available");
			return;
		}
		Random random = new Random();
		edit(s[0], s[1], s[2], snap -> {
			if (snap[0].items() == null) {
				snap[0] = null;
				return;
			}
			ItemStack[] items = new ItemStack[snap[0].items().length];
			for (int i = 0; i < 8; i++) {
				ItemStack item = loot.worldpainter$chestItem(random);
				if (item != null) {
					items[random.nextInt(items.length)] = item;
				}
			}
			snap[0] = new Snapshot(snap[0].id(), snap[0].meta(), items, null);
		});
		say("New dungeon loot");
	}

	private void addItem(Entry e, boolean fullStack) {
		int[] s = selected;
		if (s == null || !(blockEntity(s[0], s[1], s[2]) instanceof ChestBlockEntity chest)) {
			return;
		}
		ItemStack stack = e.stack(1);
		if (fullStack) {
			stack.count = Math.max(1, stack.getMaxCount());
		}
		int slot = selectedSlot;
		int size = Math.min(chest.size(), 27);
		if (slot < 0 || slot >= size) {
			slot = -1;
			for (int i = 0; i < size; i++) {
				if (chest.getStack(i) == null) {
					slot = i;
					break;
				}
			}
		}
		if (slot < 0) {
			say("The chest is full: click a slot to replace what is in it.");
			return;
		}
		selectedSlot = slot;
		setSlot(slot, stack, "Put " + stack.count + " " + e.name() + " in the chest");
	}

	private void setMob(String mob) {
		int[] s = selected;
		if (s == null) {
			return;
		}
		edit(s[0], s[1], s[2], snap -> snap[0] = new Snapshot(snap[0].id(), snap[0].meta(), null, mob));
		say("The spawner now spawns " + mob);
	}

	private void useTool(BlockRay.Hit hit, boolean alt) {
		if (hit == null) {
			return;
		}
		World w = world();
		if (alt || tool == EditTool.PICK) {
			int id = w.getBlockId(hit.x(), hit.y(), hit.z());
			int meta = w.getBlockMeta(hit.x(), hit.y(), hit.z());
			if (id == 0 || NOT_PLACEABLE.contains(id)) {
				say("That block cannot be placed on its own.");
				return;
			}
			int m = variants(id).length > 1 ? meta : 0;
			placeBlock = new Entry(id, m, blockName(id, m));
			tool = EditTool.PLACE;
			say("Placing " + placeBlock.name());
			return;
		}
		switch (tool) {
			case SELECT -> {
				selected = new int[]{hit.x(), hit.y(), hit.z()};
				selectedSlot = -1;
			}
			case PLACE -> {
				Entry p = placing();
				int x = hit.placeX(), y = hit.placeY(), z = hit.placeZ();
				edit(x, y, z, snap -> snap[0] = new Snapshot(p.id(), p.meta(), null, null));
			}
			case BREAK -> breakBlock(hit.x(), hit.y(), hit.z());
			default -> {
			}
		}
	}

	private void breakBlock(int x, int y, int z) {
		if (selected != null && selected[0] == x && selected[1] == y && selected[2] == z) {
			selected = null;
		}
		edit(x, y, z, snap -> snap[0] = new Snapshot(0, 0, null, null));
	}

	private void undoAction() {
		List<Change> changes = undo.poll();
		if (changes == null) {
			say("Nothing to undo");
			return;
		}
		for (int i = changes.size() - 1; i >= 0; i--) {
			Change c = changes.get(i);
			write(c.x(), c.y(), c.z(), c.before());
		}
		redo.push(changes);
		say("Undone");
	}

	private void redoAction() {
		List<Change> changes = redo.poll();
		if (changes == null) {
			say("Nothing to redo");
			return;
		}
		for (Change c : changes) {
			write(c.x(), c.y(), c.z(), c.after());
		}
		undo.push(changes);
		say("Redone");
	}

	// ------------------------------------------------------------------ camera actions

	private void focusHovered() {
		if (hover != null) {
			cam.focus(hover.x() + 0.5, hover.y() + 0.5, hover.z() + 0.5);
		} else if (selected != null) {
			cam.focus(selected[0] + 0.5, selected[1] + 0.5, selected[2] + 0.5);
		}
	}

	private void toggleLight() {
		view.setSeeInDark(!view.seeInDark());
		say(view.seeInDark() ? "See in the dark: on (only the picture changes)" : "See in the dark: off");
	}

	/**
	 * Looks for the nearest spawner (else chest) around the cross, selects it and turns the camera
	 * to it: dungeons are under the ground, this finds them.
	 */
	private void findNearby(boolean tellIfNone) {
		World w = world();
		if (w == null) {
			return;
		}
		int cx = (int) Math.floor(cam.pivotX), cy = (int) Math.floor(cam.pivotY), cz = (int) Math.floor(cam.pivotZ);
		int[] best = null;
		boolean bestSpawner = false;
		long bestD = Long.MAX_VALUE;
		for (int x = cx - FIND_RADIUS; x <= cx + FIND_RADIUS; x++) {
			for (int z = cz - FIND_RADIUS; z <= cz + FIND_RADIUS; z++) {
				for (int y = 1; y < 128; y++) {
					int id = w.getBlockId(x, y, z);
					if (id != BetaBlocks.SPAWNER && id != BetaBlocks.CHEST) {
						continue;
					}
					boolean spawner = id == BetaBlocks.SPAWNER;
					long d = (long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz) + (long) (y - cy) * (y - cy) / 4;
					if ((spawner && !bestSpawner) || (spawner == bestSpawner && d < bestD)) {
						best = new int[]{x, y, z};
						bestD = d;
						bestSpawner = spawner;
					}
				}
			}
		}
		if (best == null) {
			if (tellIfNone) {
				say("No spawner or chest within " + FIND_RADIUS + " blocks of the cross.");
			}
			return;
		}
		selected = best;
		selectedSlot = -1;
		tool = EditTool.SELECT;
		cam.focus(best[0] + 0.5, best[1] + 0.5, best[2] + 0.5);
		cam.distance = MathUtil.clamp(cam.distance, 8.0, 16.0);
		cam.pitch = Math.max(cam.pitch, 30f);
		say((bestSpawner ? "Found a dungeon" : "Found a chest") + " at " + best[0] + " " + best[1] + " " + best[2]
				+ (view.seeInDark() ? "" : " - L: see in the dark"));
	}

	// ------------------------------------------------------------------ input

	private static boolean shiftDown() {
		return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
	}

	private static boolean controlDown() {
		return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
				|| Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA);
	}

	private static boolean altDown() {
		return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
	}

	@Override
	public void onMouseEvent() {
		double mx = Mouse.getEventX() * width / (double) mc.displayWidth;
		double my = height - Mouse.getEventY() * height / (double) mc.displayHeight - 1;
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			mouseScrolled(mx, my, wheel > 0 ? 1 : -1);
		}
		int button = Mouse.getEventButton();
		if (button >= 0) {
			if (Mouse.getEventButtonState()) {
				mousePressed(mx, my, button);
			} else {
				hits.release();
				orbiting = false;
				panning = false;
			}
		} else if (Mouse.getEventDX() != 0 || Mouse.getEventDY() != 0) {
			double dx = Mouse.getEventDX() * width / (double) mc.displayWidth;
			double dy = -Mouse.getEventDY() * height / (double) mc.displayHeight;
			if (orbiting) {
				if (controlDown()) {
					cam.zoom(-dy * 0.05);
				} else {
					cam.orbit(dx, dy);
				}
			} else if (panning) {
				cam.pan(dx, dy, height);
			} else if (Mouse.isButtonDown(MOUSE_LEFT)) {
				hits.drag(mx, my);
			}
		}
	}

	private void mousePressed(double mx, double my, int button) {
		if (showHelp) {
			showHelp = false;
			return;
		}
		boolean inSearch = search.contains(mx, my);
		search.setFocused(inSearch);
		if (inSearch) {
			return;
		}
		if (hits.click(mx, my, button)) {
			return;
		}
		if (!inViewport(mx, my) || !view.active()) {
			return;
		}
		if (button == MOUSE_RIGHT || button == MOUSE_MIDDLE || (button == MOUSE_LEFT && Keyboard.isKeyDown(Keyboard.KEY_SPACE))) {
			if (shiftDown()) {
				panning = true;
			} else {
				orbiting = true;
			}
			return;
		}
		if (button == MOUSE_LEFT) {
			BlockRay.Hit hit = view.pick(mx, my, false, true);
			if (hit == null) {
				say("Nothing there (the world shows what is loaded around the camera).");
				return;
			}
			useTool(hit, altDown());
		}
	}

	private void mouseScrolled(double mx, double my, double amount) {
		if (showHelp) {
			return;
		}
		if (hits.scroll(mx, my, amount)) {
			return;
		}
		if (inViewport(mx, my)) {
			cam.zoom(amount > 0 ? 1 : -1);
		}
	}

	@Override
	public void onKeyboardEvent() {
		int key = Keyboard.getEventKey();
		if (Keyboard.getEventKeyState()) {
			if (key == Keyboard.KEY_F11) {
				mc.toggleFullscreen();
				return;
			}
			keyDown(Keyboard.getEventCharacter(), key);
		} else {
			heldKeys.remove(key);
		}
	}

	private void keyDown(char c, int key) {
		if (search.focused()) {
			if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_RETURN) {
				search.setFocused(false);
				return;
			}
			search.key(c, key, controlDown(), controlDown() && key == Keyboard.KEY_V ? Screen.getClipboard() : null);
			return;
		}
		if (showHelp && (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_F1)) {
			showHelp = false;
			return;
		}
		if (controlDown()) {
			if (key == Keyboard.KEY_Z) {
				if (shiftDown()) {
					redoAction();
				} else {
					undoAction();
				}
				return;
			}
			if (key == Keyboard.KEY_Y) {
				redoAction();
				return;
			}
		}
		switch (key) {
			case Keyboard.KEY_V -> tool = EditTool.SELECT;
			case Keyboard.KEY_B -> tool = EditTool.PLACE;
			case Keyboard.KEY_X -> tool = EditTool.BREAK;
			case Keyboard.KEY_I -> tool = EditTool.PICK;
			case Keyboard.KEY_F -> focusHovered();
			case Keyboard.KEY_L -> toggleLight();
			case Keyboard.KEY_HOME -> findNearby(true);
			case Keyboard.KEY_1, Keyboard.KEY_NUMPAD1 -> cam.viewFront();
			case Keyboard.KEY_3, Keyboard.KEY_NUMPAD3 -> cam.viewSide();
			case Keyboard.KEY_7, Keyboard.KEY_NUMPAD7 -> cam.viewTop();
			case Keyboard.KEY_F1 -> showHelp = !showHelp;
			case Keyboard.KEY_DELETE -> {
				if (selected != null) {
					breakBlock(selected[0], selected[1], selected[2]);
				}
			}
			case Keyboard.KEY_ESCAPE -> close();
			default -> {
				if (isMoveKey(key)) {
					heldKeys.add(key);
				}
			}
		}
	}

	private static boolean isMoveKey(int key) {
		return key == Keyboard.KEY_W || key == Keyboard.KEY_A || key == Keyboard.KEY_S || key == Keyboard.KEY_D
				|| key == Keyboard.KEY_Q || key == Keyboard.KEY_E
				|| key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN || key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT;
	}

	private void flyWithKeys() {
		long now = System.nanoTime();
		double dt = lastNanos == 0 ? 0 : Math.min(0.1, (now - lastNanos) / 1e9);
		lastNanos = now;
		// A key released while another screen had the keyboard is not seen: check what is really held.
		heldKeys.removeIf(k -> !Keyboard.isKeyDown(k));
		if (heldKeys.isEmpty() || search.focused()) {
			return;
		}
		double speed = Math.max(4, cam.distance * 0.8) * dt * (shiftDown() ? 3 : 1);
		double f = 0, r = 0, u = 0;
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
		if (heldKeys.contains(Keyboard.KEY_E)) {
			u += speed;
		}
		if (heldKeys.contains(Keyboard.KEY_Q)) {
			u -= speed;
		}
		if (f != 0 || r != 0 || u != 0) {
			cam.fly(f, r, u);
		}
	}

	@Override
	public void tick() {
		ticks++;
	}

	// ------------------------------------------------------------------ closing

	private void close() {
		closing = true;
		view.setSeeInDark(false);
		if (parent != null) {
			// The painter takes the view back (and keeps looking from here).
			mc.setScreen(parent);
			return;
		}
		view.exit();
		mc.setScreen(null);
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
		if (closing) {
			return;
		}
		// Closed some other way: leave the game as it was.
		view.setSeeInDark(false);
		if (parent instanceof PainterScreen painter) {
			painter.editorAbandoned();
		} else if (ownsView) {
			view.exit();
		}
	}
}
