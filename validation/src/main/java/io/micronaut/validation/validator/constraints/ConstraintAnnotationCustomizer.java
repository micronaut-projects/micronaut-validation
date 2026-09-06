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
package io.micronaut.validation.validator.constraints;

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.reflection.ReflectionAnnotationCustomizer;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import jakarta.validation.Constraint;
import jakarta.validation.OverridesAttribute;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * The constraint contract is retainable: a composed constraint keeps the constraints it composes in its own
     * retained tree, so that reading them back needs no reflection on the annotation type. The remapper marks
     * it at compilation time; this is the same statement for the metadata built reflectively.
     */
    @Override
    public boolean isRetainable(Class<? extends Annotation> annotationType) {
        return annotationType == Constraint.class;
    }

    /**
     * The overrides {@code @OverridesAttribute} declares on a member, as the {@link AliasFor} the
     * {@code OverridesAttributeTransformer} produces for it at compilation time: the member of the composed
     * constraint overrides a member of the constraint it composes, for the occurrence {@code constraintIndex}
     * selects, and it applies even when the overriding member is left at its default.
     */
    @Override
    public List<AnnotationValue<AliasFor>> aliasesOf(Method member) {
        OverridesAttribute[] overrides = member.getAnnotationsByType(OverridesAttribute.class);
        if (overrides.length == 0) {
            return List.of();
        }
        List<AnnotationValue<AliasFor>> aliases = new ArrayList<>(overrides.length);
        for (OverridesAttribute override : overrides) {
            aliases.add(AnnotationValue.builder(AliasFor.class)
                .member("annotationName", override.constraint().getName())
                .member("member", override.name().isEmpty() ? member.getName() : override.name())
                .member("index", override.constraintIndex())
                .member("applyDefault", true)
                .build());
        }
        return aliases;
    }

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
