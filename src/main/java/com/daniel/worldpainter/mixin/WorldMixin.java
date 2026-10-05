package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.live.LiveRegen;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * See {@link ChunkMixin}: while a regenerated chunk is decorated again, blocks and block entities
 * (spawners, chests) of the other chunks do not change. Every block change in Beta goes through
 * these world methods; StationAPI's chunks replace the chunk methods, so the world is where this
 * works with and without it.
 */
@Mixin(World.class)
public abstract class WorldMixin {
	@Inject(method = "setBlockEntity(IIILnet/minecraft/block/entity/BlockEntity;)V", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlockEntity(int x, int y, int z, BlockEntity blockEntity, CallbackInfo ci) {
		if (LiveRegen.masked(x >> 4, z >> 4)) {
			ci.cancel();
		}
	}

	@Inject(method = "setBlockWithoutNotifyingNeighbors(IIII)Z", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlock(int x, int y, int z, int block, CallbackInfoReturnable<Boolean> cir) {
		if (LiveRegen.masked(x >> 4, z >> 4)) {
			LiveRegen.blockedWrite(x, y, z, block);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setBlockWithoutNotifyingNeighbors(IIIII)Z", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskBlockMeta(int x, int y, int z, int block, int meta, CallbackInfoReturnable<Boolean> cir) {
		if (LiveRegen.masked(x >> 4, z >> 4)) {
			LiveRegen.blockedWrite(x, y, z, block);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "setBlockMetaWithoutNotifyingNeighbors(IIII)Z", at = @At("HEAD"), cancellable = true)
	private void worldpainter$maskMeta(int x, int y, int z, int meta, CallbackInfoReturnable<Boolean> cir) {
		if (LiveRegen.masked(x >> 4, z >> 4)) {
			cir.setReturnValue(false);
		}
	}
}
