package io.micronaut.validation;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.Validator;
import jakarta.inject.Singleton;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Fork(2)
@Warmup(iterations = 4, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ValidationCostBenchmark {
    private ApplicationContext ctx;
    private Validator validator;
    private Service service;
    private Person valid;
    private Person invalid;
    private Plain plain;

    @Setup
    public void init() {
        ctx = ApplicationContext.run(Map.of("spec.name", "ValidationCostBenchmark"));
        validator = ctx.getBean(Validator.class);
        service = ctx.getBean(Service.class);
        valid = new Person("Ada", 36, "ada@example.com", new Address("Main St"), List.of("a", "b", "c"));
        invalid = new Person("", 36, "not-an-email", new Address("Main St"), List.of("a", "b", "c"));
        plain = new Plain("x", 1);
        if (!validator.validate(valid).isEmpty() || validator.validate(invalid).size() != 2) {
            throw new IllegalStateException("unexpected: " + validator.validate(valid) + " / " + validator.validate(invalid));
        }
    }

    @TearDown
    public void destroy() {
        ctx.close();
    }

    @Benchmark
    public Object validBean() {
        return validator.validate(valid);
    }

    @Benchmark
    public Object invalidBean() {
        return validator.validate(invalid);
    }

    @Benchmark
    public Object unconstrainedBean() {
        return validator.validate(plain);
    }

    @Benchmark
    public Object interceptedMethod() {
        return service.call("foo", valid);
    }

    @Introspected
    public record Address(@NotBlank String street) { }

    @Introspected
    public record Person(@NotBlank String name,
                         @Min(18) int age,
                         @Email String email,
                         @Valid @NotNull Address address,
                         @Size(max = 5) List<@NotBlank String> tags) { }

    @Introspected
    public record Plain(String a, int b) { }

    @Singleton
    @Requires(property = "spec.name", value = "ValidationCostBenchmark")
    public static class Service {
        public String call(@NotBlank String s, @Valid Person p) {
            return s;
        }
    }
}
