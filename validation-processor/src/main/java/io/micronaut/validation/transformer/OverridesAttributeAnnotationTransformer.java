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

import io.micronaut.context.annotation.AliasFor;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.inject.annotation.TypedAnnotationTransformer;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.validation.OverridesAttribute;

import java.util.List;

/**
 * Transforms {@link OverridesAttribute} on a member of a composed constraint into core's
 * {@link AliasFor}, so the overridden values of the composing constraints are computed at
 * build time as part of the annotation metadata.
 *
 * @author Denis Stepanov
 * @since 5.1
 */
public final class OverridesAttributeAnnotationTransformer implements TypedAnnotationTransformer<OverridesAttribute> {

    @Override
    public List<AnnotationValue<?>> transform(AnnotationValue<OverridesAttribute> annotation, VisitorContext visitorContext) {
        return List.of(toAliasFor(annotation));
    }

    @Override
    public Class<OverridesAttribute> annotationType() {
        return OverridesAttribute.class;
    }

    static AnnotationValue<AliasFor> toAliasFor(AnnotationValue<OverridesAttribute> annotation) {
        AnnotationValueBuilder<AliasFor> builder = AnnotationValue.builder(AliasFor.class);
        annotation.annotationClassValue("constraint").ifPresent(constraint -> builder.member("annotationName", constraint.getName()));
        // An empty name means the annotated member's own name, which core fills in
        annotation.stringValue("name").ifPresent(name -> builder.member("member", name));
        annotation.intValue("constraintIndex").ifPresent(index -> builder.member("index", index));
        // The overriding member value applies also when only its default is present
        builder.member("applyDefault", true);
        return builder.build();
    }
}
