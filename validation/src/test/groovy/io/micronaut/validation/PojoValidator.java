package io.micronaut.validation;

import java.util.Objects;
import jakarta.inject.Singleton;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;

@Singleton
@Introspected
public class PojoValidator implements ConstraintValidator<ValidPojo, Pojo> {

    @Override
    public boolean isValid(Pojo pojo, AnnotationValue<ValidPojo> annotationMetadata,
                           ConstraintValidatorContext context) {
        if (Objects.equals(pojo.getEmail(), pojo.getName())) {
            context.messageTemplate("Email and Name can not be identical");
            return false;
        }

        return true;
    }
}
