package io.micronaut.validation.validator.compat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.GroupSequence;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The default validator, without the reflection module, on what the review of the 5.3 branch found broken.
 */
class ReviewFindingsTest {
    static ApplicationContext ctx;
    static Validator validator;

    @BeforeAll
    static void start() {
        ctx = ApplicationContext.run();
        validator = ctx.getBean(Validator.class);
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    // group sequences

    public interface First { }

    public interface Second { }

    public interface Third { }

    public interface Other { }

    @GroupSequence({First.class, Second.class})
    public interface FirstThenSecond { }

    @GroupSequence({First.class, Third.class})
    public interface FirstThenThird { }

    @Introspected
    public record Sequenced(@NotNull(groups = First.class) String first,
                            @NotNull(groups = Second.class) String second,
                            @NotNull(groups = Third.class) String third,
                            @NotNull(groups = Other.class) String other) { }

    @Test
    void aSequenceAfterAnotherGroupStopsAtItsFirstFailingGroup() {
        var violations = validator.validate(new Sequenced(null, null, null, null), Other.class, FirstThenSecond.class);
        assertEquals(Set.of("other", "first"), paths(violations), violations.toString());
    }

    @Test
    void twoSequencesSharingAGroupAreNoCycle() {
        var violations = validator.validate(new Sequenced(null, "x", "x", "x"), FirstThenSecond.class, FirstThenThird.class);
        assertEquals(Set.of("first"), paths(violations), violations.toString());
    }

    // group inheritance of groups no introspection describes

    public interface BaseGroup { }

    public interface DerivedGroup extends BaseGroup { }

    @Introspected
    public record Grouped(@NotNull(groups = BaseGroup.class) String base,
                          @Size(max = 1, groups = DerivedGroup.class) String derived) { }

    @Test
    void aGroupIncludesTheGroupsItExtends() {
        var violations = validator.validate(new Grouped(null, "x"), DerivedGroup.class);
        assertEquals(Set.of("base"), paths(violations), violations.toString());
    }

    // a container of the JDK that passes its type variable through Collection to Iterable

    @Introspected
    public record WithQueue(Queue<@NotBlank String> names) { }

    @Test
    void aQueueOfTheJdkIsValidatedElementByElement() {
        Queue<String> names = new ArrayDeque<>(List.of("a", ""));
        var violations = validator.validate(new WithQueue(names));
        assertEquals(1, violations.size(), violations.toString());
    }

    // a repeatable constraint whose container is not named List

    @Constraint(validatedBy = {})
    @Repeatable(Checks.class)
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Check {
        String value();

        String message() default "check {value}";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Checks {
        Check[] value();
    }

    @Singleton
    public static class CheckValidator implements ConstraintValidator<Check, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<Check> annotationMetadata, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Introspected
    public record Checked(@NotNull @Check("one") @Check("two") String value) { }

    @Test
    void aCustomNamedContainerHoldsDeclaredConstraints() {
        var violations = validator.validate(new Checked("x"));
        assertEquals(Set.of("check one", "check two"),
            violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet()), violations.toString());
    }
}
