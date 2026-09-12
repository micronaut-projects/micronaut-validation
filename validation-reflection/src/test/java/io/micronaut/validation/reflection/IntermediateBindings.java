package io.micronaut.validation.reflection;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

/**
 * Type arguments bound one level above the class that is asked about, the shape core 13081 fixed for bean
 * definitions: a walk that matches a super type by its raw type, without carrying the bindings of the levels in
 * between, loses them. Next to each, the same shape bound directly, which the walks read right already.
 */
final class IntermediateBindings {

    private IntermediateBindings() {
    }

    /** A validator base leaving the validated type open. */
    abstract static class OpenIllegalValidator<T> implements ConstraintValidator<IllegalCrossParameter, T> {
        @Override
        public boolean isValid(T value, ConstraintValidatorContext context) {
            return true;
        }
    }

    /** Binds the validated type to String one level down: illegal for a cross-parameter validator. */
    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    static final class StringCrossParameterValidator extends OpenIllegalValidator<String> {
    }

    /** A cross-parameter constraint whose only validator validates String through an intermediate base. */
    @Constraint(validatedBy = StringCrossParameterValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
    @interface IllegalCrossParameter {
        String message() default "illegal";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** A validator base leaving the validated type open, for the legal constraint. */
    abstract static class OpenLegalValidator<T> implements ConstraintValidator<LegalCrossParameter, T> {
        @Override
        public boolean isValid(T value, ConstraintValidatorContext context) {
            return true;
        }
    }

    /** Binds the validated type to Object[] one level down: what a cross-parameter validator must validate. */
    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    static final class ObjectArrayCrossParameterValidator extends OpenLegalValidator<Object[]> {
    }

    /** A cross-parameter constraint whose only validator validates Object[] through an intermediate base. */
    @Constraint(validatedBy = ObjectArrayCrossParameterValidator.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
    @interface LegalCrossParameter {
        String message() default "legal";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** Swaps its two type arguments on the way to Map. */
    abstract static class Swapped<X, Y> extends AbstractMap<Y, X> {
    }

    /**
     * Passes its arguments straight to Swapped, so as a Map its value type, Map's argument 1, is Swapped's X, which
     * is this type's argument 0.
     */
    static final class Concrete<A, B> extends Swapped<A, B> {
        @Override
        public Set<Map.Entry<B, A>> entrySet() {
            return Set.of();
        }
    }

    /** Swaps its two type arguments directly: as a Map its value type is this type's argument 0. */
    static final class DirectlySwapped<A, B> extends AbstractMap<B, A> {
        @Override
        public Set<Map.Entry<B, A>> entrySet() {
            return Set.of();
        }
    }

    /** Binds the element type to a type rather than passing a variable on. */
    static final class Strings extends AbstractList<String> {
        @Override
        public String get(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
