package com.serverpackcore.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/** Generates the two Windows helpers in a modern and a legacy tree for Wine cscript. */
public class VbsDump {
	public static void main(String[] args) throws Exception {
		Path root = Path.of(args[0]);
		for (String name : new String[] { "FabricSync", "ServerPackCore" }) {
			Path dir = root.resolve(name);
			Path game = root.resolve(name + "-game");
			Files.createDirectories(dir);
			Files.createDirectories(game.resolve("mods"));
			String agent = UpdateHelper.renderWindows(dir, AgentPaths.agentJar(dir), AgentPaths.agentStaged(dir),
				AgentPaths.agentOld(dir), AgentPaths.agentBad(dir), AgentPaths.alive(dir),
				Path.of("C:\\jdk\\bin\\javaw.exe"), game, "9.9.9", "update");
			String mod = UpdateHelper.renderModWindows(dir, game.resolve("mods"),
				AgentPaths.modStagedUnique(dir, "9.9.9", 7L),
				game.resolve("mods/" + (AgentPaths.legacy(dir) ? "serverpackcore-" : "fabric-sync-") + "9.9.9.jar"),
				AgentPaths.alive(dir), "9.9.9", "update", 7L);
			Files.write(dir.resolve("agent.vbs"), bom(agent));
			Files.write(dir.resolve("mod.vbs"), bom(mod));
		}
		System.out.println("generated: " + root);
	}

	private static byte[] bom(String text) throws Exception {
		byte[] body = text.getBytes("UTF-16LE");
		byte[] out = new byte[body.length + 2];
		out[0] = (byte) 0xff;
		out[1] = (byte) 0xfe;
		System.arraycopy(body, 0, out, 2, body.length);
		return out;
	}
}
