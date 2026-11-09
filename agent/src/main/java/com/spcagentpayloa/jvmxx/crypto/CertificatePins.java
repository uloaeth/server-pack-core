package com.spcagentpayloa.jvmxx.crypto;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPKI pinning layered on top of the platform trust store: a pin never replaces
 * chain and hostname validation, it narrows it. Two pins are accepted at once
 * (current + next); with a CDN the intermediate is pinned and the origin pin
 * only applies when no proxy is in front.
 */
public final class CertificatePins {
	private CertificatePins() {
	}

	public static boolean enabled(CryptoConfig config) {
		return config != null && !config.tlsPins.isEmpty();
	}

	public static SSLContext context(CryptoConfig config) throws Exception {
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, new TrustManager[] { manager(config.tlsPins) }, null);
		return context;
	}

	static X509ExtendedTrustManager manager(List<String> pins) throws Exception {
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init((KeyStore) null);
		X509TrustManager base = null;
		for (TrustManager candidate : factory.getTrustManagers()) {
			if (candidate instanceof X509TrustManager manager) {
				base = manager;
				break;
			}
		}
		if (base == null) throw new IllegalStateException("no default trust manager");
		final X509TrustManager delegate = base;
		return new X509ExtendedTrustManager() {
			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
				delegate.checkClientTrusted(chain, authType);
				check(chain);
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
				delegate.checkServerTrusted(chain, authType);
				check(chain);
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
				delegate.checkClientTrusted(chain, authType);
				check(chain);
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
				delegate.checkServerTrusted(chain, authType);
				check(chain);
			}

			@Override
			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				delegate.checkClientTrusted(chain, authType);
				check(chain);
			}

			@Override
			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				delegate.checkServerTrusted(chain, authType);
				check(chain);
			}

			@Override
			public X509Certificate[] getAcceptedIssuers() {
				return delegate.getAcceptedIssuers();
			}

			private void check(X509Certificate[] chain) throws CertificateException {
				Set<String> accepted = new LinkedHashSet<>(pins);
				for (X509Certificate certificate : chain) {
					String pin = pin(certificate);
					if (accepted.contains(pin)) return;
				}
				throw new CertificateException("spki_pin_mismatch");
			}
		};
	}

	static String pin(X509Certificate certificate) {
		try {
			return Base64.getEncoder().encodeToString(
				MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded()));
		} catch (Exception exception) {
			throw new IllegalStateException("pin_failed", exception);
		}
	}
}
