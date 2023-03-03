/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.validation.visitor;


import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;

import java.util.Set;
import java.util.stream.Stream;

/**
 * The visitor add property indexes for the validated annotations.
 *
 * @author Denis Stepanov
 * @since 4.0.0
 */
@Internal
public class IntrospectedValidationIndexesVisitor implements TypeElementVisitor<Object, Object> {

    private static final String ANN_CONSTRAINT = "javax.validation.Constraint";
    private static final String ANN_VALID = "javax.validation.Valid";

    private static final AnnotationValue<Introspected.IndexedAnnotation> INTROSPECTION_INDEXED_CONSTRAINT = AnnotationValue.builder(Introspected.IndexedAnnotation.class)
        .member("annotation", new AnnotationClassValue<>(ANN_CONSTRAINT))
        .build();
    private static final AnnotationValue<Introspected.IndexedAnnotation> INTROSPECTION_INDEXED_VALID = AnnotationValue.builder(Introspected.IndexedAnnotation.class)
        .member("annotation", new AnnotationClassValue<>(ANN_VALID))
        .build();

    private ClassElement classElement;

    @Override
    public int getOrder() {
        return IntrospectedTypeElementVisitor.POSITION + 10; // Should just before the introspected visitor
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        classElement = element;
        if (classElement.hasStereotype(Introspected.class)) {
            AnnotationMetadata annotationMetadata = classElement.getAnnotationMetadata();
            AnnotationValue<Introspected> introspectedAnnotation = annotationMetadata.getAnnotation(Introspected.class);
            classElement.annotate(Introspected.class, builder -> {
                AnnotationValue<?>[] indexed = Stream.concat(
                    introspectedAnnotation.getAnnotations("indexed", Introspected.IndexedAnnotation.class).stream(),
                    Stream.of(INTROSPECTION_INDEXED_CONSTRAINT, INTROSPECTION_INDEXED_VALID)
                ).toArray(AnnotationValue<?>[]::new);
                builder.member("indexed", indexed);
            });
        }
    }
}
