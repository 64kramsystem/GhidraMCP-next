package com.lauriewired;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import junit.framework.TestCase;

public class HeadlessMCPServerParityTest extends TestCase {

    public void testHeadlessMetadataJsonMatchesServerMetadata() throws Exception {
        Class<?> headless = compileAndLoadHeadlessMetadata();

        Method health = getStaticStringMethod(headless, "buildHealthJsonResponse", String.class);
        Method version = getStaticStringMethod(headless, "buildVersionJsonResponse", String.class, String.class);
        Method functions = getStaticStringMethod(headless, "buildListFunctionsJsonResponse", List.class);
        Method function = getStaticStringMethod(headless, "buildFunctionJsonResponse", Map.class);
        Method decompile = getStaticStringMethod(headless, "buildDecompileFunctionJsonResponse", Map.class, String.class);
        Method xrefs = getStaticStringMethod(headless, "buildXrefsJsonResponse", List.class);
        Method paginatedXrefs = getStaticStringMethod(
            headless, "buildXrefsJsonResponse", List.class, int.class, int.class, Integer.class);
        Method error = getStaticStringMethod(headless, "buildErrorJsonResponse", String.class, String.class);
        Field jsonContentType = getStaticStringField(headless, "JSON_CONTENT_TYPE");
        List<Map<String, String>> functionRecords = new ArrayList<>();
        functionRecords.add(functionRecord("main", "00000600"));
        functionRecords.add(functionRecord("helper\"\\fn\n", "0000060a"));
        Map<String, String> functionDetails = functionDetails(
            "main", "Global", "00000600", "00000600", "00000609", "void main(void)");
        Map<String, String> helperDetails = functionDetails(
            "helper", "Global", "0000060a", "0000060a", "00000612", "void helper(void)");
        List<Map<String, String>> xrefRecords = new ArrayList<>();
        xrefRecords.add(xrefRecord("00000604", "0000060a", "UNCONDITIONAL_CALL", functionDetails, helperDetails));

        assertEquals(
            ServerMetadata.buildHealthJsonResponse("sample\"\\bin\n"),
            health.invoke(null, "sample\"\\bin\n"));
        assertEquals(
            ServerMetadata.buildVersionJsonResponse("12.1", "21.0.1"),
            version.invoke(null, "12.1", "21.0.1"));
        assertEquals(
            invokeServerMetadataListFunctions(functionRecords),
            functions.invoke(null, functionRecords));
        assertEquals(
            invokeServerMetadataString("buildFunctionJsonResponse", functionDetails),
            function.invoke(null, functionDetails));
        assertEquals(
            invokeServerMetadataString(
                "buildDecompileFunctionJsonResponse",
                functionDetails,
                "void main(void) {\n}\n"),
            decompile.invoke(null, functionDetails, "void main(void) {\n}\n"));
        assertEquals(
            invokeServerMetadataXrefs(xrefRecords),
            xrefs.invoke(null, xrefRecords));
        assertEquals(
            invokeServerMetadataPaginatedXrefs(xrefRecords, 0, 1, 1),
            paginatedXrefs.invoke(null, xrefRecords, 0, 1, Integer.valueOf(1)));
        assertEquals(
            invokeServerMetadataPaginatedXrefs(xrefRecords, 1, 1, null),
            paginatedXrefs.invoke(null, xrefRecords, 1, 1, null));
        assertEquals(
            invokeServerMetadataString(
                "buildErrorJsonResponse",
                "function_not_found",
                "No function found at address 00000600"),
            error.invoke(null, "function_not_found", "No function found at address 00000600"));
        assertEquals(invokeHttpResponsesJsonContentType(), jsonContentType.get(null));
    }

    private Class<?> compileAndLoadHeadlessMetadata() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("Headless parity test requires a JDK compiler", compiler);

        Path outputDirectory = Files.createTempDirectory("headless-mcpserver-test");
        String sourceFile = Paths.get("test", "HeadlessMCPServer.java").toString();
        int result = compiler.run(
            null,
            null,
            null,
            "-d", outputDirectory.toString(),
            "-classpath", buildClasspath(),
            sourceFile);
        assertEquals("HeadlessMCPServer.java should compile for parity checks", 0, result);

