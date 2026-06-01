package com.lauriewired;

import java.net.InetSocketAddress;

import junit.framework.TestCase;

public class HttpServerConfigTest extends TestCase {

    public void testDefaultBindHostIsLocalhost() {
        assertEquals("127.0.0.1", HttpServerConfig.DEFAULT_BIND_HOST);
    }

    public void testBindAddressUsesConfiguredHostAndPort() {
        InetSocketAddress address = HttpServerConfig.createBindAddress("127.0.0.1", 8080);

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(8080, address.getPort());
    }

    public void testBlankBindHostFallsBackToLocalhost() {
        InetSocketAddress address = HttpServerConfig.createBindAddress("  ", 9090);

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(9090, address.getPort());
    }
}
