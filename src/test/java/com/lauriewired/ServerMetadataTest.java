package com.lauriewired;

import junit.framework.TestCase;

public class ServerMetadataTest extends TestCase {

    public void testVersionResponseContainsStableFields() {
        String response = ServerMetadata.buildVersionResponse("12.1", "21.0.1");

        assertTrue(response.contains("plugin: GhidraMCP-next"));
        assertTrue(response.contains("api_version: 1"));
        assertTrue(response.contains("ghidra_version: 12.1"));
        assertTrue(response.contains("java_version: 21.0.1"));
    }

    public void testHealthResponseReportsProgramLoaded() {
        String response = ServerMetadata.buildHealthResponse("sample.bin");

        assertTrue(response.contains("status: ok"));
        assertTrue(response.contains("program_loaded: true"));
        assertTrue(response.contains("program_name: sample.bin"));
    }

    public void testHealthResponseReportsNoProgram() {
        String response = ServerMetadata.buildHealthResponse(null);

        assertTrue(response.contains("status: ok"));
        assertTrue(response.contains("program_loaded: false"));
        assertTrue(response.contains("program_name:"));
    }
}
