package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.StructurePlan;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Applies the structure plan while chunks decide which structures start in them. */
public final class StructureControl {
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private StructureControl() {
	}

	/** True if vanilla structures must not start in this chunk (vanilla off, removed structure or zone). */
	public static boolean blocksVanilla(ChunkGenerator generator, ChunkAccess chunk) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null) {
			return false;
		}
		ChunkPos pos = chunk.getPos();
		return paint.world.structures().blocksVanilla(pos.x(), pos.z());
	}

	/** Starts every structure the player placed in this chunk, whatever the biome. */
	public static void placeStructures(ChunkGenerator generator, RegistryAccess registryAccess, ChunkGeneratorStructureState state,
									   StructureManager structureManager, ChunkAccess chunk, StructureTemplateManager templates,
									   ResourceKey<Level> level) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		List<StructurePlan.Placed> list = paint.world.structures().placedInChunk(pos.x(), pos.z());
		if (list.isEmpty()) {
			return;
		}
		Registry<Structure> registry = registryAccess.lookupOrThrow(Registries.STRUCTURE);
		for (StructurePlan.Placed placed : list) {
			Identifier id = Identifier.tryParse(placed.structure());
			Optional<Holder.Reference<Structure>> found = id == null ? Optional.empty() : registry.get(id);
			if (found.isEmpty()) {
				if (WARNED.add(placed.structure())) {
					WorldPainter.LOGGER.warn("Placed structure {} does not exist in this world", placed.structure());
				}
				continue;
			}
			Holder<Structure> holder = found.get();
			try {
				Climate.Sampler sampler = state.randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED);
				StructureStart start = holder.value().generate(holder, level, registryAccess, generator, generator.getBiomeSource(),
						sampler, state.randomState(), templates, state.getLevelSeed(), pos, 0, chunk, biome -> true);
				if (start.isValid()) {
					structureManager.setStartForStructure(holder.value(), start, chunk);
				} else {
					WorldPainter.LOGGER.warn("{} could not be placed at chunk {} (the terrain there does not suit it)", placed.structure(), pos);
				}
			} catch (RuntimeException e) {
				WorldPainter.LOGGER.error("Failed to place {} at chunk {}", placed.structure(), pos, e);
			}
		}
	}
}
