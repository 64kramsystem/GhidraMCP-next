package com.lauriewired;

import java.util.Arrays;

import junit.framework.TestCase;

public class JsonTest extends TestCase {

    public void testBuildsNestedEnvelopeWithSingleEscapingPath() throws Exception {
        String item = Json.object(
            Json.field("name", Json.string("main\"\\fn\n")),
            Json.field("address", Json.string("00000600")));
        String data = Json.object(
            Json.field("functions", Json.array(Arrays.asList(item))));

        assertEquals(
            "{\"ok\":true,\"data\":{\"functions\":[{\"name\":\"main\\\"\\\\fn\\n\"," +
            "\"address\":\"00000600\"}]},\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            Json.envelope(data));
    }

    public void testBuildsPaginatedEnvelopeWithNextOffset() throws Exception {
        String data = Json.object(Json.field("xrefs", Json.array(Arrays.<String>asList())));

        assertEquals(
            "{\"ok\":true,\"data\":{\"xrefs\":[]},\"warnings\":[]," +
            "\"meta\":{\"api_version\":\"1\",\"offset\":0,\"limit\":1,\"next_offset\":1}}",
            Json.envelope(data, 0, 1, 1));
    }

    public void testBuildsPaginatedEnvelopeWithoutNextOffsetOnLastPage() throws Exception {
        String data = Json.object(Json.field("xrefs", Json.array(Arrays.<String>asList())));

        assertEquals(
            "{\"ok\":true,\"data\":{\"xrefs\":[]},\"warnings\":[]," +
            "\"meta\":{\"api_version\":\"1\",\"offset\":1,\"limit\":1}}",
            Json.envelope(data, 1, 1, null));
    }

    public void testBuildsErrorEnvelope() throws Exception {
        assertEquals(
            "{\"ok\":false,\"error\":{\"code\":\"no_program_loaded\"," +
            "\"message\":\"No program loaded\"},\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            Json.errorEnvelope("no_program_loaded", "No program loaded"));
    }
}
