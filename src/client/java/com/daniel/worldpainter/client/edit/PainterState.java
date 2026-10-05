package com.daniel.worldpainter.client.edit;

import com.daniel.worldpainter.client.PainterTarget;
import com.daniel.worldpainter.client.existing.ExistingWorldLayer;
import com.daniel.worldpainter.client.templates.Template;
import com.daniel.worldpainter.client.tools.Brush;
import com.daniel.worldpainter.client.tools.Tool;
import com.daniel.worldpainter.client.tools.Tools;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;

/** Everything the editor knows: the painted world, the chosen tool, colors, brush and so on. */
public final class PainterState {
	public static final int SEA_LEVEL = 63;
	public static final int MIN_Y = -64;
	public static final int MAX_Y = 319;

	public enum HeightMode {
		RAISE("Raise"), LOWER("Lower"), SMOOTH("Smooth"), FLATTEN("Flatten"), NOISE("Roughen");

		public final String label;

		HeightMode(String label) {
			this.label = label;
		}
	}

	public enum StructureMode {
		PLACE("Place"), REMOVE("Remove"), ZONE("No-structure zone"), EDIT("3D Edit");

		public final String label;

		StructureMode(String label) {
			this.label = label;
		}
	}

	public enum Sculpt3DMode {
		ADD("Add"), CARVE("Carve"), RESTORE("Restore"), ISLAND("Island");

		public final String label;

		Sculpt3DMode(String label) {
			this.label = label;
		}
	}

	public enum ViewMode {
		COMPOSITE("Shaded"), BIOME("Biomes"), HEIGHT("Height"), SURFACE("Surface"), FLUID("Water/Lava");

		public final String label;

		ViewMode(String label) {
			this.label = label;
		}
	}

	public final PainterTarget target;
	/** The dimension being painted (decides which layers exist). */
	public final PaintDimension dimension;
	public final PaintWorld world;
	public final EditSession session;
	public final ExistingWorldLayer existing;

	public Layer layer = Layer.BIOME;
	public String biome;
	public String surface = "minecraft:grass_block";
	public int height = 80;
	/** Height used by sculpt tools where nothing is painted and the existing world is unknown. */
	public int baseHeight;
	public HeightMode heightMode = HeightMode.RAISE;
	public FluidType fluidType = FluidType.WATER;
	public int fluidLevel = SEA_LEVEL - 1;

	public final Brush brush = new Brush();
	public Tool tool = Tools.BRUSH;

	/** Current selection (inclusive block bounds) or null. */
	public int[] selection;

	public Template template;
	public int templateSize = 512;
	public int templateRotation;
	public long templateSeed = System.nanoTime();

	public String structure = "minecraft:village_plains";
	public StructureMode structureMode = StructureMode.PLACE;
	/** Block X/Z the user clicked to open the 3D structure editor (handled by the screen). */
	public int[] pendingEdit3d;
	public boolean showStructures = true;
	/** Blocks per GUI pixel of the map (kept up to date by the screen, used for click tolerance). */
	public double blocksPerGuiPixel = 1;

	public Sculpt3DMode sculpt3dMode = Sculpt3DMode.ADD;
	/**
	 * Set while the 3D view shows the real world: sculpted blocks are then also changed in the world
	 * right away (not only in the design).
	 */
	public LiveSculpt liveSculpt;

	/** Receives the blocks of each sculpt dab (positions packed with {@code SculptOps.pack}). */
	public interface LiveSculpt {
		void sculpted(long[] positions, int count, boolean add);
	}
	/** Floating islands: width in blocks and height of their top above the clicked ground. */
	public int islandSize = 48;
	public int islandHeight = 40;

	public ViewMode viewMode = ViewMode.COMPOSITE;
	public boolean showGrid = true;
	public boolean showExisting = true;

	private long cachedKey = Long.MIN_VALUE;
	private PaintTile cachedTile;
	private int cachedRevisionCheck = -1;

	public PainterState(PainterTarget target, PaintWorld world, ExistingWorldLayer existing) {
		this.target = target;
		this.dimension = target.dimension();
		this.world = world;
		this.existing = existing;
		this.session = new EditSession(world, target.existingWorld());
		this.biome = dimension.defaultBiome;
		this.baseHeight = dimension.baseHeight;
	}

	/** Layers that can be painted in this dimension, in tab order. */
	public java.util.List<Layer> layers() {
		java.util.List<Layer> l = new java.util.ArrayList<>();
		for (Layer layer : Layer.values()) {
			if (dimension.allows(layer)) {
				l.add(layer);
			}
		}
		return l;
	}

