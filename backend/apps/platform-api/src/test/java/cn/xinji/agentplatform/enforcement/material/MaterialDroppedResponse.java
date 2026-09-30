package cn.xinji.agentplatform.enforcement.material;

import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/** Sends a complete command and closes the TLS connection before reading its response. */
final class MaterialDroppedResponse {
    private MaterialDroppedResponse() { }

    static void send(SSLContext tls, int port, String path, String body, String token, String key)
            throws Exception {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (var socket = (SSLSocket) tls.getSocketFactory().createSocket("localhost", port)) {
            socket.startHandshake();
            var output = socket.getOutputStream();
            output.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                    + "Authorization: Bearer " + token + "\r\nIdempotency-Key: " + key
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + payload.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
        }
    }
}
