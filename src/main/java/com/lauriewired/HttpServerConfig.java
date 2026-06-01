package com.lauriewired;

import java.net.InetSocketAddress;

public final class HttpServerConfig {

    public static final String DEFAULT_BIND_HOST = "127.0.0.1";

    private HttpServerConfig() {
    }

    public static InetSocketAddress createBindAddress(String bindHost, int port) {
        String host = bindHost == null || bindHost.trim().isEmpty()
            ? DEFAULT_BIND_HOST
            : bindHost.trim();
        return new InetSocketAddress(host, port);
    }
}
