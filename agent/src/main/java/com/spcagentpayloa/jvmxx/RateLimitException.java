package com.spcagentpayloa.jvmxx;

public class RateLimitException extends Exception {
	private static final long serialVersionUID = 1L;
	private final long retryMs;

	public RateLimitException(long retryMs) {
		super("rate limited");
		this.retryMs = retryMs;
	}

	public long retryMs() {
		return retryMs;
	}
}
