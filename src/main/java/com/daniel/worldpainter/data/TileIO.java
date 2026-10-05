package com.daniel.worldpainter.data;

import com.daniel.worldpainter.storage.IoUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Binary tile file format ("WPT1"). Version 2 adds the 3D edits after the 2D layers; tiles without
 * 3D edits are still written as version 1, so they stay readable by older World Painter versions.
 */
public final class TileIO {
	private static final int MAGIC = 0x57505431; // "WPT1"
	private static final int VERSION = 2;

	private TileIO() {
	}

	public static String fileName(int tx, int tz) {
		return "t." + tx + "." + tz + ".wpt";
	}

	/** Parses "t.X.Z.wpt", returning the tile key, or null if the name does not match. */
	public static Long parseFileName(String name) {
		if (!name.startsWith("t.") || !name.endsWith(".wpt")) {
			return null;
		}
		String mid = name.substring(2, name.length() - 4);
		int dot = mid.indexOf('.', 1);
		if (dot < 0) {
			return null;
		}
		try {
			int tx = Integer.parseInt(mid.substring(0, dot));
			int tz = Integer.parseInt(mid.substring(dot + 1));
			return TileKey.of(tx, tz);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static void write(PaintTile tile, Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (OutputStream raw = Files.newOutputStream(tmp)) {
			write(tile, raw);
		}
		IoUtil.replace(tmp, file);
	}

	public static void write(PaintTile tile, OutputStream raw) throws IOException {
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		try {
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
					new DeflaterOutputStream(raw, deflater, 1 << 16), 1 << 16));
			boolean hasVolume = tile.hasVolume();
			out.writeInt(MAGIC);
			out.writeInt(hasVolume ? 2 : 1);
			out.writeInt(tile.tx);
			out.writeInt(tile.tz);
			writePalette(out, tile.biomePalette.snapshot());
			writePalette(out, tile.surfacePalette.snapshot());
			writeLayer(out, tile.biome);
			writeLayer(out, tile.height);
			writeLayer(out, tile.surface);
			writeLayer(out, tile.fluid);
			if (hasVolume) {
				writeVolume(out, tile.volume);
			}
			out.close();
		} finally {
			deflater.end();
		}
	}

	public static PaintTile read(Path file) throws IOException {
		try (InputStream raw = Files.newInputStream(file)) {
			return read(raw);
		}
	}

	public static PaintTile read(InputStream raw) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(raw), 1 << 16))) {
			return read(in);
		}
	}

	private static PaintTile read(DataInputStream in) throws IOException {
		if (in.readInt() != MAGIC) {
			throw new IOException("Not a World Painter tile");
		}
		int version = in.readInt();
		if (version > VERSION) {
			throw new IOException("Tile was saved by a newer World Painter (format " + version + ")");
		}
		int tx = in.readInt();
		int tz = in.readInt();
		PaintTile tile = new PaintTile(tx, tz);
		tile.biomePalette.load(readPalette(in));
		tile.surfacePalette.load(readPalette(in));
		readLayer(in, tile.biome);
		readLayer(in, tile.height);
		readLayer(in, tile.surface);
		readLayer(in, tile.fluid);
		if (version >= 2) {
			readVolume(in, tile.volume);
		}
		return tile;
	}

	private static void writeVolume(DataOutputStream out, VolumeLayer volume) throws IOException {
		out.writeInt(volume.sectionCount());
		IOException[] failure = new IOException[1];
		volume.forEachSection((index, data) -> {
			if (failure[0] != null) {
				return;
			}
			try {
				out.writeShort(index);
				out.write(data);
			} catch (IOException e) {
				failure[0] = e;
			}
		});
		if (failure[0] != null) {
			throw failure[0];
		}
	}

	private static void readVolume(DataInputStream in, VolumeLayer volume) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > VolumeLayer.SECTION_COUNT) {
			throw new IOException("Bad 3D section count " + n);
		}
		for (int i = 0; i < n; i++) {
			int index = in.readUnsignedShort();
			if (index >= VolumeLayer.SECTION_COUNT) {
				throw new IOException("Bad 3D section index " + index);
			}
			byte[] data = new byte[VolumeLayer.SECTION_VOLUME];
			in.readFully(data);
			volume.putSection(index, data);
		}
	}

	private static void writePalette(DataOutputStream out, List<String> ids) throws IOException {
		out.writeInt(ids.size());
		for (String s : ids) {
			out.writeUTF(s);
		}
	}

	private static List<String> readPalette(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 1 || n > Short.MAX_VALUE) {
			throw new IOException("Bad palette size " + n);
		}
		List<String> ids = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			ids.add(in.readUTF());
		}
		return ids;
	}

	private static void writeLayer(DataOutputStream out, ShortLayer layer) throws IOException {
		short[] data = layer.rawData();
		if (data == null) {
			out.writeByte(0);
			out.writeShort(layer.uniformValue());
		} else {
			out.writeByte(1);
			ByteBuffer buf = ByteBuffer.allocate(data.length * 2).order(ByteOrder.BIG_ENDIAN);
			buf.asShortBuffer().put(data);
			out.write(buf.array());
		}
	}

	private static void readLayer(DataInputStream in, ShortLayer layer) throws IOException {
		int kind = in.readUnsignedByte();
		if (kind == 0) {
			layer.fill(in.readShort());
		} else if (kind == 1) {
			byte[] bytes = new byte[PaintTile.AREA * 2];
			in.readFully(bytes);
			short[] data = new short[PaintTile.AREA];
			ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(data);
			layer.setRaw(data, data[0]);
		} else {
			throw new IOException("Bad layer kind " + kind);
		}
	}
}
