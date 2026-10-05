package com.daniel.worldpainter.mixin;

import com.daniel.worldpainter.live.LiveRegen;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Live changes regenerate painted chunks a little every server tick, and clean up when the server stops. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "tickServer", at = @At("TAIL"))
	private void worldpainter$liveRegen(BooleanSupplier haveTime, CallbackInfo ci) {
		LiveRegen.step((MinecraftServer) (Object) this);
	}

	@Inject(method = "stopServer", at = @At("HEAD"))
	private void worldpainter$stopLiveRegen(CallbackInfo ci) {
		LiveRegen.stop();
	}
}
