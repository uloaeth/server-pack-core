package com.spcagentpayloa.jvmxx;

/** Short, user-facing failure of the update pipeline. */
public final class UpdateException extends Exception {
	public UpdateException(String message) {
		super(message);
	}
}
