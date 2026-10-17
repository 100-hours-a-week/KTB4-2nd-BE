package ci.context;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.SpringVersion;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.cache.ContextCache;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.web.WebMergedContextConfiguration;

/** CI-only observer. Does not load contexts, call cache.get(), or change cache policy. */
public final class ContextCacheObserver extends AbstractTestExecutionListener {
    // Keys use Spring equals(), including customizer equality, not toString()/hashCode guesses.
    private static final Map<MergedContextConfiguration, Integer> KEYS = new HashMap<>();
    private static final Map<String, Map<Object, Integer>> COMPONENTS = new HashMap<>();
    private static final Map<ContextCache, Integer> CACHES = new IdentityHashMap<>();
    private static final Map<Integer, List<WeakReference<ApplicationContext>>> GENERATIONS = new HashMap<>();
    private static long sequence;

    @Override public int getOrder() { return Integer.MAX_VALUE; }
    @Override public void beforeTestClass(TestContext context) { observe(context, "beforeClass"); }
    @Override public void prepareTestInstance(TestContext context) { observe(context, "prepared"); }
    @Override public void beforeTestMethod(TestContext context) { observe(context, "beforeMethod"); }
    @Override public void afterTestClass(TestContext context) { observe(context, "afterClass"); }

    private static void observe(TestContext test, String phase) {
        if (System.getProperty("ci.context.output") == null) return;
        try {
            if (!"7.0.9".equals(SpringVersion.getVersion())) {
                throw new IllegalStateException("Unsupported Spring version");
            }
            Object delegate = field(test, "cacheAwareContextLoaderDelegate");
            ContextCache cache = (ContextCache) field(delegate, "contextCache");
            MergedContextConfiguration requested = (MergedContextConfiguration) field(test, "mergedConfig");
            // Spring's delegate synchronizes on the cache. Snapshot iteration does not update LRU order.
            synchronized (cache) {
                @SuppressWarnings("unchecked")
                Map<MergedContextConfiguration, ApplicationContext> map =
                        (Map<MergedContextConfiguration, ApplicationContext>) field(cache, "contextMap");
                synchronized (map) {
                    synchronized (ContextCacheObserver.class) {
                        int cacheId = CACHES.computeIfAbsent(cache, ignored -> CACHES.size() + 1);
                        int requestedId = key(requested);
                        List<Integer> resident = new ArrayList<>();
                        for (var entry : map.entrySet()) {
                            int keyId = key(entry.getKey());
                            resident.add(keyId);
                            discover(cacheId, keyId, entry.getValue());
                        }
                        // No method name, property values, env, or test output is serialized.
                        emit(row("snapshot", "cache", cacheId, "phase", phase,
                                "testClass", test.getTestClass().getName(), "key", requestedId,
                                "resident", resident, "hits", cache.getHitCount(),
                                "misses", cache.getMissCount(), "size", cache.size()));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            synchronized (ContextCacheObserver.class) {
                emit(row("error", "errorType", failure.getClass().getName()));
            }
        }
    }

    private static int key(MergedContextConfiguration config) throws ReflectiveOperationException {
        Integer existing = KEYS.get(config);
        if (existing != null) return existing;
        int id = KEYS.size() + 1;
        KEYS.put(config, id);
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("configType", component("configType", config.getClass()));
        components.put("locations", component("locations", Arrays.asList(config.getLocations())));
        components.put("classes", component("classes", Arrays.asList(config.getClasses())));
        components.put("initializers", component("initializers", config.getContextInitializerClasses()));
        components.put("profiles", component("profiles", Arrays.asList(config.getActiveProfiles())));
        components.put("propertyDescriptors", component("propertyDescriptors", config.getPropertySourceDescriptors()));
        components.put("properties", component("properties", Arrays.asList(config.getPropertySourceProperties())));
        components.put("customizers", component("customizers", config.getContextCustomizers()));
        components.put("loader", component("loader", config.getContextLoader() == null ? "none" : config.getContextLoader().getClass().getName()));
        components.put("parent", config.getParent() == null ? 0 : key(config.getParent()));
        components.put("resourceBasePath", component("resourceBasePath",
                config instanceof WebMergedContextConfiguration web ? web.getResourceBasePath() : "none"));
        List<Map<String, Object>> customizers = new ArrayList<>();
        for (var customizer : config.getContextCustomizers()) {
            customizers.add(row("customizer", "type", customizer.getClass().getName(),
                    "equalityGroup", component("customizer:" + customizer.getClass().getName(), customizer)));
        }
        List<String> classes = Arrays.stream(config.getClasses()).map(Class::getName).toList();
        emit(row("key", "key", id, "components", components, "classes", classes,
                "customizers", customizers));
        return id;
    }

    private static int component(String category, Object value) {
        Map<Object, Integer> values = COMPONENTS.computeIfAbsent(category, ignored -> new HashMap<>());
        return values.computeIfAbsent(value, ignored -> values.size() + 1);
    }

    private static void discover(int cacheId, int key, ApplicationContext context) {
        List<WeakReference<ApplicationContext>> seen = GENERATIONS.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (var reference : seen) if (reference.get() == context) return;
        var reference = new WeakReference<>(context);
        seen.add(reference);
        int generation = seen.size();
        emit(row("discovered", "cache", cacheId, "key", key, "generation", generation,
                "refreshStartMs", context.getStartupDate(), "discoveredMs", System.currentTimeMillis()));
        if (context instanceof ConfigurableApplicationContext configurable) {
            // Weak reference avoids retaining a closed ApplicationContext in the observer registry.
            configurable.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent && event.getSource() == reference.get()) {
                    synchronized (ContextCacheObserver.class) {
                        emit(row("closed", "cache", cacheId, "key", key,
                                "generation", generation, "reason", closeReason()));
                    }
                }
            });
        } else {
            emit(row("error", "errorType", "NonConfigurableContext"));
        }
    }

    private static String closeReason() {
        boolean dirty = false, explicit = false;
        for (var frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName(), method = frame.getMethodName();
            if (name.equals("org.springframework.test.context.cache.DefaultContextCache")
                    && method.equals("evictLruContextIfNecessary")) return "lru";
            if (name.startsWith("org.springframework.test.context.support.DirtiesContext")) dirty = true;
            if (name.equals("org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate")
                    && method.equals("closeContext")) explicit = true;
        }
        return dirty ? "dirties" : explicit ? "explicit" : "other";
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Map<String, Object> row(String event, Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", event);
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    private static void emit(Map<String, Object> row) {
        row.put("sequence", ++sequence);
        row.put("timestampMs", System.currentTimeMillis());
        row.put("worker", System.getProperty("org.gradle.test.worker", "unknown"));
        row.put("springVersion", SpringVersion.getVersion());
        try {
            Path directory = Path.of(System.getProperty("ci.context.output"));
            Files.createDirectories(directory);
            Path output = directory.resolve("context-events-" + System.getProperty("org.gradle.test.worker", "unknown") + ".jsonl");
            Files.writeString(output, json(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failure) {
            System.err.println("CI_CONTEXT_OBSERVER_WRITE_FAILED");
        }
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((k, v) -> entries.add(json(k.toString()) + ":" + json(v)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> entries = new ArrayList<>();
            items.forEach(v -> entries.add(json(v)));
            return "[" + String.join(",", entries) + "]";
        }
        String s = value.toString();
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
