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
package io.micronaut.validation.transformer;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.annotation.TypedAnnotationTransformer;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.validation.OverridesAttribute;

import java.util.List;

/**
 * Transforms the {@link OverridesAttribute.List} container into core {@code AliasFor} values,
 * one per contained {@link OverridesAttribute}.
 *
 * @author Denis Stepanov
 * @since 5.1
 */
public final class OverridesAttributeListAnnotationTransformer implements TypedAnnotationTransformer<OverridesAttribute.List> {

    @Override
    public List<AnnotationValue<?>> transform(AnnotationValue<OverridesAttribute.List> annotation, VisitorContext visitorContext) {
        return annotation.<OverridesAttribute>getAnnotations(AnnotationMetadata.VALUE_MEMBER)
            .stream()
            .<AnnotationValue<?>>map(OverridesAttributeAnnotationTransformer::toAliasFor)
            .toList();
    }

    @Override
    public Class<OverridesAttribute.List> annotationType() {
        return OverridesAttribute.List.class;
    }
}
