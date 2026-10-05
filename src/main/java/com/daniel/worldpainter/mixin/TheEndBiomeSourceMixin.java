package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.BiomeOverride;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Painted biomes in the End. The End has its own biome source (not multi-noise), so its biome
 * lookups are hooked here. Each hook is optional: whichever of these methods the End's biome source
 * has gets the painted biomes, and the painted result is the same if more than one applies.
 */
@Mixin(targets = "net.minecraft.world.level.biome.TheEndBiomeSource")
public abstract class TheEndBiomeSourceMixin {
	@ModifyReturnValue(method = "createResolver", at = @At("RETURN"), require = 0)
	private BiomeResolver worldpainter$paintResolver(BiomeResolver original) {
		return BiomeOverride.wrap(this, original);
	}

	@ModifyReturnValue(method = "createResolverForChunk", at = @At("RETURN"), require = 0)
	private BiomeResolver worldpainter$paintChunkResolver(BiomeResolver original) {
		return BiomeOverride.wrap(this, original);
	}

	@ModifyReturnValue(method = {
			"getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
			"getNoiseBiome(IIILnet/minecraft/world/level/levelgen/RandomState;)Lnet/minecraft/core/Holder;"
	}, at = @At("RETURN"), require = 0)
	private Holder<Biome> worldpainter$paintBiome(Holder<Biome> original,
												 @Local(argsOnly = true, ordinal = 0) int quartX,
												 @Local(argsOnly = true, ordinal = 1) int quartY,
												 @Local(argsOnly = true, ordinal = 2) int quartZ) {
		return BiomeOverride.paint(this, quartX, quartY, quartZ, original);
	}
}
