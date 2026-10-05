package com.daniel.worldpainter.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What Beta 1.7.3 generates besides terrain and plants. Its only structure is the dungeon (a
 * mossy cobblestone room with a monster spawner and up to two chests); lakes of water and lava are
 * the other generated landmarks. Villages, strongholds, mineshafts and the rest came later.
 *
 * <p>Vanilla dungeons follow the structure settings (off, no-structure zones, removed ones); lakes
 * stay natural terrain, but extra lakes can be placed like structures.
 */
public final class BetaStructures {
	public enum Kind {DUNGEON, LAKE}

	/** {@code mob} is the spawner's mob ("Zombie", ...) or null for Beta's own random pick. */
	public record Entry(String id, String name, Kind kind, String mob, int lakeBlock, int color, String about) {
	}

	public static final List<Entry> ALL;

	static {
		List<Entry> l = new ArrayList<>();
		l.add(new Entry("dungeon", "Dungeon", Kind.DUNGEON, null, 0, 0xFF9AA58E,
				"Monster room with a spawner (zombie, skeleton or spider, like Beta picks) and chests"));
		l.add(new Entry("dungeon_zombie", "Zombie Dungeon", Kind.DUNGEON, "Zombie", 0, 0xFF5FA05A,
				"Monster room with a zombie spawner and chests"));
		l.add(new Entry("dungeon_skeleton", "Skeleton Dungeon", Kind.DUNGEON, "Skeleton", 0, 0xFFD6D6CC,
				"Monster room with a skeleton spawner and chests"));
		l.add(new Entry("dungeon_spider", "Spider Dungeon", Kind.DUNGEON, "Spider", 0, 0xFF8A4A4A,
				"Monster room with a spider spawner and chests"));
		l.add(new Entry("water_lake", "Water Lake", Kind.LAKE, null, BetaBlocks.WATER, 0xFF3F76E4,
				"A small lake dug into the ground where you click"));
		l.add(new Entry("lava_lake", "Lava Lake", Kind.LAKE, null, BetaBlocks.LAVA, 0xFFD8571D,
				"A lava pool dug into the ground where you click"));
		ALL = Collections.unmodifiableList(l);
	}

	private BetaStructures() {
	}

	public static Entry get(String id) {
		if (id == null) {
			return null;
		}
		String key = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (Entry e : ALL) {
			if (e.id().equals(key)) {
				return e;
			}
		}
		return null;
	}

	public static List<String> ids() {
		List<String> out = new ArrayList<>();
		for (Entry e : ALL) {
			out.add(e.id());
		}
		return out;
	}
}
