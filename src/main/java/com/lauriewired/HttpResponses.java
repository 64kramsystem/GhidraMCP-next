package com.lauriewired;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class HttpResponses {

    public static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";
    public static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private HttpResponses() {
    }

    public static void sendText(HttpExchange exchange, String response) throws IOException {
        send(exchange, response, TEXT_CONTENT_TYPE);
    }

    public static void sendJson(HttpExchange exchange, String response) throws IOException {
        send(exchange, response, JSON_CONTENT_TYPE);
    }

    private static void send(HttpExchange exchange, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
