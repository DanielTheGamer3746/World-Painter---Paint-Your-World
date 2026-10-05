package com.daniel.worldpainter.client.preview;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Draws the 3D preview into an ARGB pixel buffer by casting one ray per pixel, spread over several
 * threads. Everything here is plain Java so it does not depend on Minecraft's renderer.
 */
public final class PreviewRenderer implements AutoCloseable {
	private static final int BAND = 8;

	private final ExecutorService pool;
	private final int workers;
	private final ThreadLocal<RayTracer> tracers = ThreadLocal.withInitial(RayTracer::new);

	public PreviewRenderer() {
		// The calling thread renders too; leave one core for the game itself.
		int cores = Runtime.getRuntime().availableProcessors();
		workers = Math.clamp(cores - 2, 0, 7);
		pool = workers == 0 ? null : Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "World Painter 3D preview");
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);
			return t;
		});
	}

	/** A block found under a screen point, and which of its faces was hit. */
	public record Hit(int x, int y, int z, int face) {
		public static final int UP = RayTracer.UP;
		public static final int DOWN = RayTracer.DOWN;

		public int nx() {
			return face == RayTracer.WEST ? -1 : face == RayTracer.EAST ? 1 : 0;
		}

		public int ny() {
			return face == RayTracer.UP ? 1 : face == RayTracer.DOWN ? -1 : 0;
		}

		public int nz() {
			return face == RayTracer.NORTH ? -1 : face == RayTracer.SOUTH ? 1 : 0;
		}

		public boolean top() {
			return face == RayTracer.UP;
		}
	}

	/**
	 * Renders the scene as seen by {@code cam} into {@code out} ({@code w * h} pixels, row by row).
	 */
	public void render(PreviewScene.Snapshot scene, PreviewCamera cam, int w, int h, int[] out, boolean shadows) {
		if (scene == null) {
			java.util.Arrays.fill(out, 0, w * h, RayTracer.SKY_HORIZON);
			return;
		}
		double maxT = cam.distance + scene.radius() * 2 + 512;
		double pixelAngle = cam.pixelSize(1, h);
		int bands = (h + BAND - 1) / BAND;
		AtomicInteger next = new AtomicInteger();
		Runnable job = () -> {
			RayTracer tracer = tracers.get();
			tracer.setScene(scene);
			double[] dir = new double[3];
			int b;
			while ((b = next.getAndIncrement()) < bands) {
				int y0 = b * BAND, y1 = Math.min(h, y0 + BAND);
				for (int y = y0; y < y1; y++) {
					int row = y * w;
					for (int x = 0; x < w; x++) {
						cam.ray(x + 0.5, y + 0.5, w, h, dir);
						out[row + x] = tracer.shade(cam.eyeX, cam.eyeY, cam.eyeZ, dir[0], dir[1], dir[2], maxT, shadows, pixelAngle);
					}
				}
			}
		};
		if (pool == null) {
			job.run();
			return;
		}
		List<Future<?>> futures = new ArrayList<>(workers);
		for (int i = 0; i < workers; i++) {
			futures.add(pool.submit(job));
		}
		job.run();
		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (ExecutionException e) {
				throw new RuntimeException(e.getCause());
			}
		}
	}

	/** The block under a point of a {@code w x h} view, looking through water. Null if there is none. */
	public Hit pick(PreviewScene.Snapshot scene, PreviewCamera cam, double sx, double sy, double w, double h) {
		if (scene == null) {
			return null;
		}
		RayTracer tracer = tracers.get();
		tracer.setScene(scene);
		double[] dir = new double[3];
		cam.ray(sx, sy, w, h, dir);
		double maxT = cam.distance + scene.radius() * 2 + 512;
		if (!tracer.trace(cam.eyeX, cam.eyeY, cam.eyeZ, dir[0], dir[1], dir[2], maxT, true, false)) {
			return null;
		}
		return new Hit(tracer.hitX, tracer.hitY, tracer.hitZ, tracer.face);
	}

	@Override
	public void close() {
		if (pool != null) {
			pool.shutdownNow();
		}
	}
}
