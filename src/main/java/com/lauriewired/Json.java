package com.lauriewired;

import java.util.ArrayList;
import java.util.List;

public final class Json {

    private Json() {
    }

    public static String envelope(String dataJson) {
        return object(
            field("ok", bool(true)),
            field("data", dataJson),
            field("warnings", array(new ArrayList<>())),
            field("meta", meta()));
    }

    public static String envelope(String dataJson, int offset, int limit, Integer nextOffset) {
        return object(
            field("ok", bool(true)),
            field("data", dataJson),
            field("warnings", array(new ArrayList<>())),
            field("meta", meta(offset, limit, nextOffset)));
    }

    public static String errorEnvelope(String code, String message) {
        return object(
            field("ok", bool(false)),
            field("error", object(
                field("code", string(code)),
                field("message", string(message)))),
            field("warnings", array(new ArrayList<>())),
            field("meta", meta()));
    }

    public static String object(String... fields) {
        return "{" + String.join(",", fields) + "}";
    }

    public static String field(String name, String jsonValue) {
        return string(name) + ":" + jsonValue;
    }

    public static String array(Iterable<String> jsonValues) {
        List<String> values = new ArrayList<>();
        for (String value : jsonValues) {
            values.add(value);
        }
        return "[" + String.join(",", values) + "]";
    }

    public static String string(String value) {
        return "\"" + escape(value) + "\"";
    }

    public static String bool(boolean value) {
        return Boolean.toString(value);
    }

    public static String number(int value) {
        return Integer.toString(value);
    }

    public static String escape(String value) {
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

    private static String meta() {
        return object(field("api_version", string(ServerMetadata.API_VERSION)));
    }

    private static String meta(int offset, int limit, Integer nextOffset) {
        List<String> fields = new ArrayList<>();
        fields.add(field("api_version", string(ServerMetadata.API_VERSION)));
        fields.add(field("offset", number(offset)));
        fields.add(field("limit", number(limit)));
        if (nextOffset != null) {
            fields.add(field("next_offset", number(nextOffset)));
        }
        return object(fields.toArray(new String[0]));
    }
}
