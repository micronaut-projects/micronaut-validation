package io.micronaut.validation

import io.micronaut.context.annotation.Property
import io.micronaut.http.HttpRequest
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import spock.lang.Specification

@MicronautTest(rebuildContext = true)
class DisableValidationSpec extends Specification {
    @Inject
    @Client("/")
    HttpClient client;

    @Property(name = "micronaut.validator.enabled", value = "false")
    void "getBean with validation disabled"() {
        testIt();
    }

    @Property(name = "micronaut.validator.enabled", value = "true")
    void "getBean with validation enabled"() {
        testIt();
    }

    void testIt() {
        var bean = client.toBlocking().retrieve(HttpRequest.GET("?internalId=42"), Application.MyBean.class);

        assertThat(bean.internalId()).isEqualTo(42);
    }
}

