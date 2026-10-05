package com.daniel.worldpainter.client.existing;

import java.io.DataInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny NBT reader used to preview existing worlds. It is independent of Minecraft's own NBT
 * classes on purpose and skips everything the map does not need (block entities, lighting, ...).
 */
final class MiniNbt {
	private static final Set<String> SKIP = Set.of(
			"block_entities", "Entities", "entities", "References", "PostProcessing", "block_ticks", "fluid_ticks",
			"BlockLight", "SkyLight", "block_states", "CarvingMasks", "Lights", "blending_data", "UpgradeData",
			"below_zero_retrogen");

	private MiniNbt() {
	}

	/** Reads a root compound. */
	static Map<String, Object> readRoot(DataInput in) throws IOException {
		int type = in.readUnsignedByte();
		if (type != 10) {
			throw new IOException("Root tag is not a compound");
		}
		in.readUTF();
		return readCompound(in, 0);
	}

	private static Map<String, Object> readCompound(DataInput in, int depth) throws IOException {
		if (depth > 64) {
			throw new IOException("NBT too deep");
		}
		Map<String, Object> map = new HashMap<>();
		while (true) {
			int type = in.readUnsignedByte();
			if (type == 0) {
				return map;
			}
			String name = in.readUTF();
			if (SKIP.contains(name)) {
				skip(in, type, depth + 1);
			} else {
				map.put(name, read(in, type, depth + 1));
			}
		}
	}

	private static Object read(DataInput in, int type, int depth) throws IOException {
		return switch (type) {
			case 1 -> in.readByte();
			case 2 -> in.readShort();
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 5 -> in.readFloat();
			case 6 -> in.readDouble();
			case 7 -> {
				int n = checkedLength(in.readInt());
				byte[] b = new byte[n];
				in.readFully(b);
				yield b;
			}
			case 8 -> in.readUTF();
			case 9 -> {
				int elemType = in.readUnsignedByte();
				int n = checkedLength(in.readInt());
				List<Object> list = new ArrayList<>(Math.min(n, 4096));
				for (int i = 0; i < n; i++) {
					list.add(elemType == 0 ? null : read(in, elemType, depth + 1));
				}
				yield list;
			}
			case 10 -> readCompound(in, depth);
			case 11 -> {
				int n = checkedLength(in.readInt());
				int[] a = new int[n];
				for (int i = 0; i < n; i++) {
					a[i] = in.readInt();
				}
				yield a;
			}
			case 12 -> {
				int n = checkedLength(in.readInt());
				long[] a = new long[n];
				for (int i = 0; i < n; i++) {
					a[i] = in.readLong();
				}
				yield a;
			}
			default -> throw new IOException("Unknown NBT tag type " + type);
		};
	}

	private static void skip(DataInput in, int type, int depth) throws IOException {
		if (depth > 64) {
			throw new IOException("NBT too deep");
		}
		switch (type) {
			case 1 -> in.skipBytes(1);
			case 2 -> in.skipBytes(2);
			case 3, 5 -> in.skipBytes(4);
			case 4, 6 -> in.skipBytes(8);
			case 7 -> skipFully(in, checkedLength(in.readInt()));
			case 8 -> skipFully(in, in.readUnsignedShort());
			case 9 -> {
				int elemType = in.readUnsignedByte();
				int n = checkedLength(in.readInt());
				for (int i = 0; i < n; i++) {
					if (elemType != 0) {
						skip(in, elemType, depth + 1);
					}
				}
			}
			case 10 -> {
				while (true) {
					int t = in.readUnsignedByte();
					if (t == 0) {
						break;
					}
					skipFully(in, in.readUnsignedShort());
					skip(in, t, depth + 1);
				}
			}
			case 11 -> skipFully(in, checkedLength(in.readInt()) * 4L);
			case 12 -> skipFully(in, checkedLength(in.readInt()) * 8L);
			default -> throw new IOException("Unknown NBT tag type " + type);
		}
	}

	private static void skipFully(DataInput in, long n) throws IOException {
		while (n > 0) {
			int step = (int) Math.min(n, Integer.MAX_VALUE);
			int skipped = in.skipBytes(step);
			if (skipped <= 0) {
				// skipBytes may skip less; fall back to reading
				in.readByte();
				skipped = 1;
			}
			n -= skipped;
		}
	}

	private static int checkedLength(int n) throws IOException {
		if (n < 0 || n > 64 * 1024 * 1024) {
			throw new IOException("Bad NBT length " + n);
		}
		return n;
	}

	// --- small typed getters ---

	@SuppressWarnings("unchecked")
	static Map<String, Object> compound(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof List ? (List<Object>) o : null;
	}

	static long[] longs(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof long[] a ? a : null;
	}

	static Integer integer(Map<String, Object> m, String key) {
		Object o = m.get(key);
		if (o instanceof Integer i) {
			return i;
		}
		if (o instanceof Byte b) {
			return (int) b;
		}
		if (o instanceof Short s) {
			return (int) s;
		}
		return null;
	}

	static String string(Map<String, Object> m, String key) {
		Object o = m.get(key);
		return o instanceof String s ? s : null;
	}
}
