package com.lauriewired;

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

    public static String buildVersionResponse(String ghidraVersion, String javaVersion) {
        return "plugin: " + PLUGIN_NAME + "\n" +
            "api_version: " + API_VERSION + "\n" +
            "ghidra_version: " + valueOrUnknown(ghidraVersion) + "\n" +
            "java_version: " + valueOrUnknown(javaVersion);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
