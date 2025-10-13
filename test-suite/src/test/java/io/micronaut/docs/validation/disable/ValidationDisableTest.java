package io.micronaut.docs.validation.disable;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Property(name = "spec.name", value = "ValidationDisableTest")
@MicronautTest(rebuildContext = true)
public class ValidationDisableTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Test
    @Property(name = "micronaut.validator.enabled", value = "false")
    void getBean_withValidationDisabled() {
        testIt();
    }

    @Test
    @Property(name = "micronaut.validator.enabled", value = "true")
    void getBean_withValidationEnabled() {
        testIt();
    }

    void testIt() {
        var bean = client.toBlocking().retrieve(HttpRequest.GET("?internalId=42"), Application.MyBean.class);

        assertEquals(42, bean.internalId());
    }
}
