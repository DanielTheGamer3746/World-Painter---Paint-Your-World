package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A world's paint data plus cached lookups of painted ids to real biomes/blocks, used during generation. */
public final class GenPaint {
	/** Underground biomes that are kept below Y=0 even where a surface biome was painted. */
	private static final List<String> CAVE_BIOMES = List.of(
			"minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark", "minecraft:sulfur_caves");
	public static final int KEEP_CAVE_BIOMES_BELOW_Y = 0;

	public final PaintWorld world;
	/** Which dimension this design is for: decides which layers are applied. */
	public final PaintDimension dimension;
	private final Registry<Biome> biomes;
	private final ConcurrentHashMap<String, Optional<Holder<Biome>>> biomeCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Optional<BlockState>> blockCache = new ConcurrentHashMap<>();
	private final Set<Holder<Biome>> caveBiomes = new HashSet<>();

	public GenPaint(PaintWorld world, RegistryAccess registries, PaintDimension dimension) {
		this.world = world;
		this.dimension = dimension;
		this.biomes = registries.lookupOrThrow(Registries.BIOME);
		for (String id : CAVE_BIOMES) {
			Holder<Biome> h = biome(id);
			if (h != null) {
				caveBiomes.add(h);
			}
		}
	}

	/** The biome for a painted id, or null if that biome does not exist in this world. */
	public Holder<Biome> biome(String id) {
		return biomeCache.computeIfAbsent(id, key -> {
			Identifier rl = Identifier.tryParse(key);
			if (rl == null) {
				return Optional.empty();
			}
			return biomes.get(rl).<Holder<Biome>>map(h -> h);
		}).orElse(null);
	}

	public boolean isCaveBiome(Holder<Biome> biome) {
		return caveBiomes.contains(biome);
	}

	/** The block for a painted surface id, or null if unknown. */
	public BlockState block(String id) {
		return blockCache.computeIfAbsent(id, key -> {
			Identifier rl = Identifier.tryParse(key);
			if (rl == null) {
				return Optional.empty();
			}
			return BuiltInRegistries.BLOCK.getOptional(rl).map(b -> b.defaultBlockState());
		}).orElse(null);
	}
}
