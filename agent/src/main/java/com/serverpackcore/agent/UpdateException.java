package com.serverpackcore.agent;

/** Short, user-facing failure of the update pipeline. */
public final class UpdateException extends Exception {
	public UpdateException(String message) {
		super(message);
	}
}
