package com.daniel.worldpainter.client;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.editor.StructureEditorLauncher;
import com.daniel.worldpainter.client.mixin.SelectWorldScreenAccessor;
import com.daniel.worldpainter.client.screen.InfoScreen;
import com.daniel.worldpainter.client.screen.PainterScreen;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.gen.Dimensions;
import com.daniel.worldpainter.storage.WorldPaths;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.sdl.SDLScancode;

import java.nio.file.Path;
import java.util.Optional;

/** The client half of the mod (never loaded on a dedicated server). */
@Mod(value = WorldPainter.MOD_ID, dist = Dist.CLIENT)
public class WorldPainterClient {
	private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(WorldPainter.id("main"));
	public static final KeyMapping openKey = new KeyMapping(
			"key.worldpainter.open", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_O, CATEGORY);
	public static final KeyMapping editorKey = new KeyMapping(
			"key.worldpainter.structure_editor", InputConstants.Type.KEYBOARD, SDLScancode.SDL_SCANCODE_K, CATEGORY);

	/** The World Painter button on the world list, which is only usable while a world is selected. */
	private static Screen worldListScreen;
	private static Button worldListButton;

	public WorldPainterClient(IEventBus modEventBus) {
		modEventBus.addListener((RegisterKeyMappingsEvent event) -> {
			event.registerCategory(CATEGORY);
			event.register(openKey);
			event.register(editorKey);
		});

		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> {
			Minecraft client = Minecraft.getInstance();
			while (openKey.consumeClick()) {
				openForRunningWorld(client);
			}
			while (editorKey.consumeClick()) {
				if (client.gui.screen() == null) {
					StructureEditorLauncher.openAtLook(client);
				}
			}
			if (PainterResume.pending() && client.gui.screen() == null && client.player != null && client.level != null) {
				resumePainter(client);
			}
			updateWorldListButton(client);
		});

		NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
			Minecraft client = Minecraft.getInstance();
			Screen screen = event.getScreen();
			if (screen instanceof CreateWorldScreen) {
				addCreateWorldButton(client, event, client.getWindow().getGuiScaledHeight());
			} else if (screen instanceof SelectWorldScreen) {
				DraftSession.disarm();
				addWorldListButton(client, event);
			} else if (screen instanceof TitleScreen) {
				DraftSession.disarm();
				worldListScreen = null;
				worldListButton = null;
			}
		});
	}

	private static void addCreateWorldButton(Minecraft client, ScreenEvent.Init.Post event, int screenHeight) {
		Screen screen = event.getScreen();
		Button button = Button.builder(Component.literal("World Painter"), b -> {
					Path draft = DraftSession.draftDir();
					client.gui.setScreen(new PainterScreen(new PainterTarget(
							PainterTarget.Kind.NEW_WORLD, draft, PaintDimension.OVERWORLD, null, null, "New world", screen)));
				})
				.bounds(4, screenHeight - 26, 96, 20)
				.tooltip(Tooltip.create(Component.literal("Paint biomes, terrain, water and lava for the world you are creating")))
				.build();
		event.addListener(button);
	}

	private static void addWorldListButton(Minecraft client, ScreenEvent.Init.Post event) {
		Screen screen = event.getScreen();
		Button button = Button.builder(Component.literal("World Painter"), b -> {
					WorldSelectionList list = ((SelectWorldScreenAccessor) screen).worldpainter$getList();
					Optional<WorldSelectionList.WorldListEntry> selected = list == null ? Optional.empty() : list.getSelectedOpt();
					if (selected.isEmpty()) {
						return;
					}
					LevelSummary summary = selected.get().getLevelSummary();
					String levelId = summary.getLevelId();
					Path root = client.getLevelSource().getLevelPath(levelId).toAbsolutePath().normalize();
					client.gui.setScreen(new PainterScreen(new PainterTarget(
							PainterTarget.Kind.SAVED_WORLD, WorldPaths.paintDir(root), PaintDimension.OVERWORLD, root, levelId,
							summary.getLevelName(), screen)));
				})
				.bounds(4, 4, 96, 20)
				.tooltip(Tooltip.create(Component.literal("Paint the selected world. Painted areas that were already explored are regenerated (a backup is kept).")))
				.build();
		button.active = false;
		event.addListener(button);
		worldListScreen = screen;
		worldListButton = button;
	}

	/** Every tick: the world list's button is usable while a world is selected. */
	private static void updateWorldListButton(Minecraft client) {
		if (worldListButton != null && client.gui.screen() == worldListScreen) {
			WorldSelectionList list = ((SelectWorldScreenAccessor) worldListScreen).worldpainter$getList();
			worldListButton.active = list != null && list.getSelectedOpt().isPresent();
		}
	}

	/** Opens the painter again in the 3D view after the world was reloaded from it. */
	private static void resumePainter(Minecraft client) {
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			return;
		}
		Path root = WorldPainter.worldRoot(server);
		String levelId = root.getFileName().toString();
		PainterResume.State resume = PainterResume.take(levelId);
		if (resume == null) {
			return;
		}
		PaintDimension here = Dimensions.of(client.level.dimension());
		PaintDimension dimension = here == null ? PaintDimension.OVERWORLD : here;
		client.gui.setScreen(new PainterScreen(new PainterTarget(
				PainterTarget.Kind.RUNNING_WORLD, WorldPaths.paintDir(root), dimension, root, levelId, levelId, null),
				resume.dimension() == dimension ? resume : null));
	}

	private static void openForRunningWorld(Minecraft client) {
		if (client.gui.screen() != null) {
			return;
		}
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			client.gui.setScreen(new InfoScreen(null, "World Painter",
					"World Painter can only edit singleplayer worlds.",
					"On a server, the owner can paint the world folder from their own game."));
			return;
		}
		Path root = WorldPainter.worldRoot(server);
		String levelId = root.getFileName().toString();
		// Opens on the dimension the player is in (other dimensions are one click away).
		PaintDimension dimension = client.level == null ? null : Dimensions.of(client.level.dimension());
		client.gui.setScreen(new PainterScreen(new PainterTarget(
				PainterTarget.Kind.RUNNING_WORLD, WorldPaths.paintDir(root), dimension == null ? PaintDimension.OVERWORLD : dimension,
				root, levelId, levelId, null)));
	}
}
