package io.micronaut.docs.validation;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;

@Property(name = "spec.name", value = "CustomerConstraintTest")
@MicronautTest(startApplication = false)
class CustomerConstraintTest {

    @Inject
    Validator validator;


    @Test
    void should_return_interpolated_message() {
        // Given
        var bean = new ExampleBean();
        bean.getList().add("11111");

        // When
        Set<ConstraintViolation<ExampleBean>> violations = validator.validate(bean);

        // Then
        assertThat("violations", violations, not(empty()));
        assertThat("violations[0].message", violations.iterator().next().getMessage(), containsString("[a-z]"));
        assertThat("violations[0].message", violations.iterator().next().getMessageTemplate(), containsString("regexp"));
    }

    @Introspected
    static class ExampleBean {
        @CollectionPattern(regexp = "[a-z]")
        private List<String> list = new ArrayList<>();

        public List<String> getList() {
            return list;
        }

        public void setList(List<String> list) {
            this.list = list;
        }
    }

    @Target({FIELD, PARAMETER, TYPE_USE})
    @Retention(RUNTIME)
    @Documented
    @Constraint(validatedBy = {CollectionPatternValidator.class})
    @interface CollectionPattern {

        String regexp();

        Pattern.Flag[] flags() default {};

        String message() default "{jakarta.validation.constraints.Pattern.message}";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Requires(property = "spec.name", value = "CustomerConstraintTest")
    @Singleton
    static class CollectionPatternValidator implements ConstraintValidator<CollectionPattern, Collection<String>> {

        @Override
        public boolean isValid(@Nullable Collection<String> values,
                               @NonNull AnnotationValue<CollectionPattern> annotationMetadata,
                               @NonNull ConstraintValidatorContext context) {

            if (values == null || values.isEmpty()) {
                return true;
            }

            final String regexp = annotationMetadata.getRequiredValue("regexp", String.class);
            final java.util.regex.Pattern regex = java.util.regex.Pattern.compile(regexp);

            context.disableDefaultConstraintViolation();

            boolean valid = true;
            for (String value : values) {
                if (value != null && !regex.matcher(value).matches()) {

                    context.buildConstraintViolationWithTemplate("must match  \"{regexp}\"")
                        .addPropertyNode("[" + value + "]")
                        .addConstraintViolation();

                    valid = false;
                }
            }
            return valid;
        }
    }
}
