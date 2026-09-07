package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.validation.annotation.ConstraintValidatorTypes

/**
 * The processor records the constraint and the validated type of an introspected validator in its introspection.
 */
class ConstraintValidatorTypesVisitorSpec extends AbstractTypeElementSpec {

    void "the types an introspected validator binds are recorded"() {
        given:
        def introspection = buildBeanIntrospection('test.LengthValidator', '''
package test;

import io.micronaut.core.annotation.*;
import io.micronaut.validation.validator.constraints.*;
import jakarta.validation.constraints.Size;

@Introspected
class LengthValidator implements ConstraintValidator<Size, CharSequence> {
    @Override
    public boolean isValid(CharSequence value, AnnotationValue<Size> annotationMetadata, ConstraintValidatorContext context) {
        return value == null || value.length() >= 3;
    }
}
''')

        expect:
        introspection.getAnnotationMetadata().getAnnotationNames().contains(ConstraintValidatorTypes.name)
        introspection.getAnnotationMetadata().stringValue(ConstraintValidatorTypes, "constraint").get() == "jakarta.validation.constraints.Size"
        introspection.getAnnotationMetadata().stringValue(ConstraintValidatorTypes, "target").get() == "java.lang.CharSequence"
    }

    void "a validator of a type variable records nothing"() {
        given:
        def introspection = buildBeanIntrospection('test.AnyValidator', '''
package test;

import io.micronaut.core.annotation.*;
import io.micronaut.validation.validator.constraints.*;
import jakarta.validation.constraints.NotNull;

@Introspected
class AnyValidator<T> implements ConstraintValidator<NotNull, T> {
    @Override
    public boolean isValid(T value, AnnotationValue<NotNull> annotationMetadata, ConstraintValidatorContext context) {
        return value != null;
    }
}
''')

        expect:
        !introspection.getAnnotationMetadata().hasAnnotation(ConstraintValidatorTypes)
    }
}
