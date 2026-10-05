package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.TerrainShaper;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
	/** After the noise has filled the chunk with stone/water/air: reshape painted columns. */
	@Inject(method = "doFill", at = @At("TAIL"))
	private void worldpainter$afterFill(CallbackInfo ci, @Local(argsOnly = true) ChunkAccess chunk) {
		TerrainShaper.afterNoiseFill((NoiseBasedChunkGenerator) (Object) this, chunk);
	}

	/** After surface rules (grass, sand, ...): put painted surface blocks on top. */
	@Inject(method = "buildSurface", at = @At("TAIL"))
	private void worldpainter$afterSurface(CallbackInfo ci, @Local(argsOnly = true) ChunkAccess chunk) {
		TerrainShaper.afterSurface((NoiseBasedChunkGenerator) (Object) this, chunk);
	}

	/** Villages, spawn point and other height queries see the painted terrain. */
	@Inject(method = "getBaseHeight", at = @At("HEAD"), cancellable = true)
	private void worldpainter$baseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor,
										 RandomState randomState, CallbackInfoReturnable<Integer> cir) {
		Integer painted = TerrainShaper.baseHeight((NoiseBasedChunkGenerator) (Object) this, x, z, type, heightAccessor);
		if (painted != null) {
			cir.setReturnValue(painted);
		}
	}

	/** Columns left to vanilla height can still have 3D edits (a floating island, a pit). */
	@Inject(method = "getBaseHeight", at = @At("RETURN"), cancellable = true)
	private void worldpainter$baseHeightVolume(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor,
											   RandomState randomState, CallbackInfoReturnable<Integer> cir) {
		int original = cir.getReturnValue();
		int adjusted = TerrainShaper.adjustBaseHeight((NoiseBasedChunkGenerator) (Object) this, x, z, original, heightAccessor);
		if (adjusted != original) {
			cir.setReturnValue(adjusted);
		}
	}

	@Inject(method = "getBaseColumn", at = @At("HEAD"), cancellable = true)
	private void worldpainter$baseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState,
										 CallbackInfoReturnable<NoiseColumn> cir) {
		NoiseColumn painted = TerrainShaper.baseColumn((NoiseBasedChunkGenerator) (Object) this, x, z, heightAccessor);
		if (painted != null) {
			cir.setReturnValue(painted);
		}
	}
}
