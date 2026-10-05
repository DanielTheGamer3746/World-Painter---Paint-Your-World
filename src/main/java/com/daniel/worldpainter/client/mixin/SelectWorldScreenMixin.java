package com.daniel.worldpainter.client.mixin;

import com.daniel.worldpainter.client.DraftSession;
import com.daniel.worldpainter.client.WorldPainterClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.world.storage.WorldSaveInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** A "World Painter" button on the world list: paint the selected world. */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {
	@Unique
	private static final int WORLDPAINTER_BUTTON = 4291;

	@Shadow
	private List<WorldSaveInfo> saves;
	@Shadow
	private int selectedWorldId;
	@Shadow
	private ButtonWidget playSelectedWorldButton;

	@Unique
	private ButtonWidget worldpainter$button;

	@SuppressWarnings("unchecked")
	@Inject(method = "init()V", at = @At("TAIL"))
	private void worldpainter$addButton(CallbackInfo ci) {
		// Back on the world list: a design painted on the Create World screen was not used.
		DraftSession.disarm();
		worldpainter$button = new ButtonWidget(WORLDPAINTER_BUTTON, 4, 4, 96, 20, "World Painter");
		buttons.add(worldpainter$button);
		worldpainter$update();
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void worldpainter$render(int mouseX, int mouseY, float delta, CallbackInfo ci) {
		worldpainter$update();
	}

	@Unique
	private void worldpainter$update() {
		if (worldpainter$button != null) {
			// Active exactly when the game's own "Play Selected World" is.
			worldpainter$button.active = playSelectedWorldButton != null && playSelectedWorldButton.active
					&& saves != null && selectedWorldId >= 0 && selectedWorldId < saves.size();
		}
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void worldpainter$click(ButtonWidget button, CallbackInfo ci) {
		if (button.id != WORLDPAINTER_BUTTON) {
			return;
		}
		ci.cancel();
		if (!button.active || saves == null || selectedWorldId < 0 || selectedWorldId >= saves.size()) {
			return;
		}
		WorldSaveInfo info = saves.get(selectedWorldId);
		WorldPainterClient.openForSavedWorld(minecraft, this, info.getSaveName(), info.getName());
	}
}
