package com.daniel.worldpainter.client.editor;

/** A 3D vector (Beta has no immutable one to use). */
public record V3(double x, double y, double z) {
	public V3 add(double dx, double dy, double dz) {
		return new V3(x + dx, y + dy, z + dz);
	}

	public V3 scale(double f) {
		return new V3(x * f, y * f, z * f);
	}

	public double dot(V3 o) {
		return x * o.x + y * o.y + z * o.z;
	}

	public V3 cross(V3 o) {
		return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
	}

	public V3 normalize() {
		double l = Math.sqrt(x * x + y * y + z * z);
		return l < 1e-12 ? this : new V3(x / l, y / l, z / l);
	}
}
