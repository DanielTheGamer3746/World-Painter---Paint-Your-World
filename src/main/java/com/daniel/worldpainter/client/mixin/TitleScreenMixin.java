package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.DraftSession;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Back on the title screen: a design painted on the Create World screen was not used. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {
	@Inject(method = "init()V", at = @At("TAIL"))
	private void worldpainter$disarmDraft(CallbackInfo ci) {
		DraftSession.disarm();
	}
}
