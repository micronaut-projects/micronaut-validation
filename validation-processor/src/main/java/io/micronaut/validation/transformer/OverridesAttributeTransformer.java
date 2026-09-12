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
import io.micronaut.inject.annotation.NamedAnnotationTransformer;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.validation.OverridesAttribute;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * Maps {@link OverridesAttribute} onto {@link AliasFor}, which is the same declaration in the terms the
 * annotation processors apply: the member of the composed constraint overrides a member of the constraint it
 * composes, for the occurrence {@code constraintIndex} selects, and it applies even when the overriding member
 * is left at its default.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
public final class OverridesAttributeTransformer implements NamedAnnotationTransformer {

    @Override
    public String getName() {
        return OverridesAttribute.class.getName();
    }

    @Override
    public List<AnnotationValue<?>> transform(AnnotationValue<Annotation> annotation, VisitorContext visitorContext) {
        AnnotationValueBuilder<AliasFor> aliasFor = AnnotationValue.builder(AliasFor.class);
        annotation.annotationClassValue("constraint").ifPresent(constraint -> aliasFor.member("annotationName", constraint.getName()));
        annotation.stringValue("name").ifPresent(name -> aliasFor.member("member", name));
        annotation.intValue("constraintIndex").ifPresent(index -> aliasFor.member("index", index));
        // @OverridesAttribute overrides with the default of the overriding member when it is not set
        aliasFor.member("applyDefault", true);
        return List.of(aliasFor.build());
    }
}
