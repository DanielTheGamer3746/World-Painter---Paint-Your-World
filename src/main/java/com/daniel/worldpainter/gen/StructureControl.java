package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.BetaStructures;
import com.daniel.worldpainter.data.StructurePlan;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.LakeFeature;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** Applies the structure plan while Beta decorates chunks (dungeons and lakes are made there). */
public final class StructureControl {
	/** Floor height of placed dungeons built in this game (they are underground, the painter shows where). */
	private static final Map<Long, Integer> BUILT = new ConcurrentHashMap<>();

	private StructureControl() {
	}

	private static long key(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/** The height a placed dungeon at (x, z) was built at, or null if it was not built in this game. */
	public static Integer builtY(int x, int z) {
		return BUILT.get(key(x, z));
	}

	/** True if the vanilla dungeon Beta wants to build at (x, z) must not generate. */
	public static boolean blocksVanillaDungeon(World world, int x, int z) {
		GenPaint paint = PaintBindings.get(world);
		return paint != null && paint.world.structures().blocksVanilla(x >> 4, z >> 4);
	}

	/** Builds every structure the player placed in the area chunk (cx, cz) decorates. */
	public static void placeStructures(World world, int cx, int cz) {
		GenPaint paint = PaintBindings.get(world);
		if (paint == null) {
			return;
		}
		List<StructurePlan.Placed> list = paint.world.structures().placedDecoratedBy(cx, cz);
		for (StructurePlan.Placed placed : list) {
			BetaStructures.Entry kind = BetaStructures.get(placed.structure());
			if (kind == null) {
				WorldPainter.LOGGER.warn("Placed structure {} does not exist in Beta 1.7.3", placed.structure());
				continue;
			}
			// The same structure every time the area generates (also after regenerating it).
			Random random = new Random(world.getSeed() ^ (placed.x() * 341873128712L + placed.z() * 132897987541L) ^ placed.structure().hashCode());
			try {
				if (kind.kind() == BetaStructures.Kind.DUNGEON) {
					int ground = DungeonBuilder.groundY(world, placed.x(), placed.z());
					int y = Math.max(6, Math.min(ground - 10, 100));
					DungeonBuilder.build(world, random, placed.x(), y, placed.z(), kind.mob());
					BUILT.put(key(placed.x(), placed.z()), y);
					WorldPainter.LOGGER.info("Built {} at {} {} {} (underground)", kind.name(), placed.x(), y, placed.z());
				} else {
					placeLake(world, random, kind.lakeBlock(), placed.x(), placed.z());
				}
			} catch (RuntimeException e) {
				WorldPainter.LOGGER.error("Failed to build {} at {} {}", placed.structure(), placed.x(), placed.z(), e);
			}
		}
	}

	/**
	 * Beta's own lake feature, started above the ground at the clicked spot (it digs down to the
	 * ground itself). It gives up where the lake would leak, so a few shapes are tried.
	 */
	private static void placeLake(World world, Random random, int block, int x, int z) {
		int y = Math.min(DungeonBuilder.groundY(world, x, z) + 2, 127);
		for (int attempt = 0; attempt < 8; attempt++) {
			if (new LakeFeature(block).generate(world, random, x, y, z)) {
				return;
			}
		}
		WorldPainter.LOGGER.info("A lake could not be placed at {} {} (the ground there would let it leak)", x, z);
	}
}
