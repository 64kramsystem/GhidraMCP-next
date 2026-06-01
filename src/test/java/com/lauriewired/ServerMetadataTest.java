package com.lauriewired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public void testVersionJsonResponseUsesEnvelope() {
        String response = ServerMetadata.buildVersionJsonResponse("12.1", "21.0.1");

        assertEquals(
            "{\"ok\":true,\"data\":{\"plugin\":\"GhidraMCP-next\",\"api_version\":\"1\"," +
            "\"ghidra_version\":\"12.1\",\"java_version\":\"21.0.1\"}," +
            "\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testHealthJsonResponseUsesEnvelopeAndEscapesProgramName() {
        String response = ServerMetadata.buildHealthJsonResponse("sample\"\\bin\n");

        assertEquals(
            "{\"ok\":true,\"data\":{\"status\":\"ok\",\"program_loaded\":true," +
            "\"program_name\":\"sample\\\"\\\\bin\\n\"}," +
            "\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testHealthJsonResponseReportsNoProgram() {
        String response = ServerMetadata.buildHealthJsonResponse(null);

        assertEquals(
            "{\"ok\":true,\"data\":{\"status\":\"ok\",\"program_loaded\":false," +
            "\"program_name\":\"\"},\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testListFunctionsJsonResponseUsesEnvelope() throws Exception {
        List<Map<String, String>> functions = new ArrayList<>();
        functions.add(functionRecord("main", "00000600"));
        functions.add(functionRecord("helper\"\\fn\n", "0000060a"));

        String response = ServerMetadata.buildListFunctionsJsonResponse(functions);

        assertEquals(
            "{\"ok\":true,\"data\":{\"functions\":[{\"name\":\"main\",\"address\":\"00000600\"}," +
            "{\"name\":\"helper\\\"\\\\fn\\n\",\"address\":\"0000060a\"}]}," +
            "\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testNoProgramJsonResponseUsesErrorEnvelope() throws Exception {
        String response = ServerMetadata.buildNoProgramJsonResponse();

        assertEquals(
            "{\"ok\":false,\"error\":{\"code\":\"no_program_loaded\"," +
            "\"message\":\"No program loaded\"},\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testFunctionJsonResponseUsesRichFunctionRecord() throws Exception {
        String response = invokeServerMetadataString(
            "buildFunctionJsonResponse",
            functionDetails("main", "Global", "00000600", "00000600", "00000609", "void main(void)"));

        assertEquals(
            "{\"ok\":true,\"data\":{\"function\":{\"name\":\"main\",\"namespace\":\"Global\"," +
            "\"entry\":\"00000600\",\"body_start\":\"00000600\",\"body_end\":\"00000609\"," +
            "\"signature\":\"void main(void)\"}},\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testDecompileJsonResponseIncludesFunctionAndText() throws Exception {
        String response = invokeServerMetadataString(
            "buildDecompileFunctionJsonResponse",
            functionDetails("main", "Global", "00000600", "00000600", "00000609", "void main(void)"),
            "void main(void) {\n}\n");

        assertEquals(
            "{\"ok\":true,\"data\":{\"function\":{\"name\":\"main\",\"namespace\":\"Global\"," +
            "\"entry\":\"00000600\",\"body_start\":\"00000600\",\"body_end\":\"00000609\"," +
            "\"signature\":\"void main(void)\"},\"decompile\":\"void main(void) {\\n}\\n\"}," +
            "\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    public void testErrorJsonResponseUsesCodeAndMessage() throws Exception {
        String response = invokeServerMetadataString(
            "buildErrorJsonResponse",
            "function_not_found",
            "No function found at address 00000600");

        assertEquals(
            "{\"ok\":false,\"error\":{\"code\":\"function_not_found\"," +
            "\"message\":\"No function found at address 00000600\"}," +
            "\"warnings\":[],\"meta\":{\"api_version\":\"1\"}}",
            response);
    }

    private Map<String, String> functionRecord(String name, String address) {
        Map<String, String> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("address", address);
        return function;
    }

    private Map<String, String> functionDetails(
            String name,
            String namespace,
            String entry,
            String bodyStart,
            String bodyEnd,
            String signature) {
        Map<String, String> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("namespace", namespace);
        function.put("entry", entry);
        function.put("body_start", bodyStart);
        function.put("body_end", bodyEnd);
        function.put("signature", signature);
        return function;
    }

    private String invokeServerMetadataString(String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = args[i] instanceof Map ? Map.class : String.class;
        }
        return (String) ServerMetadata.class.getMethod(methodName, parameterTypes).invoke(null, args);
    }
}
