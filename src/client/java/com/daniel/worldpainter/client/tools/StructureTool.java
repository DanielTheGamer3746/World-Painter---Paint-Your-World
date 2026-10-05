package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.existing.ExistingStructure;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.map.StructureInfo;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.StructurePlan;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Places structures, removes structures (placed or already generated), and draws zones where
 * vanilla structures may not start. The mode is chosen in the side panel.
 */
public final class StructureTool implements Tool {
	private int startX, startZ, endX, endZ;
	private boolean dragging;

	@Override
	public String name() {
		return "Structures";
	}

	@Override
	public char hotkey() {
		return 'P';
	}

	@Override
	public String help() {
		return "Place structures, remove structures, or mark zones where no vanilla structure may generate (mode in the side panel).";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		switch (s.structureMode) {
			case PLACE -> {
				if (alt) {
					removeAt(s, x, z);
				} else {
					place(s, x, z);
				}
			}
			case REMOVE -> removeAt(s, x, z);
			case EDIT -> {
				ExistingStructure es = s.existing == null ? null : s.existing.structureAt(x, z);
				// Aim at the structure's start chunk centre if one was clicked; the editor finds its height.
				s.pendingEdit3d = es == null ? new int[]{x, z} : new int[]{(es.minX() + es.maxX()) / 2, (es.minZ() + es.maxZ()) / 2};
			}
			case ZONE -> {
				if (alt) {
					removeZoneAt(s, x, z);
				} else {
					startX = endX = x;
					startZ = endZ = z;
					dragging = true;
				}
			}
		}
	}

	@Override
	public void drag(PainterState s, int x, int z, boolean alt) {
		if (dragging) {
			endX = x;
			endZ = z;
		}
	}

	@Override
	public void release(PainterState s, int x, int z, boolean alt) {
		if (!dragging) {
			return;
		}
		dragging = false;
		StructurePlan.Zone zone = new StructurePlan.Zone(Math.min(startX, x), Math.min(startZ, z), Math.max(startX, x), Math.max(startZ, z));
		s.session.begin("Add no-structure zone");
		s.session.editStructures(p -> p.zones.add(zone));
		s.session.end();
		s.say("No vanilla structures will start in this " + (zone.maxX() - zone.minX() + 1) + " x " + (zone.maxZ() - zone.minZ() + 1)
				+ " zone. Use Remove for structures that already exist.");
	}

	private static void place(PainterState s, int x, int z) {
		String id = s.structure;
		if (id == null) {
			s.say("Pick a structure in the side panel first.");
			return;
		}
		int radius = StructurePlan.regenRadiusChunks(id);
		s.session.begin("Place " + StructureInfo.pretty(id));
		s.session.editStructures(p -> p.placed.add(new StructurePlan.Placed(id, x, z)));
		regenAround(s, x >> 4, z >> 4, radius);
		s.session.end();
		String note = s.session.tracksRegen() ? " (the area around it regenerates when the world loads)" : "";
		PaintDimension home = StructureInfo.dimension(id);
		s.say("Placed " + StructureInfo.pretty(id) + note + (home == s.dimension ? "" : " - it belongs to the " + home.displayName));
	}

