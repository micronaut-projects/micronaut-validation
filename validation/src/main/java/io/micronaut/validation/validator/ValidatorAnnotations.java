/*
 * Copyright 2017-2025 original authors
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

import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.memo.MemoizedFlag;
import io.micronaut.validation.annotation.ValidatedElement;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;

@Internal
final class ValidatorAnnotations {
    private static final MemoizedFlag<AnnotationMetadata> STEREOTYPE_VALID = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasStereotype(Valid.class));
    private static final MemoizedFlag<AnnotationMetadata> STEREOTYPE_CONSTRAINT = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasStereotype(Constraint.class));
    private static final MemoizedFlag<AnnotationMetadata> STEREOTYPE_CONFIGURATION_READER = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasStereotype(ConfigurationReader.class));
    private static final MemoizedFlag<AnnotationMetadata> ANNOTATION_PROPERTY = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasAnnotation(Property.class));
    private static final MemoizedFlag<AnnotationMetadata> ANNOTATION_VALID = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasAnnotation(Valid.class));
    private static final MemoizedFlag<AnnotationMetadata> ANNOTATION_VALIDATED_ELEMENT = AnnotationMetadata.MEMOIZER_NAMESPACE.newFlag(m -> m.hasAnnotation(ValidatedElement.class));

    private ValidatorAnnotations() {
    }

    static boolean hasStereotypeValid(AnnotationMetadata annotationMetadata) {
        return STEREOTYPE_VALID.get(annotationMetadata);
    }

    static boolean hasStereotypeConstraint(AnnotationMetadata annotationMetadata) {
        return STEREOTYPE_CONSTRAINT.get(annotationMetadata);
    }

    static boolean hasStereotypeConfigurationReader(AnnotationMetadata annotationMetadata) {
        return STEREOTYPE_CONFIGURATION_READER.get(annotationMetadata);
    }

    static boolean hasAnnotationProperty(AnnotationMetadata executableMethod) {
        return ANNOTATION_PROPERTY.get(executableMethod);
    }

    static boolean hasAnnotationValid(AnnotationMetadata annotationMetadata) {
        return ANNOTATION_VALID.get(annotationMetadata);
    }

    static boolean hasAnnotationValidatedElement(AnnotationMetadata annotationMetadata) {
        return ANNOTATION_VALIDATED_ELEMENT.get(annotationMetadata);
    }
}
