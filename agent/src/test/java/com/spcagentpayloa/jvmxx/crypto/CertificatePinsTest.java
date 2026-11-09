package com.spcagentpayloa.jvmxx.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.X509ExtendedTrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pin narrows the platform trust store; it never replaces it. */
class CertificatePinsTest {
	@TempDir
	Path temp;

	private X509Certificate certificate(Path store) throws Exception {
		String keytool = System.getProperty("java.home") + "/bin/keytool";
		Process process = new ProcessBuilder(keytool, "-genkeypair", "-noprompt", "-alias", "pin",
			"-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=c2.example",
			"-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass", "changeit")
			.redirectErrorStream(true).start();
		assertEquals(0, process.waitFor());
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream input = Files.newInputStream(store)) {
			keys.load(input, "changeit".toCharArray());
		}
		return (X509Certificate) keys.getCertificate("pin");
	}

	@Test
	void pinnedChainPassesAndAnotherPinFails() throws Exception {
		Path store = temp.resolve("pin-store.p12");
		X509Certificate leaf = certificate(store);
		String pin = CertificatePins.pin(leaf);
		assertEquals(pin, CertificatePins.pin(leaf));
		assertNotEquals(pin, CertificatePins.pin(certificate(temp.resolve("other.p12"))));
		Path cert = temp.resolve("pin.pem");
		Path trust = temp.resolve("trust.p12");
		String keytool = System.getProperty("java.home") + "/bin/keytool";
		run(keytool, "-exportcert", "-rfc", "-alias", "pin", "-keystore", store.toString(),
			"-storepass", "changeit", "-file", cert.toString());
		run(keytool, "-importcert", "-noprompt", "-alias", "pin", "-keystore", trust.toString(),
			"-storetype", "PKCS12", "-storepass", "changeit", "-file", cert.toString());

		// The dedicated trust store makes the chain valid; the pin is the second
		// gate and must still reject a different one.
		String oldStore = System.getProperty("javax.net.ssl.trustStore");
		String oldPass = System.getProperty("javax.net.ssl.trustStorePassword");
		String oldType = System.getProperty("javax.net.ssl.trustStoreType");
		System.setProperty("javax.net.ssl.trustStore", trust.toString());
		System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
		System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
		try {
			CertificatePins.manager(List.of(pin))
				.checkServerTrusted(new X509Certificate[] { leaf }, "RSA");
			java.security.cert.CertificateException mismatch = assertThrows(
				java.security.cert.CertificateException.class,
				() -> CertificatePins.manager(List.of("wrong")).checkServerTrusted(
					new X509Certificate[] { leaf }, "RSA"));
			assertTrue(mismatch.getMessage().contains("spki_pin_mismatch"));
		} finally {
			restore("javax.net.ssl.trustStore", oldStore);
			restore("javax.net.ssl.trustStorePassword", oldPass);
			restore("javax.net.ssl.trustStoreType", oldType);
		}
	}

	private static void run(String... args) throws Exception {
		assertEquals(0, new ProcessBuilder(args).redirectErrorStream(true).start().waitFor());
	}

	private static void restore(String key, String value) {
		if (value == null) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	private X509ExtendedTrustManager trustForSelfSigned(X509Certificate leaf) {
		return new X509ExtendedTrustManager() {
			public void checkClientTrusted(X509Certificate[] chain, String authType) { }
			public void checkServerTrusted(X509Certificate[] chain, String authType) { }
			public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) { }
			public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) { }
			public void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) { }
			public void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) { }
			public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[] { leaf }; }
		};
	}
}