        URL[] urls = new URL[] { outputDirectory.toUri().toURL() };
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        URLClassLoader loader = new URLClassLoader(urls, parent);
        try {
            return Class.forName("HeadlessMetadata", true, loader);
        }
        catch (ClassNotFoundException e) {
            fail("Expected HeadlessMCPServer.java to compile a HeadlessMetadata parity helper");
            return null;
        }
    }

    private Method getStaticStringMethod(Class<?> clazz, String name, Class<?>... parameterTypes) throws Exception {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        }
        catch (NoSuchMethodException e) {
            fail("Expected HeadlessMCPServer." + name + " to mirror ServerMetadata output");
            return null;
        }
    }

    private Field getStaticStringField(Class<?> clazz, String name) throws Exception {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (NoSuchFieldException e) {
            fail("Expected HeadlessMetadata." + name + " to mirror HttpResponses");
            return null;
        }
    }

    private String buildClasspath() {
        StringBuilder classpath = new StringBuilder(System.getProperty("java.class.path"));
        classpath.append(File.pathSeparator).append(Paths.get("target", "classes").toAbsolutePath());

        File libDirectory = new File("lib");
        File[] jars = libDirectory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                classpath.append(File.pathSeparator).append(jar.getAbsolutePath());
            }
        }
        return classpath.toString();
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

    private Map<String, String> xrefRecord(
            String fromAddress,
            String toAddress,
            String referenceType,
            Map<String, String> fromFunction,
            Map<String, String> toFunction) {
        Map<String, String> xref = new LinkedHashMap<>();
        xref.put("from_address", fromAddress);
        xref.put("to_address", toAddress);
        xref.put("reference_type", referenceType);
        putPrefixedFunction(xref, "from_function_", fromFunction);
        putPrefixedFunction(xref, "to_function_", toFunction);
        return xref;
    }

    private void putPrefixedFunction(
            Map<String, String> target,
            String prefix,
            Map<String, String> function) {
        target.put(prefix + "name", function.get("name"));
        target.put(prefix + "namespace", function.get("namespace"));
        target.put(prefix + "entry", function.get("entry"));
        target.put(prefix + "body_start", function.get("body_start"));
        target.put(prefix + "body_end", function.get("body_end"));
        target.put(prefix + "signature", function.get("signature"));
    }

    private String invokeServerMetadataListFunctions(List<Map<String, String>> functions) throws Exception {
        Method method = ServerMetadata.class.getMethod("buildListFunctionsJsonResponse", List.class);
        return (String) method.invoke(null, functions);
    }

    private String invokeServerMetadataXrefs(List<Map<String, String>> xrefs) throws Exception {
        Method method = ServerMetadata.class.getMethod("buildXrefsJsonResponse", List.class);
        return (String) method.invoke(null, xrefs);
    }

    private String invokeServerMetadataPaginatedXrefs(
            List<Map<String, String>> xrefs,
            int offset,
            int limit,
            Integer nextOffset) throws Exception {
        Method method = ServerMetadata.class.getMethod(
            "buildXrefsJsonResponse", List.class, int.class, int.class, Integer.class);
        return (String) method.invoke(null, xrefs, offset, limit, nextOffset);
    }

    private String invokeHttpResponsesJsonContentType() throws Exception {
        try {
            Class<?> responses = Class.forName("com.lauriewired.HttpResponses");
            return (String) responses.getField("JSON_CONTENT_TYPE").get(null);
        }
        catch (ClassNotFoundException e) {
            fail("Expected HttpResponses.JSON_CONTENT_TYPE for shared HTTP metadata");
            return null;
        }
    }

    private String invokeServerMetadataString(String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = args[i] instanceof Map ? Map.class : String.class;
        }
        return (String) ServerMetadata.class.getMethod(methodName, parameterTypes).invoke(null, args);
    }
}