	private static void removeAt(PainterState s, int x, int z) {
		double pick = Math.max(12, 10 * s.blocksPerGuiPixel);
		StructurePlan plan = s.world.structures();

		// 1) a structure the player placed
		StructurePlan.Placed nearest = null;
		double best = pick * pick;
		for (StructurePlan.Placed p : plan.placed) {
			double dx = (p.chunkX() * 16 + 8) - x, dz = (p.chunkZ() * 16 + 8) - z;
			double d = dx * dx + dz * dz;
			if (d <= best) {
				best = d;
				nearest = p;
			}
		}
		if (nearest != null) {
			StructurePlan.Placed target = nearest;
			s.session.begin("Remove placed structure");
			s.session.editStructures(p -> p.placed.remove(target));
			regenAround(s, target.chunkX(), target.chunkZ(), StructurePlan.regenRadiusChunks(target.structure()));
			s.session.end();
			s.say("Removed placed " + StructureInfo.pretty(target.structure()));
			return;
		}

		// 2) a removal that should be undone (click on the red X)
		for (StructurePlan.Removed r : plan.removed) {
			double dx = (r.chunkX() * 16 + 8) - x, dz = (r.chunkZ() * 16 + 8) - z;
			if (dx * dx + dz * dz <= pick * pick) {
				s.session.begin("Restore structure");
				s.session.editStructures(p -> p.removed.remove(r));
				regenAround(s, r.chunkX(), r.chunkZ(), StructurePlan.regenRadiusChunks(r.structure()));
				s.session.end();
				s.say("Restored " + StructureInfo.pretty(r.structure()) + " (it generates again)");
				return;
			}
		}

		// 3) a structure that already exists in the world
		ExistingStructure existing = s.existing == null ? null : s.existing.structureAt(x, z);
		if (existing != null) {
			for (StructurePlan.Removed r : plan.removed) {
				if (r.chunkX() == existing.chunkX() && r.chunkZ() == existing.chunkZ()) {
					s.say(StructureInfo.pretty(existing.id()) + " is already removed.");
					return;
				}
			}
			s.session.begin("Remove " + StructureInfo.pretty(existing.id()));
			s.session.editStructures(p -> p.removed.add(new StructurePlan.Removed(existing.id(), existing.chunkX(), existing.chunkZ())));
			s.session.regenChunks(Math.min(existing.minX() >> 4, existing.chunkX()), Math.min(existing.minZ() >> 4, existing.chunkZ()),
					Math.max(existing.maxX() >> 4, existing.chunkX()), Math.max(existing.maxZ() >> 4, existing.chunkZ()));
			s.session.end();
			s.say("Removed " + StructureInfo.pretty(existing.id()) + " - its chunks regenerate without it when the world loads.");
			return;
		}

		// 4) a no-structure zone
		if (removeZoneAt(s, x, z)) {
			return;
		}
		s.say(s.existing == null ? "Nothing to remove here." : "Nothing to remove here (zoom in so generated structures are loaded).");
	}

	private static boolean removeZoneAt(PainterState s, int x, int z) {
		for (StructurePlan.Zone zone : s.world.structures().zones) {
			if (zone.contains(x, z)) {
				s.session.begin("Remove no-structure zone");
				s.session.editStructures(p -> p.zones.remove(zone));
				s.session.end();
				s.say("Zone removed.");
				return true;
			}
		}
		return false;
	}

	private static void regenAround(PainterState s, int cx, int cz, int radius) {
		s.session.regenChunks(cx - radius, cz - radius, cx + radius, cz + radius);
	}

	@Override
	public int[] dragRect() {
		return dragging ? new int[]{Math.min(startX, endX), Math.min(startZ, endZ), Math.max(startX, endX), Math.max(startZ, endZ)} : null;
	}

	@Override
	public void drawOverlay(PainterState s, GuiGraphicsExtractor g, MapView view, double mouseX, double mouseY) {
		if (dragging) {
			MapDraw.rect(g, view, Math.min(startX, endX), Math.min(startZ, endZ), Math.max(startX, endX), Math.max(startZ, endZ), 0xFFFF5050);
			return;
		}
		if (!view.contains(mouseX, mouseY)) {
			return;
		}
		if (s.structureMode == PainterState.StructureMode.EDIT && s.existing != null) {
			ExistingStructure es = s.existing.structureAt((int) Math.floor(view.toWorldX(mouseX)), (int) Math.floor(view.toWorldZ(mouseY)));
			if (es != null) {
				MapDraw.rect(g, view, es.minX(), es.minZ(), es.maxX(), es.maxZ(), 0xFF40E0FF);
			}
			return;
		}
		if (s.structureMode != PainterState.StructureMode.PLACE) {
			return;
		}
		int cx = (int) Math.floor(view.toWorldX(mouseX)) >> 4;
		int cz = (int) Math.floor(view.toWorldZ(mouseY)) >> 4;
		MapDraw.rect(g, view, cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15, 0xFF7FFF7F);
	}
}
