package com.serverpackcore.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class DiscordClient {
	private final String token;
	private volatile HttpURLConnection active;

	public DiscordClient(String token) {
		this.token = token;
	}

	public void close() {
		HttpURLConnection connection = active;
		if (connection != null) {
			try {
				connection.disconnect();
			} catch (Throwable throwable) {
				Log.debug("Connection close failed", throwable);
			}
		}
	}

	public static final class Response {
		public final int status;
		public final String body;

		private Response(int status, String body) {
			this.status = status;
			this.body = body;
		}
	}

	public static final class Part {
		final String name;
		final String fileName;
		final String contentType;
		final byte[] data;

		public Part(String name, String fileName, String contentType, byte[] data) {
			this.name = name;
			this.fileName = fileName;
			this.contentType = contentType;
			this.data = data;
		}
	}

	public Response get(String path) throws Exception {
		return request("GET", DiscordConfig.API + path, null, null, true);
	}

	public Response post(String path, String json) throws Exception {
		return request("POST", DiscordConfig.API + path, json, "application/json", true);
	}

	public Response delete(String path) throws Exception {
		return request("DELETE", DiscordConfig.API + path, null, null, true);
	}

	public Response put(String path) throws Exception {
		return request("PUT", DiscordConfig.API + path, null, null, true);
	}

	public Response postWebhook(String url, String payloadJson, List<Part> parts) throws Exception {
		String boundary = "----spc" + System.nanoTime();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		writeField(body, boundary, "payload_json", payloadJson);
		int index = 0;
		for (Part part : parts) {
			writeFile(body, boundary, index++, part);
		}
		body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return request("POST", url, null, "multipart/form-data; boundary=" + boundary, false, body.toByteArray());
	}

	private Response request(String method, String url, String body, String contentType, boolean auth) throws Exception {
		return request(method, url, body, contentType, auth, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
	}

	private Response request(String method, String url, String body, String contentType, boolean auth, byte[] data)
		throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		active = connection;
		try {
			connection.setRequestMethod(method);
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(10000);
			connection.setRequestProperty("User-Agent", DiscordConfig.USER_AGENT);
			if (auth) {
				connection.setRequestProperty("Authorization", "Bot " + token);
			}
			if (contentType != null) {
				connection.setRequestProperty("Content-Type", contentType);
			}
			if (data.length > 0) {
				connection.setDoOutput(true);
				connection.setFixedLengthStreamingMode(data.length);
				try (OutputStream output = connection.getOutputStream()) {
					output.write(data);
				}
			}
			int status = connection.getResponseCode();
			if (status == 429) {
				long retry = retryAfter(connection, connection.getHeaderField("Retry-After"));
				throw new RateLimitException(retry);
			}
			InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
			String responseBody = stream == null ? "" : read(stream);
			if (status >= 400) {
				throw new DiscordException(status, responseBody);
			}
			return new Response(status, responseBody);
		} finally {
			if (active == connection) {
				active = null;
			}
			connection.disconnect();
		}
	}

	private static long retryAfter(HttpURLConnection connection, String header) {
		try {
			if (header != null && !header.isEmpty()) {
				return Math.max(500L, (long) (Double.parseDouble(header) * 1000.0));
			}
			InputStream stream = connection.getErrorStream();
			if (stream != null) {
				Map<String, Object> map = Json.parseObject(read(stream));
				Object value = map.get("retry_after");
				double seconds = value instanceof Number ? ((Number) value).doubleValue() : 1.0;
				return Math.max(500L, (long) (seconds * 1000.0));
			}
		} catch (Throwable throwable) {
			Log.debug("Retry header parse failed", throwable);
		}
		return 5000L;
	}

	private static String read(InputStream stream) throws IOException {
		try (InputStream input = stream) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int read;
			while ((read = input.read(chunk)) != -1) {
				output.write(chunk, 0, read);
			}
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	private static void writeField(ByteArrayOutputStream output, String boundary, String name, String value)
		throws IOException {
		output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		output.write(value.getBytes(StandardCharsets.UTF_8));
		output.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	private static void writeFile(ByteArrayOutputStream output, String boundary, int index, Part part)
		throws IOException {
		output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name
			+ "\"; filename=\"" + part.fileName + "\"\r\nContent-Type: " + part.contentType + "\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		output.write(part.data);
		output.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}
}
