package com.daniel.worldpainter.client.existing;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads one Beta region file ({@code r.X.Z.mcr}) of an existing world into a small top-down raster:
 * surface height, ground height, the top block's color and the (natural) biome per block column,
 * plus the dungeons (their monster spawners). Chunks saved by Beta itself and by StationAPI are read.
 */
final class RegionReader {
	static final short UNKNOWN = Short.MIN_VALUE;

	private RegionReader() {
	}

	/** Result for a 512x512 region. */
	static final class Raster {
		final short[] surface = new short[512 * 512];
		final short[] floor = new short[512 * 512];
		final short[] biome = new short[512 * 512];
		final int[] color = new int[512 * 512];
		final List<ExistingStructure> structures = new ArrayList<>();
		int chunks;

		Raster() {
			Arrays.fill(surface, UNKNOWN);
			Arrays.fill(floor, UNKNOWN);
		}
	}

	interface BiomeIds {
		short idOf(String biome);
	}

	/** {@code biomes} may be null (seed unknown): then no biomes are filled in. */
	static Raster read(Path file, NaturalBiomes biomes, BiomeIds biomeIds) throws IOException {
		Raster raster = new Raster();
		if (!Files.isRegularFile(file) || Files.size(file) < 8192) {
			return raster;
		}
		String[] parts = file.getFileName().toString().split("\\.");
		int rx = Integer.parseInt(parts[1]);
		int rz = Integer.parseInt(parts[2]);
		String[] area = new String[256];
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
					InputStream chunkIn = compression == 1 ? new GZIPInputStream(new ByteArrayInputStream(data))
							: compression == 2 ? new InflaterInputStream(new ByteArrayInputStream(data)) : null;
					if (chunkIn == null) {
						continue;
					}
					Map<String, Object> root;
					try (DataInputStream in = new DataInputStream(new BufferedInputStream(chunkIn))) {
						root = MiniNbt.readRoot(in);
					}
					Map<String, Object> level = MiniNbt.compound(root, "Level");
					if (level == null) {
						continue;
					}
					int lcx = idx & 31, lcz = idx >> 5;
					String[] chunkBiomes = biomes == null ? null
							: biomes.area(((rx << 5) + lcx) << 4, ((rz << 5) + lcz) << 4, area);
					readChunk(level, lcx, lcz, chunkBiomes, biomeIds, raster);
				} catch (IOException | RuntimeException e) {
					// A broken chunk only leaves a hole in the preview.
				}
			}
		}
		return raster;
	}

	private static void readChunk(Map<String, Object> level, int lcx, int lcz, String[] chunkBiomes, BiomeIds biomeIds, Raster raster) {
		byte[] blocks = MiniNbt.bytes(level, "Blocks");
		if (blocks == null || blocks.length < 32768) {
			// Saved by StationAPI: blocks by name in sections.
			blocks = StationChunks.blocks(level);
		}
		if (blocks == null) {
			return;
		}
		readDungeons(level, raster);
		raster.chunks++;
		ColumnScan scan = new ColumnScan();
		byte[] b = blocks;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int base = x << 11 | z << 7;
				String biome = chunkBiomes == null ? null : chunkBiomes[x * 16 + z];
				if (!scan.scan(y -> b[base + y] & 255, 127, biome)) {
					continue;
				}
				int ri = (((lcz << 4) + z) << 9) | ((lcx << 4) + x);
				raster.surface[ri] = (short) scan.surface;
				raster.floor[ri] = (short) scan.ground;
				raster.color[ri] = scan.color;
				raster.biome[ri] = biome != null ? biomeIds.idOf(biome) : 0;
			}
		}
	}

	/** Beta's only structure is the dungeon; its monster spawner is a block entity of the chunk. */
	private static void readDungeons(Map<String, Object> level, Raster raster) {
		List<Object> entities = MiniNbt.list(level, "TileEntities");
		if (entities == null) {
			return;
		}
		for (Object o : entities) {
			if (!(o instanceof Map<?, ?> raw)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> be = (Map<String, Object>) raw;
			String type = MiniNbt.string(be, "id");
			if (type == null || !type.toLowerCase(java.util.Locale.ROOT).replace("_", "").contains("mobspawner")) {
				continue;
			}
			Integer x = MiniNbt.integer(be, "x"), z = MiniNbt.integer(be, "z");
			if (x == null || z == null) {
				continue;
			}
			String mob = MiniNbt.string(be, "EntityId");
			String id = "Zombie".equals(mob) ? "dungeon_zombie" : "Skeleton".equals(mob) ? "dungeon_skeleton"
					: "Spider".equals(mob) ? "dungeon_spider" : "dungeon";
			raster.structures.add(new ExistingStructure(id, x >> 4, z >> 4, x - 4, z - 4, x + 4, z + 4));
		}
	}
}
