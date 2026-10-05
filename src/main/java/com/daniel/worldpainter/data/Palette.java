package com.daniel.worldpainter.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Maps ids (biome ids, block ids) to small numbers stored in a tile. Index 0 always means "not painted". */
public final class Palette {
	private final List<String> ids = new ArrayList<>();
	private final Map<String, Short> lookup = new HashMap<>();
	/** What {@link #get} reads: replaced whole on every change, so other threads read it without a lock. */
	private volatile String[] view = {""};

	public Palette() {
		ids.add("");
	}

	public synchronized short indexOf(String id) {
		if (id == null || id.isEmpty()) {
			return 0;
		}
		Short existing = lookup.get(id);
		if (existing != null) {
			return existing;
		}
		if (ids.size() >= Short.MAX_VALUE) {
			throw new IllegalStateException("Palette full");
		}
		short idx = (short) ids.size();
		ids.add(id);
		lookup.put(id, idx);
		view = ids.toArray(new String[0]);
		return idx;
	}

	/** Returns the id for an index, or null for 0 / unknown. */
	public String get(short index) {
		if (index <= 0) {
			return null;
		}
		String[] v = view;
		return index < v.length ? v[index] : null;
	}

	public synchronized int size() {
		return ids.size();
	}

	public synchronized List<String> snapshot() {
		return new ArrayList<>(ids);
	}

	public synchronized void load(List<String> entries) {
		ids.clear();
		lookup.clear();
		ids.add("");
		for (int i = 1; i < entries.size(); i++) {
			String s = entries.get(i);
			ids.add(s);
			lookup.put(s, (short) i);
		}
		view = ids.toArray(new String[0]);
	}

	public Palette copy() {
		Palette p = new Palette();
		p.load(snapshot());
		return p;
	}
}
