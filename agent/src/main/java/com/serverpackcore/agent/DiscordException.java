package com.serverpackcore.agent;

public class DiscordException extends Exception {
	private static final long serialVersionUID = 1L;
	private final int status;

	public DiscordException(int status, String message) {
		super(status + ": " + message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
