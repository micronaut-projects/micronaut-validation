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
            assertEquals(Optional.of("english"), new ValidationMessageBundleLoader(resources).find("message", Locale.GERMANY));
            assertEquals(Optional.of("root"), new ValidationMessageBundleLoader(resources).find("message", Locale.FRANCE));
            assertEquals(Optional.empty(), new ValidationMessageBundleLoader(resources).find("french", Locale.GERMANY));
            assertEquals(Optional.of("root-parent"), new ValidationMessageBundleLoader(resources).find("parent", Locale.US));
            assertEquals(Optional.of("root"), new ValidationMessageBundleLoader(resources).find("message", Locale.ROOT));
        } finally {
            Locale.setDefault(previous);
            Thread.currentThread().setContextClassLoader(previousLoader);
        }
    }

    @Test
    void cachesBundlesAndMissingLocalesWithinTheirApplicationLoader() {
        var first = new ResourceLoader(Map.of("ValidationMessages.properties", "message=first"));
        var second = new ResourceLoader(Map.of("ValidationMessages.properties", "message=second"));
        var firstBundles = new ValidationMessageBundleLoader(first);
        var secondBundles = new ValidationMessageBundleLoader(second);
        for (int i = 0; i < 5; i++) {
            assertEquals(Optional.of("first"), firstBundles.find("message", Locale.ROOT));
            assertEquals(Optional.empty(), firstBundles.find("missing", Locale.ROOT));
            assertEquals(Optional.of("second"), secondBundles.find("message", Locale.ROOT));
        }
        assertEquals(1, first.reads);
        assertEquals(1, second.reads);
    }

    private static final class ResourceLoader extends ClassLoader {
        private final Map<String, String> resources;
        int reads;
        ResourceLoader(Map<String, String> resources) {
            this.resources = resources;
        }
        @Override public URL getResource(String name) { return null; }
        @Override public InputStream getResourceAsStream(String name) {
            reads++;
            String value = resources.get(name);
            return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
