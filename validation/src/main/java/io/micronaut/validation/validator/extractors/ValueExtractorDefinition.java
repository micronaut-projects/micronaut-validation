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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
        this(argument, valueExtractor, false);
    }

    /**
     * A definition read from the {@code ValueExtractor} signature an extractor declares.
     *
     * @param argument The {@code ValueExtractor} signature
     * @param valueExtractor The extractor
     * @param firstTypeArgumentByDefault Whether a container with a single type argument and no
     * {@link ExtractedValue} extracts that argument, as an extractor bean of the application did before 5.3.
     * The specification requires {@link ExtractedValue}, so an extractor registered through its API is not lenient
     */
    ValueExtractorDefinition(Argument<ValueExtractor<T>> argument, ValueExtractor<T> valueExtractor,
                             boolean firstTypeArgumentByDefault) {
        this(argument, argument.getFirstTypeVariable().orElseThrow(), valueExtractor, firstTypeArgumentByDefault);
    }

    private ValueExtractorDefinition(@NotNull Argument<ValueExtractor<T>> argument,
                                     @NotNull Argument<?> containerArgument,
                                     @NotNull ValueExtractor<T> valueExtractor,
                                     boolean firstTypeArgumentByDefault) {
        this(
            (Class<T>) containerArgument.getType(),
            (Class<Object>) findExtractedValue(containerArgument, valueExtractor, firstTypeArgumentByDefault).classValue("type").orElse(containerArgument.getType()),
            findExtractedTypeArgumentIndex(containerArgument, firstTypeArgumentByDefault),
            valueExtractor instanceof UnwrapByDefaultValueExtractor || argument.getAnnotationMetadata().hasAnnotation(UnwrapByDefault.class),
            valueExtractor
        );
    }

    /**
     * Describes an extractor in full, for {@link io.micronaut.validation.validator.MicronautValidatorContext}:
     * nothing is read from its class.
     *
     * @param containerType The container type the extractor reads, such as {@code Optional.class}
     * @param valueType The type of the value it yields
     * @param typeArgumentIndex Which type argument of the container carries the value, or {@code null} when the
     * extractor yields the container's own values, as one for {@code OptionalInt} does
     * @param unwrapByDefault Whether the value is unwrapped by default
     * @param valueExtractor The extractor
     * @param <T> The container type
     * @return The definition
     * @since 5.3.0
     */
    @SuppressWarnings("unchecked")
    public static <T> ValueExtractorDefinition<T> of(Class<T> containerType,
                                                     Class<?> valueType,
                                                     @Nullable Integer typeArgumentIndex,
                                                     boolean unwrapByDefault,
                                                     ValueExtractor<?> valueExtractor) {
        return new ValueExtractorDefinition<>(containerType, (Class<Object>) valueType, typeArgumentIndex,
            unwrapByDefault, (ValueExtractor<T>) valueExtractor);
    }

    @Nullable
    private static Integer findExtractedTypeArgumentIndex(@NotNull Argument<?> argument, boolean firstTypeArgumentByDefault) {
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
        if (firstTypeArgumentByDefault && typeParameters.length == 1) {
            return 0;
        }
        throw new ValueExtractorDefinitionException("ValueExtractor definition is missing @ExtractedValue on an argument: " + argument);
    }

    private static AnnotationValue<?> findExtractedValue(@NotNull Argument<?> argument, ValueExtractor<?> valueExtractor,
                                                     boolean firstTypeArgumentByDefault) {
        Argument<?>[] typeParameters = argument.getTypeParameters();
        for (Argument<?> typeParameter : typeParameters) {
            AnnotationValue<ExtractedValue> annotationValue = typeParameter.getAnnotationMetadata().getAnnotation(ExtractedValue.class);
            if (annotationValue != null) {
                return annotationValue;
            }
        }
        AnnotationValue<ExtractedValue> annotationValue = argument.getAnnotationMetadata().getAnnotation(ExtractedValue.class);
        if (annotationValue == null) {
            if (firstTypeArgumentByDefault && typeParameters.length == 1) {
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
