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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.type.Argument;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.ValidationException;
import jakarta.validation.Payload;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The descriptor of the violation reported when a cascaded value has no bean introspection: there is no
 * constraint behind it, the message is the one of {@code Introspected}.
 *
 * @param <E> The element type
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class NotIntrospectedConstraintDescriptor<E> implements ConstraintDescriptor<Annotation> {

    private final Argument<E> notIntrospectedArgument;
    private final E elementValue;

    NotIntrospectedConstraintDescriptor(Argument<E> notIntrospectedArgument, E elementValue) {
        this.notIntrospectedArgument = notIntrospectedArgument;
        this.elementValue = elementValue;
    }

    @Override
    public Annotation getAnnotation() {
        // the violation reports a type that is not introspected: the annotation it stands for
        return AnnotationMetadataSupport.buildAnnotation(Introspected.class, new AnnotationValue<>(Introspected.class.getName()));
    }

    @Override
    public String getMessageTemplate() {
        return "{" + Introspected.class.getName() + ".message}";
    }

    @Override
    public Set<Class<?>> getGroups() {
        return Set.of();
    }

    @Override
    public Set<Class<? extends Payload>> getPayload() {
        return Set.of();
    }

    @Override
    public ConstraintTarget getValidationAppliesTo() {
        return ConstraintTarget.IMPLICIT;
    }

    @Override
    public List<Class<? extends jakarta.validation.ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
        return List.of();
    }

    @Override
    public Map<String, Object> getAttributes() {
        var argType = notIntrospectedArgument.getType().getName();
        if (notIntrospectedArgument.isTypeVariable()) {
            argType = elementValue.getClass().getName();
        }
        return Collections.singletonMap("type", argType);
    }

    @Override
    public Set<ConstraintDescriptor<?>> getComposingConstraints() {
        return Set.of();
    }

    @Override
    public boolean isReportAsSingleViolation() {
        return false;
    }

    @Override
    public ValidateUnwrappedValue getValueUnwrapping() {
        return ValidateUnwrappedValue.DEFAULT;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        throw new ValidationException("Not supported");
    }
}
