package io.micronaut.docs.validation.disable;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.runtime.Micronaut;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class Application {

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }

    @Property(name = "spec.name", value = "ValidationDisableTest")
    @Controller
    static class MyController {
        @Get
        MyBean bean(@ValidInternalId int internalId) {
            return new MyBean(internalId);
        }
    }

    @Introspected
    public record MyBean(@ValidInternalId int internalId) {
    }

    @Documented
    @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.CONSTRUCTOR, ElementType.TYPE_USE})
    @Retention(RetentionPolicy.RUNTIME)
    @NotNull
    @Positive
    @Max(Integer.MAX_VALUE)
    public @interface ValidInternalId {
    }

}
