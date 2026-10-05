package com.daniel.worldpainter.util;

import java.io.PrintStream;

/**
 * A tiny logger: Beta 1.7.3 ships no logging library, so World Painter writes to the game's
 * output itself. Messages use "{}" placeholders; a Throwable as the last argument is printed.
 */
public final class Log {
	private final String name;
	private final boolean debug = Boolean.getBoolean("worldpainter.debug");

	public Log(String name) {
		this.name = name;
	}

	public void info(String message, Object... args) {
		print(System.out, "INFO", message, args);
	}

	public void warn(String message, Object... args) {
		print(System.err, "WARN", message, args);
	}

	public void error(String message, Object... args) {
		print(System.err, "ERROR", message, args);
	}

	public void debug(String message, Object... args) {
		if (debug) {
			print(System.out, "DEBUG", message, args);
		}
	}

	private void print(PrintStream out, String level, String message, Object[] args) {
		Throwable error = args.length > 0 && args[args.length - 1] instanceof Throwable t ? t : null;
		StringBuilder sb = new StringBuilder("[").append(name).append("/").append(level).append("] ");
		int arg = 0;
		int from = 0;
		while (true) {
			int at = message.indexOf("{}", from);
			if (at < 0 || arg >= args.length || (error != null && arg == args.length - 1)) {
				sb.append(message, from, message.length());
				break;
			}
			sb.append(message, from, at).append(args[arg++]);
			from = at + 2;
		}
		synchronized (out) {
			out.println(sb);
			if (error != null) {
				error.printStackTrace(out);
			}
		}
	}
}
