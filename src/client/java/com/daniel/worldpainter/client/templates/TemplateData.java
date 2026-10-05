package com.daniel.worldpainter.client.templates;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.storage.IoUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** A rectangle of painted layers that can be stamped anywhere on the map. */
public final class TemplateData {
	private static final int MAGIC = 0x57505450; // "WPTP"
	public static final int MAX_SIDE = 4096;

	public final int width;
	public final int depth;
	final short[] biome;
	final List<String> biomeIds = new ArrayList<>();
	private final Map<String, Short> biomeLookup = new HashMap<>();
	final short[] height;
	final short[] surface;
	final List<String> surfaceIds = new ArrayList<>();
	private final Map<String, Short> surfaceLookup = new HashMap<>();
	final short[] fluid;

	public TemplateData(int width, int depth) {
		if (width < 1 || depth < 1 || width > MAX_SIDE || depth > MAX_SIDE) {
			throw new IllegalArgumentException("Template size must be 1.." + MAX_SIDE);
		}
		this.width = width;
		this.depth = depth;
		int n = width * depth;
		biome = new short[n];
		height = new short[n];
		surface = new short[n];
		fluid = new short[n];
		Arrays.fill(height, PaintTile.NO_HEIGHT);
		Arrays.fill(fluid, FluidType.NONE);
		biomeIds.add("");
		surfaceIds.add("");
	}

	private int i(int x, int z) {
		return z * width + x;
	}

	public void setBiome(int x, int z, String id) {
		biome[i(x, z)] = id == null ? 0 : biomeLookup.computeIfAbsent(id, k -> {
			biomeIds.add(k);
			return (short) (biomeIds.size() - 1);
		});
	}

	public void setSurface(int x, int z, String id) {
		surface[i(x, z)] = id == null ? 0 : surfaceLookup.computeIfAbsent(id, k -> {
			surfaceIds.add(k);
			return (short) (surfaceIds.size() - 1);
		});
	}

	public void setHeight(int x, int z, int h) {
		height[i(x, z)] = (short) Math.clamp(h, PainterState.MIN_Y, PainterState.MAX_Y);
	}

	public void setFluid(int x, int z, FluidType type, int level) {
		fluid[i(x, z)] = FluidType.pack(type, level);
	}

	/** Size after rotating by {@code quarterTurns} * 90 degrees. */
	public int rotatedWidth(int quarterTurns) {
		return (quarterTurns & 1) == 0 ? width : depth;
	}

	public int rotatedDepth(int quarterTurns) {
		return (quarterTurns & 1) == 0 ? depth : width;
	}

	/** Paints this template centered on (cx, cz). Only layers that are set in the template are written. */
	public void stamp(PainterState s, int cx, int cz, int quarterTurns) {
		int rot = quarterTurns & 3;
		int w = rotatedWidth(rot), d = rotatedDepth(rot);
		int x0 = cx - w / 2, z0 = cz - d / 2;
		// Only the layers this dimension has (no heights or water under the Nether's roof).
		boolean heights = s.dimension.allows(Layer.HEIGHT);
		boolean surfaces = s.dimension.allows(Layer.SURFACE);
		boolean fluids = s.dimension.allows(Layer.FLUID);
		s.session.begin("Stamp");
		Ops.forPixels(s.session, x0, z0, x0 + w - 1, z0 + d - 1, (t, i, x, z) -> {
			int rx = x - x0, rz = z - z0;
			int sx, sz;
			switch (rot) {
				case 1 -> { sx = rz; sz = depth - 1 - rx; }
				case 2 -> { sx = width - 1 - rx; sz = depth - 1 - rz; }
				case 3 -> { sx = width - 1 - rz; sz = rx; }
				default -> { sx = rx; sz = rz; }
			}
			int si = sz * width + sx;
			if (biome[si] != 0) {
				t.biome.set(i, t.biomePalette.indexOf(biomeIds.get(biome[si])));
			}
			if (heights && height[si] != PaintTile.NO_HEIGHT) {
				t.height.set(i, height[si]);
			}
			if (surfaces && surface[si] != 0) {
				t.surface.set(i, t.surfacePalette.indexOf(surfaceIds.get(surface[si])));
			}
			if (fluids && fluid[si] != FluidType.NONE) {
				t.fluid.set(i, fluid[si]);
			}
		});
		s.session.end();
	}

	/** Copies the painted content of a rectangle (inclusive bounds). */
	public static TemplateData capture(PainterState s, int minX, int minZ, int maxX, int maxZ) {
		TemplateData d = new TemplateData(maxX - minX + 1, maxZ - minZ + 1);
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				PaintTile t = s.tileAt(x, z);
				if (t == null) {
					continue;
				}
				int ti = PaintTile.indexForBlock(x, z);
				int lx = x - minX, lz = z - minZ;
				String b = t.biomeAt(ti);
				if (b != null) {
					d.setBiome(lx, lz, b);
				}
				String su = t.surfaceAt(ti);
				if (su != null) {
					d.setSurface(lx, lz, su);
				}
				d.height[d.i(lx, lz)] = t.height.get(ti);
				d.fluid[d.i(lx, lz)] = t.fluid.get(ti);
			}
		}
		return d;
	}

	public boolean isEmpty() {
		for (int i = 0; i < biome.length; i++) {
			if (biome[i] != 0 || height[i] != PaintTile.NO_HEIGHT || surface[i] != 0 || fluid[i] != FluidType.NONE) {
				return false;
			}
		}
		return true;
	}

	// ---- files ----

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = Files.newOutputStream(tmp);
			 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw), 1 << 16))) {
			out.writeInt(MAGIC);
			out.writeInt(1);
			out.writeInt(width);
			out.writeInt(depth);
			writeIds(out, biomeIds);
			writeIds(out, surfaceIds);
			for (short[] layer : new short[][]{biome, height, surface, fluid}) {
				for (short v : layer) {
					out.writeShort(v);
				}
			}
		}
		IoUtil.replace(tmp, file);
	}

	public static TemplateData load(Path file) throws IOException {
		try (InputStream raw = Files.newInputStream(file);
			 DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw), 1 << 16))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a World Painter template");
			}
			in.readInt();
			TemplateData d = new TemplateData(in.readInt(), in.readInt());
			List<String> b = readIds(in);
			List<String> su = readIds(in);
			for (int i = 1; i < b.size(); i++) {
				d.biomeIds.add(b.get(i));
				d.biomeLookup.put(b.get(i), (short) i);
			}
			for (int i = 1; i < su.size(); i++) {
				d.surfaceIds.add(su.get(i));
				d.surfaceLookup.put(su.get(i), (short) i);
			}
			for (short[] layer : new short[][]{d.biome, d.height, d.surface, d.fluid}) {
				for (int i = 0; i < layer.length; i++) {
					layer[i] = in.readShort();
				}
			}
			return d;
		}
	}

	private static void writeIds(DataOutputStream out, List<String> ids) throws IOException {
		out.writeInt(ids.size());
		for (String s : ids) {
			out.writeUTF(s);
		}
	}

	private static List<String> readIds(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 1 || n > Short.MAX_VALUE) {
			throw new IOException("Bad template palette");
		}
		List<String> ids = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			ids.add(in.readUTF());
		}
		return ids;
	}
}
