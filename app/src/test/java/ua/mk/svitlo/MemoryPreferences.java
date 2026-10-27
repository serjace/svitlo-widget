package ua.mk.svitlo;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Small persistent in-memory SharedPreferences boundary for JVM diagnostic tests. */
final class MemoryPreferences {
    private final Map<String, Object> data = new HashMap<>();
    final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("edit")) return editor();
                if (name.equals("getAll")) return new HashMap<>(data);
                if (name.equals("contains")) return data.containsKey(args[0]);
                if (name.startsWith("get")) return data.getOrDefault(args[0], args[1]);
                throw new UnsupportedOperationException(name);
            });

    private SharedPreferences.Editor editor() {
        Map<String, Object> pending = new HashMap<>();
        return (SharedPreferences.Editor) Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) { pending.put((String) args[0], args[1]); return proxy; }
                    if (name.equals("remove")) { pending.put((String) args[0], null); return proxy; }
                    if (name.equals("apply") || name.equals("commit")) {
                        for (Map.Entry<String, Object> e : pending.entrySet()) {
                            if (e.getValue() == null) data.remove(e.getKey()); else data.put(e.getKey(), e.getValue());
                        }
                        return name.equals("commit") ? true : null;
                    }
                    throw new UnsupportedOperationException(name);
                });
    }
}
