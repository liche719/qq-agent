package com.liche.wechatagent.channel.qq;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.time.Duration;

/** Owns the OkHttp WebSocket client lifecycle used by the QQ gateway channel. */
final class QqWebSocketClient {

    private final long connectTimeoutSeconds;
    private final String userAgent;
    private volatile OkHttpClient client;
    private volatile WebSocket webSocket;

    // Creates a gateway client with bounded connection settings.
    QqWebSocketClient(long connectTimeoutSeconds, String userAgent) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.userAgent = userAgent;
    }

    // Opens a WebSocket connection and installs the channel listener.
    synchronized WebSocket connect(String url, String accessToken, WebSocketListener listener) {
        close();
        client = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ZERO)
                .build();
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "QQBot " + accessToken)
                .header("User-Agent", userAgent)
                .build();
        webSocket = client.newWebSocket(request, listener);
        return webSocket;
    }

    // Releases the underlying OkHttp resources and connection pool.
    synchronized void close() {
        WebSocket currentWebSocket = webSocket;
        webSocket = null;
        if (currentWebSocket != null) {
            try {
                currentWebSocket.close(1000, "client shutdown");
            } catch (Exception ignored) {
            }
        }
        OkHttpClient current = client;
        client = null;
        if (current == null) {
            return;
        }
        try {
            current.dispatcher().executorService().shutdown();
            current.connectionPool().evictAll();
        } catch (Exception ignored) {
        }
    }
}
