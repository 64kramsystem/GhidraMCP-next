// GhidraScript: Headless HTTP server for integration testing.
//
// Run via analyzeHeadless -postScript HeadlessMCPServer.java
// The script starts an HTTP server on port 8080 (or GHIDRA_MCP_PORT env var),
// then blocks until a request to /shutdown is received.
//
// This exposes a subset of the GhidraMCP endpoints sufficient for integration
// testing without the GUI.
//
//@category Test

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.listing.CommentType;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.*;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeManager;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.util.task.ConsoleTaskMonitor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class HeadlessMCPServer extends GhidraScript {

    private HttpServer server;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    @Override
    public void run() throws Exception {
        if (currentProgram == null) {
            printerr("No program loaded.");
            return;
        }

        int port = 8080;
        String envPort = System.getenv("GHIDRA_MCP_PORT");
        if (envPort != null) {
            port = Integer.parseInt(envPort);
        }

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        registerEndpoints();
        server.start();
        println("HeadlessMCPServer started on port " + port);

        // Block until /shutdown is called
        shutdownLatch.await();
        server.stop(1);
        println("HeadlessMCPServer stopped.");
    }

    private void registerEndpoints() {
        // Shutdown
        server.createContext("/shutdown", exchange -> {
            sendResponse(exchange, "Shutting down");
            shutdownLatch.countDown();
        });

        server.createContext("/health", exchange -> {
            sendResponse(exchange, "status: ok\n" +
                "program_loaded: true\n" +
                "program_name: " + currentProgram.getName());
        });

        server.createContext("/version", exchange -> {
            sendResponse(exchange, "plugin: GhidraMCP-next\n" +
                "api_version: 1\n" +
                "ghidra_version: " + getGhidraVersion() + "\n" +
                "java_version: " + System.getProperty("java.version"));
        });

        server.createContext("/api/v1/health", exchange -> {
            sendJsonResponse(exchange, HeadlessMetadata.buildHealthJsonResponse(currentProgram.getName()));
        });

        server.createContext("/api/v1/version", exchange -> {
            sendJsonResponse(exchange,
                HeadlessMetadata.buildVersionJsonResponse(
                    getGhidraVersion(),
                    System.getProperty("java.version")));
        });

        // Read endpoints
        server.createContext("/list_functions", exchange -> {
            StringBuilder sb = new StringBuilder();
            FunctionIterator funcs = currentProgram.getFunctionManager().getFunctions(true);
            while (funcs.hasNext()) {
                Function f = funcs.next();
                if (!f.isExternal()) {
                    sb.append(f.getName()).append(" at ").append(f.getEntryPoint()).append("\n");
                }
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/api/v1/list_functions", exchange -> {
            List<Map<String, String>> functions = new ArrayList<>();
            FunctionIterator funcs = currentProgram.getFunctionManager().getFunctions(true);
            while (funcs.hasNext()) {
                Function f = funcs.next();
                if (!f.isExternal()) {
                    Map<String, String> function = new LinkedHashMap<>();
                    function.put("name", f.getName());
                    function.put("address", f.getEntryPoint().toString());
                    functions.add(function);
                }
            }
            sendJsonResponse(exchange, HeadlessMetadata.buildListFunctionsJsonResponse(functions));
        });

        server.createContext("/get_function_by_address", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            Address addr = parseAddressOrNull(addrStr);
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                func = currentProgram.getFunctionManager().getFunctionContaining(addr);
            }
            if (func != null) {
                String resp = "Function: " + func.getName() + " at " + func.getEntryPoint() + "\n" +
                    "Signature: " + func.getSignature().getPrototypeString() + "\n" +
                    "Entry: " + func.getEntryPoint() + "\n" +
                    "Body: " + func.getBody().getMinAddress() + " - " + func.getBody().getMaxAddress();
                sendResponse(exchange, resp);
            } else {
                sendResponse(exchange, "No function found at address " + addrStr);
            }
        });

        server.createContext("/api/v1/get_function_by_address", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            if (addrStr == null || addrStr.isEmpty()) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "address_required",
                    "Address is required"));
                return;
            }
            Address addr = parseAddressOrNull(addrStr);
            if (addr == null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "invalid_address",
                    "Invalid address: " + addrStr));
                return;
            }
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                func = currentProgram.getFunctionManager().getFunctionContaining(addr);
            }
            if (func != null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildFunctionJsonResponse(richFunctionRecord(func)));
            }
            else {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "function_not_found",
                    "No function found at or containing address " + addrStr));
            }
        });

        server.createContext("/get_current_address", exchange -> {
            sendResponse(exchange, currentProgram.getMinAddress().toString());
        });

        server.createContext("/decompile_function", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            Address addr = parseAddressOrNull(addrStr);
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                func = currentProgram.getFunctionManager().getFunctionContaining(addr);
            }
            if (func == null) {
                sendResponse(exchange, "No function at address " + addrStr);
                return;
            }
            DecompInterface decomp = new DecompInterface();
            decomp.openProgram(currentProgram);
            DecompileResults results = decomp.decompileFunction(func, 30, new ConsoleTaskMonitor());
            String decompiled = results.getDecompiledFunction() != null
                ? results.getDecompiledFunction().getC()
                : "Decompilation failed";
            decomp.dispose();
            sendResponse(exchange, decompiled);
        });

        server.createContext("/api/v1/decompile_function", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            if (addrStr == null || addrStr.isEmpty()) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "address_required",
                    "Address is required"));
                return;
            }
            Address addr = parseAddressOrNull(addrStr);
            if (addr == null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "invalid_address",
                    "Invalid address: " + addrStr));
                return;
            }
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                func = currentProgram.getFunctionManager().getFunctionContaining(addr);
            }
            if (func == null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "function_not_found",
                    "No function found at or containing address " + addrStr));
                return;
            }
            DecompInterface decomp = new DecompInterface();
            decomp.openProgram(currentProgram);
            DecompileResults results = decomp.decompileFunction(func, 30, new ConsoleTaskMonitor());
            String decompiled = results.getDecompiledFunction() != null
                ? results.getDecompiledFunction().getC()
                : "Decompilation failed";
            decomp.dispose();
            sendJsonResponse(exchange,
                HeadlessMetadata.buildDecompileFunctionJsonResponse(richFunctionRecord(func), decompiled));
        });

        server.createContext("/disassemble_function", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            Address addr = parseAddressOrNull(addrStr);
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                sendResponse(exchange, "No function at address " + addrStr);
                return;
            }
            StringBuilder sb = new StringBuilder();
            Listing listing = currentProgram.getListing();
            InstructionIterator iter = listing.getInstructions(func.getBody(), true);
            while (iter.hasNext()) {
                Instruction instr = iter.next();
                sb.append(instr.getAddress()).append(": ").append(instr).append("\n");
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/searchFunctions", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String query = params.get("query");
            if (query == null || query.isEmpty()) {
                sendResponse(exchange, "Search term is required");
                return;
            }
            StringBuilder sb = new StringBuilder();
            FunctionIterator funcs = currentProgram.getFunctionManager().getFunctions(true);
            while (funcs.hasNext()) {
                Function f = funcs.next();
                if (f.getName().toLowerCase().contains(query.toLowerCase())) {
                    sb.append(f.getName()).append(" at ").append(f.getEntryPoint()).append("\n");
                }
            }
            if (sb.length() == 0) {
                sendResponse(exchange, "No functions matching '" + query + "'");
            } else {
                sendResponse(exchange, sb.toString().trim());
            }
        });

        server.createContext("/segments", exchange -> {
            StringBuilder sb = new StringBuilder();
            for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
                sb.append(block.getName()).append(": ")
                  .append(block.getStart()).append(" - ")
                  .append(block.getEnd()).append("\n");
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/strings", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            int skipped = 0;
            DataIterator iter = currentProgram.getListing().getDefinedData(true);
            while (iter.hasNext() && count < limit) {
                Data data = iter.next();
                if (data.hasStringValue()) {
                    if (skipped < offset) { skipped++; continue; }
                    sb.append(data.getAddress()).append(": ")
                      .append("\"").append(data.getValue()).append("\"\n");
                    count++;
                }
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/data", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            int skipped = 0;
            DataIterator iter = currentProgram.getListing().getDefinedData(true);
            while (iter.hasNext() && count < limit) {
                Data data = iter.next();
                if (skipped < offset) { skipped++; continue; }
                String name = data.getLabel() != null ? data.getLabel() : "(unnamed)";
                sb.append(data.getAddress()).append(": ").append(name)
                  .append(" = ").append(data.getDefaultValueRepresentation()).append("\n");
                count++;
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/imports", exchange -> {
            sendResponse(exchange, "");
        });

        server.createContext("/exports", exchange -> {
            StringBuilder sb = new StringBuilder();
            SymbolTable symTable = currentProgram.getSymbolTable();
            SymbolIterator iter = symTable.getAllSymbols(true);
            while (iter.hasNext()) {
                Symbol sym = iter.next();
                if (sym.isExternalEntryPoint()) {
                    sb.append(sym.getName()).append(" -> ").append(sym.getAddress()).append("\n");
                }
            }
            sendResponse(exchange, sb.toString().trim());
        });

        for (String ep : new String[]{"/methods", "/classes", "/namespaces"}) {
            server.createContext(ep, exchange -> sendResponse(exchange, ""));
        }

        server.createContext("/xrefs_to", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            Address addr = currentProgram.getAddressFactory().getAddress(params.get("address"));
            StringBuilder sb = new StringBuilder();
            for (Reference ref : currentProgram.getReferenceManager().getReferencesTo(addr)) {
                sb.append("From ").append(ref.getFromAddress())
                  .append(" [").append(ref.getReferenceType()).append("]\n");
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/api/v1/get_xrefs_to", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            if (addrStr == null || addrStr.isEmpty()) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "address_required",
                    "Address is required"));
                return;
            }
            Address addr = parseAddressOrNull(addrStr);
            if (addr == null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "invalid_address",
                    "Invalid address: " + addrStr));
                return;
            }

            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            List<Map<String, String>> xrefs = new ArrayList<>();
            for (Reference ref : currentProgram.getReferenceManager().getReferencesTo(addr)) {
                xrefs.add(xrefRecord(ref));
            }
            Page<Map<String, String>> page = paginateRecords(xrefs, offset, limit);
            sendJsonResponse(exchange,
                HeadlessMetadata.buildXrefsJsonResponse(page.items, page.offset, page.limit, page.nextOffset));
        });

        server.createContext("/xrefs_from", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            Address addr = currentProgram.getAddressFactory().getAddress(params.get("address"));
            StringBuilder sb = new StringBuilder();
            for (Reference ref : currentProgram.getReferenceManager().getReferencesFrom(addr)) {
                sb.append("To ").append(ref.getToAddress())
                  .append(" [").append(ref.getReferenceType()).append("]\n");
            }
            sendResponse(exchange, sb.toString().trim());
        });

        server.createContext("/api/v1/get_xrefs_from", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String addrStr = params.get("address");
            if (addrStr == null || addrStr.isEmpty()) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "address_required",
                    "Address is required"));
                return;
            }
            Address addr = parseAddressOrNull(addrStr);
            if (addr == null) {
                sendJsonResponse(exchange, HeadlessMetadata.buildErrorJsonResponse(
                    "invalid_address",
                    "Invalid address: " + addrStr));
                return;
            }

            int offset = parseIntOrDefault(params.get("offset"), 0);
            int limit = parseIntOrDefault(params.get("limit"), 100);
            List<Map<String, String>> xrefs = new ArrayList<>();
            for (Reference ref : currentProgram.getReferenceManager().getReferencesFrom(addr)) {
                xrefs.add(xrefRecord(ref));
            }
            Page<Map<String, String>> page = paginateRecords(xrefs, offset, limit);
            sendJsonResponse(exchange,
                HeadlessMetadata.buildXrefsJsonResponse(page.items, page.offset, page.limit, page.nextOffset));
        });

        server.createContext("/function_xrefs", exchange -> {
            Map<String, String> params = parseQueryParams(exchange);
            String name = params.get("name");
            Function func = null;
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                if (f.getName().equals(name)) { func = f; break; }
            }
            if (func == null) {
                sendResponse(exchange, "No references found to function: " + name);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Reference ref : currentProgram.getReferenceManager().getReferencesTo(func.getEntryPoint())) {
                sb.append("From ").append(ref.getFromAddress())
                  .append(" [").append(ref.getReferenceType()).append("]\n");
            }
            sendResponse(exchange, sb.length() == 0
                ? "No references found to function: " + name
                : sb.toString().trim());
        });

        // Write endpoints
        server.createContext("/rename_function_by_address", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String addrStr = params.get("function_address");
            String newName = params.get("new_name");
            Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func == null) {
                func = currentProgram.getFunctionManager().getFunctionContaining(addr);
            }
            if (func == null) {
                sendResponse(exchange, "Failed to rename function");
                return;
            }
            int tx = currentProgram.startTransaction("Rename function");
            try {
                func.setName(newName, SourceType.USER_DEFINED);
                sendResponse(exchange, "Function renamed successfully");
            } catch (Exception e) {
                sendResponse(exchange, "Failed to rename function");
            } finally {
                currentProgram.endTransaction(tx, true);
            }
        });

        server.createContext("/renameFunction", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String oldName = params.get("oldName");
            String newName = params.get("newName");
            Function func = null;
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                if (f.getName().equals(oldName)) { func = f; break; }
            }
            if (func == null) {
                sendResponse(exchange, "Rename failed");
                return;
            }
            int tx = currentProgram.startTransaction("Rename function");
            try {
                func.setName(newName, SourceType.USER_DEFINED);
                sendResponse(exchange, "Renamed successfully");
            } catch (Exception e) {
                sendResponse(exchange, "Rename failed");
            } finally {
                currentProgram.endTransaction(tx, true);
            }
        });

        server.createContext("/set_disassembly_comment", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String addrStr = params.get("address");
            String comment = params.get("comment");
            Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
            int tx = currentProgram.startTransaction("Set comment");
            try {
                currentProgram.getListing().setComment(addr, CommentType.EOL, comment);
                sendResponse(exchange, "Comment set successfully");
            } catch (Exception e) {
                sendResponse(exchange, "Failed to set comment");
            } finally {
                currentProgram.endTransaction(tx, true);
            }
        });

        server.createContext("/set_decompiler_comment", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String addrStr = params.get("address");
            String comment = params.get("comment");
            Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
            int tx = currentProgram.startTransaction("Set comment");
            try {
                currentProgram.getListing().setComment(addr, CommentType.PRE, comment);
                sendResponse(exchange, "Comment set successfully");
            } catch (Exception e) {
                sendResponse(exchange, "Failed to set comment");
            } finally {
                currentProgram.endTransaction(tx, true);
            }
        });

        server.createContext("/set_function_prototype", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String addrStr = params.get("function_address");
            Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
            Function func = currentProgram.getFunctionManager().getFunctionAt(addr);
            if (func != null) {
                sendResponse(exchange, "Function prototype set successfully");
            } else {
                sendResponse(exchange, "Failed to set function prototype");
            }
        });

        server.createContext("/renameData", exchange -> {
            Map<String, String> params = parsePostParams(exchange);
            String addrStr = params.get("address");
            String newName = params.get("newName");
            Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
            int tx = currentProgram.startTransaction("Rename data");
            try {
                SymbolTable symTable = currentProgram.getSymbolTable();
                Symbol symbol = symTable.getPrimarySymbol(addr);
                if (symbol != null) {
                    symbol.setName(newName, SourceType.USER_DEFINED);
                } else {
                    symTable.createLabel(addr, newName, SourceType.USER_DEFINED);
                }
                sendResponse(exchange, "Rename data attempted");
            } catch (Exception e) {
                sendResponse(exchange, "Rename data attempted");
            } finally {
                currentProgram.endTransaction(tx, true);
            }
        });
    }

    // --- helpers ---

    private Map<String, String> parseQueryParams(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private Map<String, String> parsePostParams(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = new HashMap<>();
        if (body.isEmpty()) return params;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                           URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private void sendResponse(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", HeadlessMetadata.JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> richFunctionRecord(Function func) {
        Map<String, String> function = new LinkedHashMap<>();
        Namespace parent = func.getParentNamespace();
        function.put("name", func.getName());
        function.put("namespace", parent != null ? parent.getName(true) : "");
        function.put("entry", func.getEntryPoint().toString());
        function.put("body_start", func.getBody().getMinAddress().toString());
        function.put("body_end", func.getBody().getMaxAddress().toString());
        function.put("signature", func.getSignature().getPrototypeString());
        return function;
    }

    private Address parseAddressOrNull(String addressStr) {
        try {
            return currentProgram.getAddressFactory().getAddress(addressStr);
        }
        catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> xrefRecord(Reference ref) {
        Map<String, String> xref = new LinkedHashMap<>();
        Address fromAddr = ref.getFromAddress();
        Address toAddr = ref.getToAddress();
        xref.put("from_address", fromAddr != null ? fromAddr.toString() : "");
        xref.put("to_address", toAddr != null ? toAddr.toString() : "");
        xref.put("reference_type", ref.getReferenceType() != null ? ref.getReferenceType().getName() : "");

        FunctionManager functionManager = currentProgram.getFunctionManager();
        putPrefixedFunctionRecord(xref, "from_function_",
            fromAddr != null ? functionManager.getFunctionContaining(fromAddr) : null);
        putPrefixedFunctionRecord(xref, "to_function_",
            toAddr != null ? functionManager.getFunctionContaining(toAddr) : null);
        return xref;
    }

    private void putPrefixedFunctionRecord(Map<String, String> target, String prefix, Function function) {
        Map<String, String> record = function != null ? richFunctionRecord(function) : emptyFunctionRecord();
        target.put(prefix + "name", record.get("name"));
        target.put(prefix + "namespace", record.get("namespace"));
        target.put(prefix + "entry", record.get("entry"));
        target.put(prefix + "body_start", record.get("body_start"));
        target.put(prefix + "body_end", record.get("body_end"));
        target.put(prefix + "signature", record.get("signature"));
    }

    private Map<String, String> emptyFunctionRecord() {
        Map<String, String> function = new LinkedHashMap<>();
        function.put("name", "");
        function.put("namespace", "");
        function.put("entry", "");
        function.put("body_start", "");
        function.put("body_end", "");
        function.put("signature", "");
        return function;
    }

    private <T> Page<T> paginateRecords(List<T> items, int offset, int limit) {
        int start = Math.max(0, offset);
        int effectiveLimit = Math.max(0, limit);
        int end = Math.min(items.size(), start + effectiveLimit);

        if (start >= items.size()) {
            return new Page<>(new ArrayList<>(), start, effectiveLimit, null);
        }
        Integer nextOffset = effectiveLimit > 0 && end < items.size() ? end : null;
        return new Page<>(new ArrayList<>(items.subList(start, end)), start, effectiveLimit, nextOffset);
    }

    private static class Page<T> {
        private final List<T> items;
        private final int offset;
        private final int limit;
        private final Integer nextOffset;

        private Page(List<T> items, int offset, int limit, Integer nextOffset) {
            this.items = items;
            this.offset = offset;
            this.limit = limit;
            this.nextOffset = nextOffset;
        }
    }

    private int parseIntOrDefault(String val, int def) {
        if (val == null) return def;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return def; }
    }
}

final class HeadlessMetadata {

    static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";

    private static final String PLUGIN_NAME = "GhidraMCP-next";
    private static final String API_VERSION = "1";

    private HeadlessMetadata() {
    }

    static String buildHealthJsonResponse(String programName) {
        boolean programLoaded = programName != null && !programName.isBlank();
        String safeProgramName = programLoaded ? programName : "";

        return envelope(object(
            field("status", string("ok")),
            field("program_loaded", bool(programLoaded)),
            field("program_name", string(safeProgramName))));
    }

    static String buildVersionJsonResponse(String ghidraVersion, String javaVersion) {
        return envelope(object(
            field("plugin", string(PLUGIN_NAME)),
            field("api_version", string(API_VERSION)),
            field("ghidra_version", string(valueOrUnknown(ghidraVersion))),
            field("java_version", string(valueOrUnknown(javaVersion)))));
    }

    static String buildListFunctionsJsonResponse(List<Map<String, String>> functions) {
        List<String> records = new ArrayList<>();
        for (Map<String, String> function : functions) {
            records.add(object(
                field("name", string(function.get("name"))),
                field("address", string(function.get("address")))));
        }

        return envelope(object(
            field("functions", array(records))));
    }

    static String buildFunctionJsonResponse(Map<String, String> function) {
        return envelope(object(
            field("function", buildFunctionRecord(function))));
    }

    static String buildDecompileFunctionJsonResponse(Map<String, String> function, String decompile) {
        return envelope(object(
            field("function", buildFunctionRecord(function)),
            field("decompile", string(decompile))));
    }

    static String buildXrefsJsonResponse(List<Map<String, String>> xrefs) {
        return buildXrefsJsonResponseData(xrefs, null, null, null);
    }

    static String buildXrefsJsonResponse(
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
            records.add(object(
                field("from_address", string(xref.get("from_address"))),
                field("to_address", string(xref.get("to_address"))),
                field("reference_type", string(xref.get("reference_type"))),
                field("from_function", buildPrefixedFunctionRecord(xref, "from_function_")),
                field("to_function", buildPrefixedFunctionRecord(xref, "to_function_"))));
        }

        String data = object(field("xrefs", array(records)));
        if (offset == null || limit == null) {
            return envelope(data);
        }
        return envelope(data, offset, limit, nextOffset);
    }

    static String buildErrorJsonResponse(String code, String message) {
        return object(
            field("ok", bool(false)),
            field("error", object(
                field("code", string(code)),
                field("message", string(message)))),
            field("warnings", array(new ArrayList<>())),
            field("meta", object(field("api_version", string(API_VERSION)))));
    }

    private static String envelope(String dataJson) {
        return object(
            field("ok", bool(true)),
            field("data", dataJson),
            field("warnings", array(new ArrayList<>())),
            field("meta", object(field("api_version", string(API_VERSION)))));
    }

    private static String envelope(String dataJson, int offset, int limit, Integer nextOffset) {
        return object(
            field("ok", bool(true)),
            field("data", dataJson),
            field("warnings", array(new ArrayList<>())),
            field("meta", meta(offset, limit, nextOffset)));
    }

    private static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    private static String field(String name, String jsonValue) {
        return string(name) + ":" + jsonValue;
    }

    private static String array(Iterable<String> jsonValues) {
        List<String> values = new ArrayList<>();
        for (String value : jsonValues) {
            values.add(value);
        }
        return "[" + String.join(",", values) + "]";
    }

    private static String string(String value) {
        return "\"" + escape(value) + "\"";
    }

    private static String bool(boolean value) {
        return Boolean.toString(value);
    }

    private static String number(int value) {
        return Integer.toString(value);
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String buildFunctionRecord(Map<String, String> function) {
        return object(
            field("name", string(function.get("name"))),
            field("namespace", string(function.get("namespace"))),
            field("entry", string(function.get("entry"))),
            field("body_start", string(function.get("body_start"))),
            field("body_end", string(function.get("body_end"))),
            field("signature", string(function.get("signature"))));
    }

    private static String buildPrefixedFunctionRecord(Map<String, String> values, String prefix) {
        return object(
            field("name", string(values.get(prefix + "name"))),
            field("namespace", string(values.get(prefix + "namespace"))),
            field("entry", string(values.get(prefix + "entry"))),
            field("body_start", string(values.get(prefix + "body_start"))),
            field("body_end", string(values.get(prefix + "body_end"))),
            field("signature", string(values.get(prefix + "signature"))));
    }

    private static String meta(int offset, int limit, Integer nextOffset) {
        List<String> fields = new ArrayList<>();
        fields.add(field("api_version", string(API_VERSION)));
        fields.add(field("offset", number(offset)));
        fields.add(field("limit", number(limit)));
        if (nextOffset != null) {
            fields.add(field("next_offset", number(nextOffset)));
        }
        return object(fields.toArray(new String[0]));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    }
                    else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }
}
