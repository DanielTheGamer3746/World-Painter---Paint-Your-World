package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.gen.BiomeOverride;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Optional;

/**
 * Whether a structure may start at a chunk (world generation, /locate, structure checks) is decided
 * with the original biomes, so painting biomes never adds or removes structures.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {
	@WrapMethod(method = "findValidGenerationPoint")
	private Optional<Structure.GenerationStub> worldpainter$useOriginalBiomes(Structure.GenerationContext context,
																		   Operation<Optional<Structure.GenerationStub>> original) {
		BiomeOverride.enterVanilla();
		try {
			return original.call(context);
		} finally {
			BiomeOverride.exitVanilla();
		}
	}
}
