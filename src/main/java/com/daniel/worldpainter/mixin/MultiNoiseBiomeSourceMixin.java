package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.BiomeOverride;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every biome lookup for generation, structure checks and /locate goes through a resolver made by
 * the biome source, so wrapping the resolvers applies painted biomes everywhere.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin {
	@ModifyReturnValue(method = "createResolver", at = @At("RETURN"))
	private BiomeResolver worldpainter$paintResolver(BiomeResolver original) {
		return BiomeOverride.wrap(this, original);
	}

	@ModifyReturnValue(method = "createResolverForChunk", at = @At("RETURN"))
	private BiomeResolver worldpainter$paintChunkResolver(BiomeResolver original) {
		return BiomeOverride.wrap(this, original);
	}
}
