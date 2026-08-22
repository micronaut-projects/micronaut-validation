/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.validation.reflection;

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.annotation.ReflectionAnnotationCustomizer;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;

import java.lang.annotation.Annotation;
import java.util.Map;

/**
 * The runtime counterpart of {@code ValidationAnnotationRemapper}: the validator classes of a constraint,
 * declared by its {@code @Constraint} meta-annotation, are copied into the values of the constraint as the
 * remapper copies them at compilation time, so that the validator resolution reads one member whichever way
 * the metadata was built.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class ConstraintAnnotationCustomizer implements ReflectionAnnotationCustomizer {

    @Override
    public boolean supports(Class<? extends Annotation> annotationType) {
        return annotationType.isAnnotationPresent(Constraint.class);
    }

    @Override
    public void customize(Annotation annotation, Map<CharSequence, Object> values) {
        Class<?>[] validatedBy = annotation.annotationType().getAnnotation(Constraint.class).validatedBy();
        if (validatedBy.length == 0) {
            return;
        }
        AnnotationClassValue<?>[] classValues = new AnnotationClassValue[validatedBy.length];
        for (int i = 0; i < validatedBy.length; i++) {
            classValues[i] = new AnnotationClassValue<>(validatedBy[i]);
        }
        values.put(ValidationAnnotationUtil.CONSTRAINT_VALIDATED_BY, classValues);
    }
}
