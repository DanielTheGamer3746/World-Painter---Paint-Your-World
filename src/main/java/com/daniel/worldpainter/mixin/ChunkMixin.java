package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.live.LiveRegen;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While live changes decorate regenerated chunks again (trees, ores, dungeons), only those chunks
 * may change: the neighbours' share of the decoration is already in place.
 */
@Mixin(Chunk.class)
public abstract class ChunkMixin {
	@Shadow
	@Final
	public int x;
	@Shadow
	@Final
	public int z;

	@Inject(method = "setBlock(IIII)Z", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlock(int lx, int y, int lz, int block, CallbackInfoReturnable<Boolean> cir) {
		if (LiveRegen.masked(x, z)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setBlock(IIIII)Z", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlockMeta(int lx, int y, int lz, int block, int meta, CallbackInfoReturnable<Boolean> cir) {
		if (LiveRegen.masked(x, z)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setBlockMeta(IIII)V", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskMeta(int lx, int y, int lz, int meta, CallbackInfo ci) {
		if (LiveRegen.masked(x, z)) {
			ci.cancel();
		}
	}

	@Inject(method = "setBlockEntity(IIILnet/minecraft/block/entity/BlockEntity;)V", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlockEntity(int lx, int y, int lz, BlockEntity blockEntity, CallbackInfo ci) {
		if (LiveRegen.masked(x, z)) {
			ci.cancel();
		}
	}
}
