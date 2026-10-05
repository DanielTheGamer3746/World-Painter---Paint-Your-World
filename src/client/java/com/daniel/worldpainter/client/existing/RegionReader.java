package com.daniel.worldpainter.client.existing;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads one region file (.mca) of an existing world into a small top-down raster: surface height,
 * water depth and surface biome per block column.
 */
final class RegionReader {
	static final short UNKNOWN = Short.MIN_VALUE;
	private static volatile Constructor<?> lz4Constructor;
	private static volatile boolean lz4Missing;

	private RegionReader() {
	}

	/** Result for a 512x512 region. */
	static final class Raster {
		final short[] surface = new short[512 * 512];
		final short[] floor = new short[512 * 512];
		final short[] biome = new short[512 * 512];
		final List<ExistingStructure> structures = new java.util.ArrayList<>();
		int chunks;

		Raster() {
			java.util.Arrays.fill(surface, UNKNOWN);
			java.util.Arrays.fill(floor, UNKNOWN);
		}
	}

	interface BiomeIds {
		short idOf(String biome);
	}

	static Raster read(Path file, int worldMinY, int worldHeight, BiomeIds biomeIds) throws IOException {
		Raster raster = new Raster();
		if (!Files.isRegularFile(file) || Files.size(file) < 8192) {
			return raster;
		}
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
			byte[] header = new byte[4096];
			raf.readFully(header);
			long fileLength = raf.length();
			for (int idx = 0; idx < 1024; idx++) {
				int loc = ((header[idx * 4] & 0xFF) << 24) | ((header[idx * 4 + 1] & 0xFF) << 16)
						| ((header[idx * 4 + 2] & 0xFF) << 8) | (header[idx * 4 + 3] & 0xFF);
				if (loc == 0) {
					continue;
				}
				long offset = (long) (loc >>> 8) * 4096L;
				int sectors = loc & 0xFF;
				if (offset < 8192 || offset + 5 > fileLength || sectors == 0) {
					continue;
				}
				try {
					raf.seek(offset);
					int length = raf.readInt();
					if (length <= 1 || length > sectors * 4096) {
						continue;
					}
					int compression = raf.readUnsignedByte();
					byte[] data = new byte[length - 1];
					raf.readFully(data);
					InputStream chunkIn;
					if ((compression & 128) != 0) {
						int cx = idx & 31, cz = idx >> 5;
						Path region = file.getFileName();
						String[] parts = region.toString().split("\\.");
						int rx = Integer.parseInt(parts[1]);
						int rz = Integer.parseInt(parts[2]);
						Path external = file.resolveSibling("c." + ((rx << 5) + cx) + "." + ((rz << 5) + cz) + ".mcc");
						if (!Files.isRegularFile(external)) {
							continue;
						}
						chunkIn = decompress(compression & 127, Files.newInputStream(external));
					} else {
						chunkIn = decompress(compression, new ByteArrayInputStream(data));
					}
					if (chunkIn == null) {
						continue;
					}
					Map<String, Object> root;
					try (DataInputStream in = new DataInputStream(new BufferedInputStream(chunkIn))) {
						root = MiniNbt.readRoot(in);
					}
					readChunk(root, idx & 31, idx >> 5, worldMinY, worldHeight, biomeIds, raster);
				} catch (IOException | RuntimeException e) {
					// A broken chunk only leaves a hole in the preview.
				}
			}
		}
		return raster;
	}

	private static InputStream decompress(int type, InputStream raw) throws IOException {
		return switch (type) {
			case 1 -> new GZIPInputStream(raw);
			case 2 -> new InflaterInputStream(raw);
			case 3 -> raw;
			case 4 -> lz4(raw);
			default -> null;
		};
	}

	/** LZ4 region compression is optional in Minecraft; the library is loaded by reflection. */
	private static InputStream lz4(InputStream raw) {
		if (lz4Missing) {
			return null;
		}
		try {
			Constructor<?> c = lz4Constructor;
			if (c == null) {
				c = Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream.class);
				lz4Constructor = c;
			}
			return (InputStream) c.newInstance(raw);
		} catch (ReflectiveOperationException e) {
			lz4Missing = true;
			return null;
		}
	}

	private static void readChunk(Map<String, Object> root, int lcx, int lcz, int worldMinY, int worldHeight,
								  BiomeIds biomeIds, Raster raster) {
		readStructures(root, raster);
		Map<String, Object> heightmaps = MiniNbt.compound(root, "Heightmaps");
		if (heightmaps == null) {
			return;
		}
		long[] surface = MiniNbt.longs(heightmaps, "WORLD_SURFACE");
		long[] floor = MiniNbt.longs(heightmaps, "OCEAN_FLOOR");
		if (surface == null) {
			surface = MiniNbt.longs(heightmaps, "WORLD_SURFACE_WG");
		}
		if (floor == null) {
			floor = MiniNbt.longs(heightmaps, "OCEAN_FLOOR_WG");
		}
		if (surface == null) {
			return;
		}
		Integer yPos = MiniNbt.integer(root, "yPos");
		int minY = yPos != null ? yPos * 16 : worldMinY;
		int bits = ceilLog2(worldHeight + 1);
		List<Object> sections = MiniNbt.list(root, "sections");
		raster.chunks++;

		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int i = z * 16 + x;
				int top = unpack(surface, i, bits) + minY - 1;
				int bottom = floor != null ? unpack(floor, i, bits) + minY - 1 : top;
				int px = (lcx << 4) + x;
				int pz = (lcz << 4) + z;
				int ri = (pz << 9) | px;
				raster.surface[ri] = (short) top;
				raster.floor[ri] = (short) bottom;
				String biome = sections != null ? biomeAt(sections, x, top, z) : null;
				raster.biome[ri] = biome != null ? biomeIds.idOf(biome) : 0;
			}
		}
	}

	/** Structure starts stored in this chunk (a structure is saved once, in its start chunk). */
	private static void readStructures(Map<String, Object> root, Raster raster) {
		Map<String, Object> structures = MiniNbt.compound(root, "structures");
		if (structures == null) {
			return;
		}
		Map<String, Object> starts = MiniNbt.compound(structures, "starts");
		if (starts == null) {
			starts = MiniNbt.compound(structures, "Starts");
		}
		if (starts == null) {
			return;
		}
		for (Map.Entry<String, Object> e : starts.entrySet()) {
			if (!(e.getValue() instanceof Map<?, ?> rawStart)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> start = (Map<String, Object>) rawStart;
			String id = MiniNbt.string(start, "id");
			if (id == null || id.equals("INVALID")) {
				continue;
			}
			Integer cx = MiniNbt.integer(start, "ChunkX");
			Integer cz = MiniNbt.integer(start, "ChunkZ");
			if (cx == null || cz == null) {
				continue;
			}
			int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			List<Object> children = MiniNbt.list(start, "Children");
			if (children != null) {
				for (Object c : children) {
					if (c instanceof Map<?, ?> piece && piece.get("BB") instanceof int[] bb && bb.length == 6) {
						minX = Math.min(minX, bb[0]);
						minZ = Math.min(minZ, bb[2]);
						maxX = Math.max(maxX, bb[3]);
						maxZ = Math.max(maxZ, bb[5]);
					}
				}
			}
			if (minX > maxX) {
				minX = cx << 4;
				minZ = cz << 4;
				maxX = minX + 15;
				maxZ = minZ + 15;
			}
			raster.structures.add(new ExistingStructure(id, cx, cz, minX, minZ, maxX, maxZ));
		}
	}

	private static String biomeAt(List<Object> sections, int x, int y, int z) {
		int sectionY = Math.floorDiv(y, 16);
		for (Object o : sections) {
			if (!(o instanceof Map<?, ?> raw)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> section = (Map<String, Object>) raw;
			Integer sy = MiniNbt.integer(section, "Y");
			if (sy == null || sy != sectionY) {
				continue;
			}
			Map<String, Object> biomes = MiniNbt.compound(section, "biomes");
			if (biomes == null) {
				return null;
			}
			List<Object> palette = MiniNbt.list(biomes, "palette");
			if (palette == null || palette.isEmpty()) {
				return null;
			}
			if (palette.size() == 1) {
				return palette.getFirst() instanceof String s ? s : null;
			}
			long[] data = MiniNbt.longs(biomes, "data");
			if (data == null) {
				return palette.getFirst() instanceof String s ? s : null;
			}
			int bits = ceilLog2(palette.size());
			int qx = x >> 2, qy = (y & 15) >> 2, qz = z >> 2;
			int idx = (qy << 4) | (qz << 2) | qx;
			int value = unpack(data, idx, bits);
			if (value < 0 || value >= palette.size()) {
				return null;
			}
			return palette.get(value) instanceof String s ? s : null;
		}
		return null;
	}

	private static int unpack(long[] data, int index, int bits) {
		int perLong = 64 / bits;
		int li = index / perLong;
		if (li >= data.length) {
			return 0;
		}
		int shift = (index % perLong) * bits;
		return (int) ((data[li] >>> shift) & ((1L << bits) - 1));
	}

	private static int ceilLog2(int v) {
		return v <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(v - 1);
	}
}
