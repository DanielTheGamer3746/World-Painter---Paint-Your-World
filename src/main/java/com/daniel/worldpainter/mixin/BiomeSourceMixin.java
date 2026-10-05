package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.BiomeOverride;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;

import java.util.function.Predicate;

/**
 * Stronghold rings are nudged towards suitable biomes with this horizontal biome search; it uses
 * the original biomes so painting does not move strongholds.
 */
@Mixin(BiomeSource.class)
public abstract class BiomeSourceMixin {
	@WrapMethod(method = "findBiomeHorizontal(IIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/RandomState;)Lcom/mojang/datafixers/util/Pair;")
	private Pair<BlockPos, Holder<Biome>> worldpainter$originalBiomes(int x, int y, int z, int searchRadius,
																	 Predicate<Holder<Biome>> allowed, RandomSource random,
																	 RandomState randomState,
																	 Operation<Pair<BlockPos, Holder<Biome>>> original) {
		BiomeOverride.enterVanilla();
		try {
			return original.call(x, y, z, searchRadius, allowed, random, randomState);
		} finally {
			BiomeOverride.exitVanilla();
		}
	}

	@WrapMethod(method = "findBiomeHorizontal(IIIIILjava/util/function/Predicate;Lnet/minecraft/util/RandomSource;ZLnet/minecraft/world/level/levelgen/RandomState;)Lcom/mojang/datafixers/util/Pair;")
	private Pair<BlockPos, Holder<Biome>> worldpainter$originalBiomesFull(int originX, int originY, int originZ, int searchRadius,
																		 int skipSteps, Predicate<Holder<Biome>> allowed,
																		 RandomSource random, boolean findClosest,
																		 RandomState randomState,
																		 Operation<Pair<BlockPos, Holder<Biome>>> original) {
		BiomeOverride.enterVanilla();
		try {
			return original.call(originX, originY, originZ, searchRadius, skipSteps, allowed, random, findClosest, randomState);
		} finally {
			BiomeOverride.exitVanilla();
		}
	}
}
