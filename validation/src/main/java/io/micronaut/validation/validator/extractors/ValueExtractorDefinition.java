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
package io.micronaut.validation.validator.extractors;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;
import jakarta.validation.valueextraction.ValueExtractorDefinitionException;

/**
 * The value extractor definition.
 *
 * @param containerType The container type
 * @param valueType The value type
 * @param typeArgumentIndex The type argument
 * @param unwrapByDefault Is unwrapped by default
 * @param valueExtractor The value extractor
 * @param <T> The value type
 *
 * @author Denis Stepanov
 */
public record ValueExtractorDefinition<T>(@NonNull Class<T> containerType,
                                          @NonNull Class<Object> valueType,
                                          @Nullable Integer typeArgumentIndex,
                                          boolean unwrapByDefault,
                                          ValueExtractor<T> valueExtractor) {

    public ValueExtractorDefinition(@NotNull Argument<ValueExtractor<T>> argument,
                                    @NotNull ValueExtractor<T> valueExtractor) {
        this(argument, argument.getFirstTypeVariable().orElseThrow(), valueExtractor);
    }

    private ValueExtractorDefinition(@NotNull Argument<ValueExtractor<T>> argument,
                                     @NotNull Argument<?> containerArgument,
                                     @NotNull ValueExtractor<T> valueExtractor) {
        this(
            (Class<T>) containerArgument.getType(),
            (Class<Object>) findExtractedValue(containerArgument, valueExtractor).classValue("type").orElse(containerArgument.getType()),
            findExtractedTypeArgumentIndex(containerArgument),
            valueExtractor instanceof UnwrapByDefaultValueExtractor || argument.getAnnotationMetadata().hasAnnotation(UnwrapByDefault.class),
            valueExtractor
        );
    }

    @NonNull
    private static Integer findExtractedTypeArgumentIndex(@NotNull Argument<?> argument) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        Integer typeArgumentIndex = null;
        for (int i = 0; i < typeParameters.length; i++) {
            Argument<?> typeParameter = typeParameters[i];
            if (typeParameter.getAnnotationMetadata().hasAnnotation(ExtractedValue.class)) {
                if (typeArgumentIndex != null) {
                    throw new ValueExtractorDefinitionException("ValueExtractor definition cannot have multiple @ExtractedValue");
                }
                typeArgumentIndex = i;
            }
        }
        if (argument.getAnnotationMetadata().hasAnnotation(ExtractedValue.class)) {
            if (typeArgumentIndex != null) {
                throw new ValueExtractorDefinitionException("ValueExtractor definition cannot have multiple @ExtractedValue");
            }
            return null;
        }
        if (typeArgumentIndex != null) {
            return typeArgumentIndex;
        }
        if (typeParameters.length == 1) {
            // On missing @ExtractedValue select first type parameter by default
            return 0;
        }
        throw new ValueExtractorDefinitionException("ValueExtractor definition is missing @ExtractedValue on an argument: " + argument);
    }

    @Nullable
    private static AnnotationValue<?> findExtractedValue(@NotNull Argument<?> argument, ValueExtractor<?> valueExtractor) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        for (Argument<?> typeParameter : typeParameters) {
            AnnotationValue<ExtractedValue> annotationValue = typeParameter.getAnnotationMetadata().getAnnotation(ExtractedValue.class);
            if (annotationValue != null) {
                return annotationValue;
            }
        }
        AnnotationValue<ExtractedValue> annotationValue = argument.getAnnotationMetadata().getAnnotation(ExtractedValue.class);
        if (annotationValue == null) {
            if (typeParameters.length == 1) {
                // On missing @ExtractedValue select first type parameter by default
                return AnnotationValue.builder(ExtractedValue.class).build();
            }
            throw new ValueExtractorDefinitionException("ValueExtractor definition '" + valueExtractor + "' is missing @ExtractedValue!");
        }
        if (annotationValue.classValue("type").isEmpty()) {
            throw new ValueExtractorDefinitionException("ValueExtractor definition '" + valueExtractor + "' is missing @ExtractedValue type value!");
        }
        return annotationValue;
    }

}
