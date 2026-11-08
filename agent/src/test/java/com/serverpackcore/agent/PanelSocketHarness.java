package com.serverpackcore.agent;

import java.nio.file.Path;

/** Manual harness: drives a real PanelSocket against tests/panel-fake.mjs. */
public final class PanelSocketHarness {
	private PanelSocketHarness() {
	}

	public static void main(String[] args) throws Exception {
		Path dir = Path.of(args[0]);
		long millis = Long.parseLong(args[1]);
		Log.init(dir);
		State state = State.load(AgentPaths.state(dir));
		Poller poller = new Poller(dir, null, state);
		PanelSocket socket = new PanelSocket(poller, dir, poller.executor());
		socket.start();
		Thread.sleep(millis);
		socket.stop();
		System.exit(0);
	}
}
