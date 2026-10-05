package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.StructureControl;
import com.daniel.worldpainter.gen.TerrainShaper;
import net.minecraft.block.SandBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSource;
import net.minecraft.world.gen.chunk.OverworldChunkGenerator;
import net.minecraft.world.gen.feature.DungeonFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Random;

/** Painted terrain, surfaces and structures in Beta's Overworld generator. */
@Mixin(OverworldChunkGenerator.class)
public abstract class OverworldChunkGeneratorMixin {
	@Shadow
	private World world;

	@Inject(method = "buildTerrain", at = @At("RETURN"))
	private void worldpainter$afterTerrain(int cx, int cz, byte[] blocks, Biome[] biomes, double[] temperatures, CallbackInfo ci) {
		TerrainShaper.afterTerrain(world, cx, cz, blocks, temperatures);
	}

	@Inject(method = "buildSurfaces", at = @At("RETURN"))
	private void worldpainter$afterSurfaces(int cx, int cz, byte[] blocks, Biome[] biomes, CallbackInfo ci) {
		TerrainShaper.afterSurface(world, cx, cz, blocks);
	}

	@Inject(method = "getChunk", at = @At("RETURN"))
	private void worldpainter$finishChunk(int cx, int cz, CallbackInfoReturnable<Chunk> cir) {
		TerrainShaper.finishChunk(world, cir.getReturnValue());
	}

	/** Vanilla dungeons follow the structure settings (off, no-structure zones, removed ones). */
	@Redirect(method = "decorate", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/gen/feature/DungeonFeature;generate(Lnet/minecraft/world/World;Ljava/util/Random;III)Z"))
	private boolean worldpainter$vanillaDungeon(DungeonFeature feature, World w, Random random, int x, int y, int z) {
		if (StructureControl.blocksVanillaDungeon(w, x, z)) {
			return false;
		}
		return feature.generate(w, random, x, y, z);
	}

	/** Structures the player placed, built with the rest of the area's decoration. */
	@Inject(method = "decorate", at = @At("TAIL"))
	private void worldpainter$placedStructures(ChunkSource source, int cx, int cz, CallbackInfo ci) {
		boolean before = SandBlock.fallInstantly;
		SandBlock.fallInstantly = true;
		try {
			StructureControl.placeStructures(world, cx, cz);
		} finally {
			SandBlock.fallInstantly = before;
		}
	}
}
