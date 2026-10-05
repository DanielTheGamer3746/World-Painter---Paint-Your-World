package com.daniel.worldpainter.client.editor;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.screen.InfoScreen;
import com.daniel.worldpainter.client.screen.StructureEditorScreen;
import com.daniel.worldpainter.editor.EditorOps;
import com.daniel.worldpainter.editor.EditorSessions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.Function;

/** Opens the 3D structure editor: works out where to look, switches the player into editing, shows the screen. */
public final class StructureEditorLauncher {
	private static boolean opening;

	private StructureEditorLauncher() {
	}

	/** From the painter map: a structure in that column, or the ground there. */
	public static void openAtColumn(Minecraft mc, int x, int z) {
		open(mc, level -> EditorOps.startAtColumn(level, x, z));
	}

	/** From the keybind: around the block the player looks at (or a few blocks ahead). */
	public static void openAtLook(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		Vec3 look = mc.player.getLookAngle();
		BlockRay.Hit hit = BlockRay.cast(mc.level, eye, look, 96, false);
		BlockPos pos = hit != null ? hit.pos() : BlockPos.containing(eye.x + look.x * 8, eye.y + look.y * 8, eye.z + look.z * 8);
		open(mc, level -> EditorOps.startAt(level, pos));
	}

	private static void open(Minecraft mc, Function<ServerLevel, EditorOps.EditorStart> where) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level == null) {
			mc.gui.setScreen(new InfoScreen(null, "Structure Editor",
					"The 3D structure editor works in singleplayer worlds.",
					"Open the world in singleplayer to edit its structures."));
			return;
		}
		if (opening) {
			return;
		}
		opening = true;
		UUID playerId = mc.player.getUUID();
		ResourceKey<Level> dimension = mc.level.dimension();
		server.submit(() -> {
			ServerLevel level = server.getLevel(dimension);
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (level == null || player == null) {
				return null;
			}
			EditorOps.EditorStart start = where.apply(level);
			OrbitCamera cam = new OrbitCamera(start.x(), start.y(), start.z());
			cam.distance = start.distance();
			EditorSessions.begin(server, player);
			Vec3 eye = cam.eye();
			// Moving the player there makes the server send the chunks around the structure.
			player.teleportTo(eye.x, eye.y - player.getEyeHeight(), eye.z);
			return cam;
		}).thenAcceptAsync(cam -> {
			opening = false;
			if (cam != null && mc.player != null) {
				mc.gui.setScreen(new StructureEditorScreen(cam));
			}
		}, mc).exceptionally(e -> {
			opening = false;
			WorldPainter.LOGGER.error("Could not open the structure editor", e);
			mc.execute(() -> mc.gui.setScreen(new InfoScreen(null, "Structure Editor",
					"The structure editor could not open:", String.valueOf(e.getMessage()))));
			return null;
		});
	}
}