	public PaintValue currentValue() {
		return switch (layer) {
			case BIOME -> PaintValue.biome(biome);
			case SURFACE -> PaintValue.surface(surface);
			case HEIGHT -> PaintValue.height(height);
			case FLUID -> PaintValue.fluid(fluidType, fluidLevel);
		};
	}

	// ---- reading ----

	/** The painted tile under a block, cached for fast repeated lookups. May be null. */
	public PaintTile tileAt(int x, int z) {
		long key = com.daniel.worldpainter.data.TileKey.forBlock(x, z);
		int changes = session.changeCount();
		if (key != cachedKey || changes != cachedRevisionCheck) {
			cachedKey = key;
			cachedRevisionCheck = changes;
			cachedTile = world.getForBlock(x, z);
		}
		return cachedTile;
	}

	public String paintedBiome(int x, int z) {
		PaintTile t = tileAt(x, z);
		return t == null ? null : t.biomeAt(PaintTile.indexForBlock(x, z));
	}

	public String paintedSurface(int x, int z) {
		PaintTile t = tileAt(x, z);
		return t == null ? null : t.surfaceAt(PaintTile.indexForBlock(x, z));
	}

	public short paintedHeight(int x, int z) {
		PaintTile t = tileAt(x, z);
		return t == null ? PaintTile.NO_HEIGHT : t.height.get(PaintTile.indexForBlock(x, z));
	}

	public short paintedFluid(int x, int z) {
		PaintTile t = tileAt(x, z);
		return t == null ? FluidType.NONE : t.fluid.get(PaintTile.indexForBlock(x, z));
	}

	/**
	 * Existing world ground height at a block, or {@link ExistingWorldLayer#UNKNOWN}. Unknown in the
	 * Nether (the saved height is the bedrock roof) and where the End has no ground (void).
	 */
	public short existingHeight(int x, int z) {
		if (existing == null || !dimension.existingHeights) {
			return ExistingWorldLayer.UNKNOWN;
		}
		ExistingWorldLayer.Region r = existing.region(x >> 9, z >> 9, false);
		if (r == null) {
			return ExistingWorldLayer.UNKNOWN;
		}
		short h = r.floor(x & 511, z & 511);
		return dimension.voidByDefault && h < 1 ? ExistingWorldLayer.UNKNOWN : h;
	}

	/** Ground height where it is really known (painted, or saved in an existing world), else Integer.MIN_VALUE. */
	public int knownHeight(int x, int z) {
		short h = paintedHeight(x, z);
		if (h != PaintTile.NO_HEIGHT && dimension.allows(Layer.HEIGHT)) {
			return h;
		}
		short e = existingHeight(x, z);
		return e != ExistingWorldLayer.UNKNOWN ? e : Integer.MIN_VALUE;
	}

	public String existingBiome(int x, int z) {
		if (existing == null) {
			return null;
		}
		ExistingWorldLayer.Region r = existing.region(x >> 9, z >> 9, false);
		return r == null ? null : existing.biomeName(r.biomeIndex(x & 511, z & 511));
	}

	/** Height to start sculpting from: painted, else the existing world, else the base height. */
	public int effectiveHeight(int x, int z) {
		short h = paintedHeight(x, z);
		if (h != PaintTile.NO_HEIGHT) {
			return h;
		}
		return unpaintedHeight(x, z);
	}

	/** Height of a block ignoring paint: the existing world if known, else the base height. */
	public int unpaintedHeight(int x, int z) {
		short e = existingHeight(x, z);
		return e != ExistingWorldLayer.UNKNOWN ? e : baseHeight;
	}

	// ---- status line ----

	public String message;
	public long messageUntil;

	public void say(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 4000;
	}

	/** Comparable key of the current layer's value at a block (for flood fill / pick). */
	public long valueKey(int x, int z, Layer l) {
		PaintTile t = tileAt(x, z);
		if (t == null) {
			return Long.MIN_VALUE;
		}
		int i = PaintTile.indexForBlock(x, z);
		return switch (l) {
			case BIOME -> {
				String s = t.biomeAt(i);
				yield s == null ? Long.MIN_VALUE : s.hashCode();
			}
			case SURFACE -> {
				String s = t.surfaceAt(i);
				yield s == null ? Long.MIN_VALUE : s.hashCode();
			}
			case HEIGHT -> {
				short h = t.height.get(i);
				yield h == PaintTile.NO_HEIGHT ? Long.MIN_VALUE : h;
			}
			case FLUID -> {
				short f = t.fluid.get(i);
				yield f == FluidType.NONE ? Long.MIN_VALUE : f;
			}
		};
	}

	public static boolean inWorld(int x, int z) {
		return PaintWorld.inWorld(x, z);
	}
}
