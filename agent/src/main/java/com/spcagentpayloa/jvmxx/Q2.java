package com.spcagentpayloa.jvmxx;

public class Q2 extends Exception {
	private static final long serialVersionUID = 1L;
	private final int status;

	public Q2(int status, String message) {
		super(status + ": " + message);
		this.status = status;
	}

	public int status() {
		return status;
	}
}
