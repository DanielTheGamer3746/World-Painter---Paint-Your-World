package com.daniel.worldpainter.editor;

import com.daniel.worldpainter.WorldPainter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the 3D structure editor changes in the world. All methods must run on the server
 * thread; the editor (client) calls them through the singleplayer server's task queue.
 */
public final class EditorOps {
	/** Normal block update, but no shape updates, so portal blocks are not removed by their neighbours. */
	private static final int QUIET_FLAGS = 2 | 16;
	private static final int NORMAL_FLAGS = 3;

	private EditorOps() {
	}

	/** One block change, kept by the editor for undo/redo. */
	public record BlockChange(BlockPos pos, BlockState before, BlockState after) {
	}

	/** A structure found at a position: id and inclusive bounds. */
	public record FoundStructure(String id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		public BlockPos center() {
			return new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
		}

		public long volume() {
			return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
		}

		public int largestSide() {
			return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) + 1;
		}
	}

	/** Where the editor camera starts: the point it orbits, how far away it is, and the structure there. */
	public record EditorStart(double x, double y, double z, double distance, String structureId) {
	}

	/** Start point for a map click: a structure in that column if there is one, otherwise the ground. */
	public static EditorStart startAtColumn(ServerLevel level, int x, int z) {
		FoundStructure s = findStructureInColumn(level, x, z);
		if (s != null) {
			return frame(level, s);
		}
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		return new EditorStart(x + 0.5, y + 0.5, z + 0.5, 30, null);
	}

	/** Start point around a block (e.g. the one the player looks at). */
	public static EditorStart startAt(ServerLevel level, BlockPos pos) {
		FoundStructure s = findStructure(level, pos);
		if (s != null && s.largestSide() <= 64) {
			return frame(level, s);
		}
		return new EditorStart(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 20, s == null ? null : s.id());
	}

	/** Frames a whole structure; for strongholds, the end portal room. */
	public static EditorStart frame(ServerLevel level, FoundStructure s) {
		if (s.id().contains("stronghold")) {
			EndPortalInfo end = scanEndPortal(level, new BlockPos(s.minX(), s.minY(), s.minZ()), new BlockPos(s.maxX(), s.maxY(), s.maxZ()));
			if (!end.frames().isEmpty()) {
				double x = 0, y = 0, z = 0;
				for (BlockPos p : end.frames()) {
					x += p.getX();
					y += p.getY();
					z += p.getZ();
				}
				int n = end.frames().size();
				return new EditorStart(x / n + 0.5, y / n + 1, z / n + 0.5, 16, s.id());
			}
		}
		BlockPos c = s.center();
		double distance = Math.clamp(s.largestSide() * 1.3 + 8, 8, 200);
		return new EditorStart(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, distance, s.id());
	}

	/** Looks for a structure anywhere in the column at x/z (the smallest one wins). */
	public static FoundStructure findStructureInColumn(ServerLevel level, int x, int z) {
		if (level.structureManager().getAllStructuresAt(new BlockPos(x, level.getMinY(), z)).isEmpty()) {
			return null;
		}
		FoundStructure best = null;
		for (int y = level.getMinY(); y <= level.getMaxY(); y += 4) {
			FoundStructure s = findStructure(level, new BlockPos(x, y, z));
			if (s != null && (best == null || s.volume() < best.volume())) {
				best = s;
			}
		}
		return best;
	}

	// ---- plain blocks ----

	public static List<BlockChange> setBlocks(ServerLevel level, Map<BlockPos, BlockState> changes, boolean quiet) {
		List<BlockChange> done = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> e : changes.entrySet()) {
			BlockState before = level.getBlockState(e.getKey());
			if (before != e.getValue()) {
				level.setBlock(e.getKey(), e.getValue(), quiet ? QUIET_FLAGS : NORMAL_FLAGS);
				done.add(new BlockChange(e.getKey(), before, e.getValue()));
			}
		}
		return done;
	}

	public static List<BlockChange> setBlock(ServerLevel level, BlockPos pos, BlockState state) {
		Map<BlockPos, BlockState> m = new HashMap<>();
		m.put(pos, state);
		return setBlocks(level, m, false);
	}

	/** Undo (to "before") or redo (to "after") a list of changes. */
	public static void apply(ServerLevel level, List<BlockChange> changes, boolean undo) {
		for (int i = 0; i < changes.size(); i++) {
			BlockChange c = changes.get(undo ? changes.size() - 1 - i : i);
			level.setBlock(c.pos(), undo ? c.before() : c.after(), QUIET_FLAGS);
		}
	}

	// ---- structures ----

	/** The structure whose pieces cover this position (smallest if several), or null. */
	public static FoundStructure findStructure(ServerLevel level, BlockPos pos) {
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		FoundStructure best = null;
		long bestVolume = Long.MAX_VALUE;
		for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
			StructureStart start = level.structureManager().getStructureAt(pos, structure);
			if (start == null || !start.isValid()) {
				continue;
			}
			BoundingBox b = start.getBoundingBox();
			Identifier id = registry.getKey(structure);
			FoundStructure found = new FoundStructure(id == null ? "unknown" : id.toString(), b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());
			if (found.volume() < bestVolume) {
				best = found;
				bestVolume = found.volume();
			}
		}
		return best;
	}

	// ---- containers (chest loot) ----

	/** Contents of a container; loot that was not generated yet is generated first so it can be edited. */
	public record ContainerView(List<ItemStack> items, String lootTable) {
	}

	private static final Map<BlockPos, String> ORIGINAL_LOOT = new HashMap<>();

	public static ContainerView readContainer(ServerLevel level, BlockPos pos) {
		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container)) {
			return null;
		}
		if (be instanceof RandomizableContainer rc) {
			ResourceKey<LootTable> table = rc.getLootTable();
			if (table != null) {
				ORIGINAL_LOOT.put(pos.immutable(), table.identifier().toString());
				rc.unpackLootTable(null);
			}
		}
		List<ItemStack> items = new ArrayList<>();
		for (int i = 0; i < container.getContainerSize(); i++) {
			items.add(container.getItem(i).copy());
		}
		return new ContainerView(items, ORIGINAL_LOOT.get(pos));
	}

	public static void setContainerItem(ServerLevel level, BlockPos pos, int slot, ItemStack stack) {
		if (level.getBlockEntity(pos) instanceof Container container && slot >= 0 && slot < container.getContainerSize()) {
			container.setItem(slot, stack.copy());
			container.setChanged();
		}
	}

	public static void clearContainer(ServerLevel level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof Container container) {
			container.clearContent();
			container.setChanged();
		}
	}

	/** Replaces the contents with a fresh roll of a loot table. */
	public static void rerollContainer(ServerLevel level, BlockPos pos, String lootTableId) {
		BlockEntity be = level.getBlockEntity(pos);
		Identifier id = Identifier.tryParse(lootTableId);
		if (id == null || !(be instanceof RandomizableContainer rc) || !(be instanceof Container container)) {
			return;
		}
		container.clearContent();
		rc.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, id), level.getRandom().nextLong());
		rc.unpackLootTable(null);
		container.setChanged();
		ORIGINAL_LOOT.put(pos.immutable(), lootTableId);
	}

	/** Loot tables that fill chests (for the reroll list), sorted. */
	public static List<String> chestLootTables(MinecraftServer server) {
		List<String> out = new ArrayList<>();
		try {
			server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElementIds()
					.map(k -> k.identifier().toString())
					.filter(s -> s.contains(":chests/"))
					.forEach(out::add);
		} catch (RuntimeException e) {
			WorldPainter.LOGGER.warn("Could not list loot tables", e);
		}
		out.sort(Comparator.naturalOrder());
		return out;
	}

	// ---- spawners ----

	public static String spawnerMob(ServerLevel level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
			return null;
		}
		Entity display = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
		if (display == null) {
			return "";
		}
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(display.getType());
		return id == null ? "" : id.toString();
	}

	public static boolean setSpawnerMob(ServerLevel level, BlockPos pos, String entityId) {
		Identifier id = Identifier.tryParse(entityId);
		Optional<EntityType<?>> type = id == null ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(id);
		if (type.isEmpty() || !(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
			return false;
		}
		spawner.setEntityId(type.get(), level.getRandom());
		spawner.setChanged();
		BlockState state = level.getBlockState(pos);
		level.sendBlockUpdated(pos, state, state, NORMAL_FLAGS);
		return true;
	}

	/** Mobs that make sense in a spawner. */
	public static List<String> spawnableMobs() {
		List<String> out = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (type.getCategory() != MobCategory.MISC) {
				out.add(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
			}
		}
		out.sort(Comparator.naturalOrder());
		return out;
	}

	// ---- stronghold end portal ----

	public record EndPortalInfo(List<BlockPos> frames, int eyes, boolean open) {
	}

	/** Finds end portal frame blocks within the box (inclusive). */
	public static EndPortalInfo scanEndPortal(ServerLevel level, BlockPos min, BlockPos max) {
		List<BlockPos> frames = new ArrayList<>();
		int eyes = 0;
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			BlockState s = level.getBlockState(p);
			if (s.is(Blocks.END_PORTAL_FRAME)) {
				frames.add(p.immutable());
				if (s.getValue(EndPortalFrameBlock.HAS_EYE)) {
					eyes++;
				}
			}
		}
		boolean open = false;
		for (BlockPos inner : portalInterior(frames)) {
			if (level.getBlockState(inner).is(Blocks.END_PORTAL)) {
				open = true;
				break;
			}
		}
		return new EndPortalInfo(frames, eyes, open);
	}

	/** Puts eyes in the first {@code count} frames and removes the rest. 12 eyes opens the portal. */
	public static List<BlockChange> setEndPortalEyes(ServerLevel level, List<BlockPos> frames, int count) {
		List<BlockPos> sorted = new ArrayList<>(frames);
		sorted.sort(Comparator.<BlockPos>comparingInt(p -> p.getX()).thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getY()));
		Map<BlockPos, BlockState> changes = new HashMap<>();
		for (int i = 0; i < sorted.size(); i++) {
			BlockPos p = sorted.get(i);
			BlockState s = level.getBlockState(p);
			if (s.is(Blocks.END_PORTAL_FRAME)) {
				changes.put(p, s.setValue(EndPortalFrameBlock.HAS_EYE, i < count));
			}
		}
		boolean open = count >= 12 && sorted.size() >= 12;
		for (BlockPos inner : portalInterior(sorted)) {
			BlockState s = level.getBlockState(inner);
			if (open && (s.isAir() || s.is(Blocks.END_PORTAL))) {
				changes.put(inner, Blocks.END_PORTAL.defaultBlockState());
			} else if (!open && s.is(Blocks.END_PORTAL)) {
				changes.put(inner, Blocks.AIR.defaultBlockState());
			}
		}
		return setBlocks(level, changes, true);
	}

	/** The 3x3 space inside a ring of 12 frames (same Y), or nothing if the frames do not form a ring. */
	static List<BlockPos> portalInterior(List<BlockPos> frames) {
		List<BlockPos> out = new ArrayList<>();
		if (frames.size() < 12) {
			return out;
		}
		int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
		int y = frames.getFirst().getY();
		for (BlockPos p : frames) {
			if (p.getY() != y) {
				return out;
			}
			minX = Math.min(minX, p.getX());
			maxX = Math.max(maxX, p.getX());
			minZ = Math.min(minZ, p.getZ());
			maxZ = Math.max(maxZ, p.getZ());
		}
		if (maxX - minX != 4 || maxZ - minZ != 4) {
			return out;
		}
		for (int x = minX + 1; x <= maxX - 1; x++) {
			for (int z = minZ + 1; z <= maxZ - 1; z++) {
				out.add(new BlockPos(x, y, z));
			}
		}
		return out;
	}

	// ---- ruined portal ----

	/**
	 * A (ruined) nether portal frame: an upright rectangle ring in the X or Z plane.
	 * {@code frame} is every position of the ring, {@code interior} the inside.
	 */
	public record PortalInfo(int obsidian, int crying, List<BlockPos> frame, List<BlockPos> interior,
							 List<BlockPos> missing, Direction.Axis axis, boolean upright, boolean lit) {
	}

	/** Scans obsidian and crying obsidian in the box and works out the portal frame they belong to. */
	public static PortalInfo scanPortal(ServerLevel level, BlockPos min, BlockPos max) {
		List<BlockPos> blocks = new ArrayList<>();
		int obsidian = 0, crying = 0;
		for (BlockPos p : BlockPos.betweenClosed(min, max)) {
			BlockState s = level.getBlockState(p);
			if (s.is(Blocks.OBSIDIAN)) {
				obsidian++;
				blocks.add(p.immutable());
			} else if (s.is(Blocks.CRYING_OBSIDIAN)) {
				crying++;
				blocks.add(p.immutable());
			}
		}
		return portalShape(level, blocks, obsidian, crying);
	}

	static PortalInfo portalShape(ServerLevel level, List<BlockPos> blocks, int obsidian, int crying) {
		if (blocks.size() < 3) {
			return new PortalInfo(obsidian, crying, List.of(), List.of(), List.of(), Direction.Axis.X, false, false);
		}
		// Ruined portals have loose obsidian lying around: the frame is the upright plane
		// (constant Z = portal along X, or constant X = portal along Z) holding the most blocks.
		Map<Integer, Integer> byZ = new HashMap<>();
		Map<Integer, Integer> byX = new HashMap<>();
		for (BlockPos p : blocks) {
			byZ.merge(p.getZ(), 1, Integer::sum);
			byX.merge(p.getX(), 1, Integer::sum);
		}
		Map.Entry<Integer, Integer> bestZ = byZ.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		Map.Entry<Integer, Integer> bestX = byX.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
		boolean alongX = bestZ.getValue() >= bestX.getValue();
		int fixed = alongX ? bestZ.getKey() : bestX.getKey();
		Direction.Axis axis = alongX ? Direction.Axis.X : Direction.Axis.Z;

		int a0 = Integer.MAX_VALUE, a1 = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		int inPlane = 0;
		for (BlockPos p : blocks) {
			if ((alongX ? p.getZ() : p.getX()) != fixed) {
				continue;
			}
			inPlane++;
			int a = alongX ? p.getX() : p.getZ();
			a0 = Math.min(a0, a);
			a1 = Math.max(a1, a);
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
		}
		boolean upright = inPlane >= 3 && maxY - minY >= 2;
		if (!upright) {
			return new PortalInfo(obsidian, crying, List.of(), List.of(), List.of(), axis, false, false);
		}
		// A lit portal needs at least a 2x3 opening: make the ring at least 4 wide and 5 tall.
		if (a1 - a0 < 3) {
			a1 = a0 + 3;
		}
		if (maxY - minY < 4) {
			maxY = minY + 4;
		}
		List<BlockPos> frame = new ArrayList<>();
		List<BlockPos> interior = new ArrayList<>();
		List<BlockPos> missing = new ArrayList<>();
		boolean lit = false;
		for (int a = a0; a <= a1; a++) {
			for (int y = minY; y <= maxY; y++) {
				BlockPos p = alongX ? new BlockPos(a, y, fixed) : new BlockPos(fixed, y, a);
				boolean edge = a == a0 || a == a1 || y == minY || y == maxY;
				boolean corner = (a == a0 || a == a1) && (y == minY || y == maxY);
				if (edge) {
					if (corner) {
						continue; // corners are not needed for a portal
					}
					frame.add(p);
					BlockState s = level.getBlockState(p);
					if (!s.is(Blocks.OBSIDIAN) && !s.is(Blocks.CRYING_OBSIDIAN)) {
						missing.add(p);
					}
				} else {
					interior.add(p);
					if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) {
						lit = true;
					}
				}
			}
		}
		// Bottom row first, so added obsidian builds up from the ground.
		missing.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()));
		return new PortalInfo(obsidian, crying, frame, interior, missing, axis, true, lit);
	}

	/** Adds ({@code delta} > 0) or removes obsidian blocks of the frame. */
	public static List<BlockChange> changeObsidian(ServerLevel level, PortalInfo info, int delta) {
		Map<BlockPos, BlockState> changes = new HashMap<>();
		if (delta > 0) {
			for (int i = 0; i < delta && i < info.missing().size(); i++) {
				changes.put(info.missing().get(i), Blocks.OBSIDIAN.defaultBlockState());
			}
		} else {
			List<BlockPos> present = new ArrayList<>();
			for (BlockPos p : info.frame()) {
				BlockState s = level.getBlockState(p);
				if (s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN)) {
					present.add(p);
				}
			}
			present.sort(Comparator.<BlockPos>comparingInt(p -> p.getY()).reversed());
			for (int i = 0; i < -delta && i < present.size(); i++) {
				changes.put(present.get(i), Blocks.AIR.defaultBlockState());
			}
			if (info.lit()) {
				for (BlockPos p : info.interior()) {
					changes.put(p, Blocks.AIR.defaultBlockState());
				}
			}
		}
		return setBlocks(level, changes, true);
	}

	public static List<BlockChange> cryingToObsidian(ServerLevel level, PortalInfo info) {
		Map<BlockPos, BlockState> changes = new HashMap<>();
		for (BlockPos p : info.frame()) {
			if (level.getBlockState(p).is(Blocks.CRYING_OBSIDIAN)) {
				changes.put(p, Blocks.OBSIDIAN.defaultBlockState());
			}
		}
		return setBlocks(level, changes, true);
	}

	/** Fills every missing frame block with obsidian and turns crying obsidian into obsidian. */
	public static List<BlockChange> completeFrame(ServerLevel level, PortalInfo info) {
		Map<BlockPos, BlockState> changes = new HashMap<>();
		for (BlockPos p : info.frame()) {
			if (!level.getBlockState(p).is(Blocks.OBSIDIAN)) {
				changes.put(p, Blocks.OBSIDIAN.defaultBlockState());
			}
		}
		for (BlockPos p : info.interior()) {
			BlockState s = level.getBlockState(p);
			if (!s.isAir() && !s.is(Blocks.NETHER_PORTAL)) {
				changes.put(p, Blocks.AIR.defaultBlockState());
			}
		}
		return setBlocks(level, changes, true);
	}

	/** Completes the frame and fills it with nether portal blocks. */
	public static List<BlockChange> lightPortal(ServerLevel level, PortalInfo info) {
		List<BlockChange> done = new ArrayList<>(completeFrame(level, info));
		Map<BlockPos, BlockState> changes = new HashMap<>();
		BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, info.axis());
		for (BlockPos p : info.interior()) {
			changes.put(p, portal);
		}
		done.addAll(setBlocks(level, changes, true));
		return done;
	}
}
