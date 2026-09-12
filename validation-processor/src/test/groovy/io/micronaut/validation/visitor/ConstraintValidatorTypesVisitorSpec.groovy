package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import jakarta.validation.ConstraintValidator

/**
 * The processor introspects a constraint validator, so the constraint and the type it validates are read
 * from the type arguments its introspection records rather than from the class.
 */
class ConstraintValidatorTypesVisitorSpec extends AbstractTypeElementSpec {

    void "a validator is introspected and the types it binds are recorded"() {
        given:
        def introspection = buildBeanIntrospection('test.LengthValidator', '''
package test;

import io.micronaut.core.annotation.*;
import io.micronaut.validation.validator.constraints.*;
import jakarta.validation.constraints.Size;

class LengthValidator implements ConstraintValidator<Size, CharSequence> {
    @Override
    public boolean isValid(CharSequence value, AnnotationValue<Size> annotationMetadata, ConstraintValidatorContext context) {
        return value == null || value.length() >= 3;
    }
}
''')

        when:
        def typeArguments = introspection.getTypeArguments(ConstraintValidator)

        then: "the validator is introspected without asking for it"
        introspection != null

        and: "and records the constraint it validates and the type it validates"
        typeArguments.size() == 2
        typeArguments[0].type == jakarta.validation.constraints.Size
        typeArguments[1].type == CharSequence
    }

    void "a validator leaving its type open records the variable"() {
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

        expect: "the constraint resolves and the open variable erases to Object, so the validator validates anything"
        introspection.getTypeArguments(ConstraintValidator)*.type == [jakarta.validation.constraints.NotNull, Object]
    }
}
