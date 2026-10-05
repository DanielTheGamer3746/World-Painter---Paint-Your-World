package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.StructureControl;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Structure starts: skip vanilla structures where the plan blocks them, and add the placed ones. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {
	@Inject(method = "createStructures", at = @At("HEAD"), cancellable = true)
	private void worldpainter$blockVanilla(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
										   ChunkAccess centerChunk, StructureTemplateManager templates, ResourceKey<Level> level,
										   CallbackInfo ci) {
		ChunkGenerator self = (ChunkGenerator) (Object) this;
		if (StructureControl.blocksVanilla(self, centerChunk)) {
			StructureControl.placeStructures(self, registryAccess, state, structureManager, centerChunk, templates, level);
			ci.cancel();
		}
	}

	@Inject(method = "createStructures", at = @At("RETURN"))
	private void worldpainter$addPlaced(RegistryAccess registryAccess, ChunkGeneratorStructureState state, StructureManager structureManager,
										ChunkAccess centerChunk, StructureTemplateManager templates, ResourceKey<Level> level,
										CallbackInfo ci) {
		ChunkGenerator self = (ChunkGenerator) (Object) this;
		// Blocked chunks were already handled (and returned early) at HEAD.
		if (!StructureControl.blocksVanilla(self, centerChunk)) {
			StructureControl.placeStructures(self, registryAccess, state, structureManager, centerChunk, templates, level);
		}
	}
}
