package br.com.almoxarifado;

import java.util.*;
import java.lang.reflect.RecordComponent;

/** Serialização de respostas JSON; as requisições usam formulários URL encoded. */
public final class Json {
    private Json() {}
    public static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            StringJoiner out = new StringJoiner(",", "{", "}");
            map.forEach((k,v) -> out.add(encode(k.toString()) + ":" + encode(v))); return out.toString();
        }
        if (value instanceof Iterable<?> items) {
            StringJoiner out = new StringJoiner(",", "[", "]");
            items.forEach(v -> out.add(encode(v))); return out.toString();
        }
        if (value.getClass().isRecord()) {
            Map<String,Object> fields = new LinkedHashMap<>();
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                if (c.getName().equals("password")) continue;
                try { fields.put(c.getName(), c.getAccessor().invoke(value)); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            return encode(fields);
        }
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch(c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
