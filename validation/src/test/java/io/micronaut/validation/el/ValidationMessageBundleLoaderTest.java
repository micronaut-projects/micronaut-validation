package io.micronaut.validation.el;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ValidationMessageBundleLoaderTest {
    @Test
    void preservesDefaultLocaleFallbackAndRootParentLookup() {
        Locale previous = Locale.getDefault();
        ClassLoader previousLoader = Thread.currentThread().getContextClassLoader();
        try {
            Locale.setDefault(Locale.US);
            var resources = new ResourceLoader(Map.of(
                "ValidationMessages.properties", "message=root\nparent=root-parent",
                "ValidationMessages_en.properties", "message=english",
                "ValidationMessages_fr.properties", "french=bonjour"));
            Thread.currentThread().setContextClassLoader(resources);
            assertEquals(Optional.of("english"), ValidationMessageBundleLoader.find("message", Locale.GERMANY));
            assertEquals(Optional.of("root"), ValidationMessageBundleLoader.find("message", Locale.FRANCE));
            assertEquals(Optional.empty(), ValidationMessageBundleLoader.find("french", Locale.GERMANY));
            assertEquals(Optional.of("root-parent"), ValidationMessageBundleLoader.find("parent", Locale.US));
            assertEquals(Optional.of("root"), ValidationMessageBundleLoader.find("message", Locale.ROOT));
        } finally {
            Locale.setDefault(previous);
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    private static final class ResourceLoader extends ClassLoader {
        private final Map<String, String> resources;
        ResourceLoader(Map<String, String> resources) {
            this.resources = resources;
        }
        @Override public URL getResource(String name) { return null; }
        @Override public InputStream getResourceAsStream(String name) {
            String value = resources.get(name);
            return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
