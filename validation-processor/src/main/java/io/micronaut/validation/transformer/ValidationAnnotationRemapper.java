/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.transformer;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.annotation.AnnotationRemapper;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;

import java.lang.annotation.Inherited;
import java.util.List;
import java.util.Optional;

/**
 * The validation annotations remapper.
 *
 * @author Denis Stepanov
 */
public class ValidationAnnotationRemapper implements AnnotationRemapper {

    @Override
    public String getPackageName() {
        return AnnotationRemapper.ALL_PACKAGES;
    }

    @Override
    public List<AnnotationValue<?>> remap(AnnotationValue<?> annotation, VisitorContext visitorContext) {
        if (annotation.getAnnotationName().equals(Valid.class.getName())) {
            return List.of(
                    annotation.mutate().stereotype(
                            AnnotationValue.builder(Inherited.class).build()
                    ).build());
        }
        List<AnnotationValue<?>> stereotypes = annotation.getStereotypes();
        if (stereotypes != null) {
            Optional<AnnotationValue<?>> optionalConstraint = stereotypes.stream().filter(stereotype -> stereotype.getAnnotationName().equals(Constraint.class.getName())).findFirst();
            if (optionalConstraint.isPresent()) {
                AnnotationValue<?> constraintAnnotationValue = optionalConstraint.get();
                AnnotationValueBuilder<?> builder = annotation.mutate();
                AnnotationClassValue<?>[] validatedBy = constraintAnnotationValue.annotationClassValues("validatedBy");
                if (validatedBy.length > 0) {
                    builder = builder.member(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY, validatedBy);
                }
                return List.of(
                        builder.stereotype(
                        AnnotationValue.builder(Inherited.class).build()
                ).build());
            }
        }
        return List.of(annotation);
    }
}
