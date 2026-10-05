package com.daniel.worldpainter.data;

import com.daniel.worldpainter.storage.IoUtil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Remembers which chunks were painted over, so they can be regenerated the next time the
 * world loads. Stored per tile as a 16x16 chunk bitmask (4 longs), which stays small even for
 * huge painted areas.
 */
public final class DirtyChunks {
	private static final int MAGIC = 0x57504443; // "WPDC"
	/**
	 * Guards the regeneration files: the painter adds to them while the (integrated) server removes
	 * chunks it regenerated live, both in the same game.
	 */
	public static final Object FILE_LOCK = new Object();
	private final Map<Long, long[]> bits = new HashMap<>();

	public boolean isEmpty() {
		return bits.isEmpty();
	}

	public void markChunk(int cx, int cz) {
		long key = TileKey.of(cx >> 4, cz >> 4);
		long[] b = bits.computeIfAbsent(key, k -> new long[4]);
		int bit = ((cz & 15) << 4) | (cx & 15);
		b[bit >> 6] |= 1L << (bit & 63);
	}

	public boolean contains(int cx, int cz) {
		long[] b = bits.get(TileKey.of(cx >> 4, cz >> 4));
		if (b == null) {
			return false;
		}
		int bit = ((cz & 15) << 4) | (cx & 15);
		return (b[bit >> 6] & (1L << (bit & 63))) != 0;
	}

	public void removeChunk(int cx, int cz) {
		long key = TileKey.of(cx >> 4, cz >> 4);
		long[] b = bits.get(key);
		if (b == null) {
			return;
		}
		int bit = ((cz & 15) << 4) | (cx & 15);
		b[bit >> 6] &= ~(1L << (bit & 63));
		if (b[0] == 0 && b[1] == 0 && b[2] == 0 && b[3] == 0) {
			bits.remove(key);
		}
	}

	public DirtyChunks copy() {
		DirtyChunks c = new DirtyChunks();
		c.addAll(this);
		return c;
	}

	/** Marks every chunk touching the given block rectangle (inclusive). */
	public void markBlockRect(int minX, int minZ, int maxX, int maxZ) {
		int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				markChunk(cx, cz);
			}
		}
	}

	public void markTile(int tx, int tz) {
		long[] b = bits.computeIfAbsent(TileKey.of(tx, tz), k -> new long[4]);
		b[0] = b[1] = b[2] = b[3] = -1L;
	}

	public void addAll(DirtyChunks other) {
		for (Map.Entry<Long, long[]> e : other.bits.entrySet()) {
			long[] b = bits.computeIfAbsent(e.getKey(), k -> new long[4]);
			long[] o = e.getValue();
			for (int i = 0; i < 4; i++) {
				b[i] |= o[i];
			}
		}
	}

	public void clear() {
		bits.clear();
	}

	public interface ChunkConsumer {
		void accept(int cx, int cz);
	}

	public void forEach(ChunkConsumer consumer) {
		for (Map.Entry<Long, long[]> e : bits.entrySet()) {
			int tx = TileKey.x(e.getKey());
			int tz = TileKey.z(e.getKey());
			long[] b = e.getValue();
			for (int bit = 0; bit < 256; bit++) {
				if ((b[bit >> 6] & (1L << (bit & 63))) != 0) {
					consumer.accept((tx << 4) | (bit & 15), (tz << 4) | (bit >> 4));
				}
			}
		}
	}

	public long countChunks() {
		long n = 0;
		for (long[] b : bits.values()) {
			for (long l : b) {
				n += Long.bitCount(l);
			}
		}
		return n;
	}

	public static DirtyChunks load(Path file) throws IOException {
		DirtyChunks d = new DirtyChunks();
		if (!Files.isRegularFile(file)) {
			return d;
		}
		try (InputStream raw = Files.newInputStream(file);
			 DataInputStream in = new DataInputStream(new GZIPInputStream(raw))) {
			if (in.readInt() != MAGIC) {
				throw new IOException("Not a World Painter regeneration file: " + file);
			}
			in.readInt(); // version
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				long key = in.readLong();
				long[] b = new long[4];
				for (int j = 0; j < 4; j++) {
					b[j] = in.readLong();
				}
				d.bits.put(key, b);
			}
		}
		return d;
	}

	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = Files.newOutputStream(tmp);
			 DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
			out.writeInt(MAGIC);
			out.writeInt(1);
			out.writeInt(bits.size());
			for (Map.Entry<Long, long[]> e : bits.entrySet()) {
				out.writeLong(e.getKey());
				for (long l : e.getValue()) {
					out.writeLong(l);
				}
			}
		}
		IoUtil.replace(tmp, file);
	}
}
