package com.lauriewired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ServerMetadata {

    public static final String PLUGIN_NAME = "GhidraMCP-next";
    public static final String API_VERSION = "1";

    private ServerMetadata() {
    }

    public static String buildHealthResponse(String programName) {
        boolean programLoaded = programName != null && !programName.isBlank();
        String safeProgramName = programLoaded ? programName : "";

        return "status: ok\n" +
            "program_loaded: " + programLoaded + "\n" +
            "program_name: " + safeProgramName;
    }

    public static String buildHealthJsonResponse(String programName) {
        boolean programLoaded = programName != null && !programName.isBlank();
        String safeProgramName = programLoaded ? programName : "";

        return Json.envelope(Json.object(
            Json.field("status", Json.string("ok")),
            Json.field("program_loaded", Json.bool(programLoaded)),
            Json.field("program_name", Json.string(safeProgramName))));
    }

    public static String buildVersionResponse(String ghidraVersion, String javaVersion) {
        return "plugin: " + PLUGIN_NAME + "\n" +
            "api_version: " + API_VERSION + "\n" +
            "ghidra_version: " + valueOrUnknown(ghidraVersion) + "\n" +
            "java_version: " + valueOrUnknown(javaVersion);
    }

    public static String buildVersionJsonResponse(String ghidraVersion, String javaVersion) {
        return Json.envelope(Json.object(
            Json.field("plugin", Json.string(PLUGIN_NAME)),
            Json.field("api_version", Json.string(API_VERSION)),
            Json.field("ghidra_version", Json.string(valueOrUnknown(ghidraVersion))),
            Json.field("java_version", Json.string(valueOrUnknown(javaVersion)))));
    }

    public static String buildListFunctionsJsonResponse(List<Map<String, String>> functions) {
        List<String> records = new ArrayList<>();
        for (Map<String, String> function : functions) {
            records.add(Json.object(
                Json.field("name", Json.string(function.get("name"))),
                Json.field("address", Json.string(function.get("address")))));
        }

        return Json.envelope(Json.object(
            Json.field("functions", Json.array(records))));
    }

    public static String buildNoProgramJsonResponse() {
        return Json.errorEnvelope("no_program_loaded", "No program loaded");
    }

    public static String buildFunctionJsonResponse(Map<String, String> function) {
        return Json.envelope(Json.object(
            Json.field("function", buildFunctionRecord(function))));
    }

    public static String buildDecompileFunctionJsonResponse(Map<String, String> function, String decompile) {
        return Json.envelope(Json.object(
            Json.field("function", buildFunctionRecord(function)),
            Json.field("decompile", Json.string(decompile))));
    }

    public static String buildXrefsJsonResponse(List<Map<String, String>> xrefs) {
        return buildXrefsJsonResponseData(xrefs, null, null, null);
    }

    public static String buildXrefsJsonResponse(
            List<Map<String, String>> xrefs,
            int offset,
            int limit,
            Integer nextOffset) {
        return buildXrefsJsonResponseData(xrefs, offset, limit, nextOffset);
    }

    private static String buildXrefsJsonResponseData(
            List<Map<String, String>> xrefs,
            Integer offset,
            Integer limit,
            Integer nextOffset) {
        List<String> records = new ArrayList<>();
        for (Map<String, String> xref : xrefs) {
            records.add(Json.object(
                Json.field("from_address", Json.string(xref.get("from_address"))),
                Json.field("to_address", Json.string(xref.get("to_address"))),
                Json.field("reference_type", Json.string(xref.get("reference_type"))),
                Json.field("from_function", buildPrefixedFunctionRecord(xref, "from_function_")),
                Json.field("to_function", buildPrefixedFunctionRecord(xref, "to_function_"))));
        }

        String data = Json.object(Json.field("xrefs", Json.array(records)));
        if (offset == null || limit == null) {
            return Json.envelope(data);
        }
        return Json.envelope(data, offset, limit, nextOffset);
    }

    public static String buildErrorJsonResponse(String code, String message) {
        return Json.errorEnvelope(code, message);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String buildFunctionRecord(Map<String, String> function) {
        return Json.object(
            Json.field("name", Json.string(function.get("name"))),
            Json.field("namespace", Json.string(function.get("namespace"))),
            Json.field("entry", Json.string(function.get("entry"))),
            Json.field("body_start", Json.string(function.get("body_start"))),
            Json.field("body_end", Json.string(function.get("body_end"))),
            Json.field("signature", Json.string(function.get("signature"))));
    }

    private static String buildPrefixedFunctionRecord(Map<String, String> values, String prefix) {
        return Json.object(
            Json.field("name", Json.string(values.get(prefix + "name"))),
            Json.field("namespace", Json.string(values.get(prefix + "namespace"))),
            Json.field("entry", Json.string(values.get(prefix + "entry"))),
            Json.field("body_start", Json.string(values.get(prefix + "body_start"))),
            Json.field("body_end", Json.string(values.get(prefix + "body_end"))),
            Json.field("signature", Json.string(values.get(prefix + "signature"))));
    }
}
