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

import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidatorContext;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * A {@link ValidatorContext} that also takes a value extractor described in full.
 *
 * <p>The specification registers an extractor as an instance and says nothing else about it, so
 * {@link ValidatorContext#addValueExtractor(jakarta.validation.valueextraction.ValueExtractor)} has to read
 * the {@code ValueExtractor} signature the extractor's class declares, which needs
 * {@code micronaut-validation-reflection}. Describing the extractor instead needs nothing to be read.</p>
 *
 * <p>The context {@link jakarta.validation.ValidatorFactory#usingContext()} returns is one: cast it to reach this
 * method. The methods of {@link ValidatorContext} return this type, so a chain of calls keeps it.</p>
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
public interface MicronautValidatorContext extends ValidatorContext {

    @Override
    MicronautValidatorContext messageInterpolator(MessageInterpolator messageInterpolator);

    @Override
    MicronautValidatorContext traversableResolver(TraversableResolver traversableResolver);

    @Override
    MicronautValidatorContext constraintValidatorFactory(ConstraintValidatorFactory factory);

    @Override
    MicronautValidatorContext parameterNameProvider(ParameterNameProvider parameterNameProvider);

    @Override
    MicronautValidatorContext clockProvider(ClockProvider clockProvider);

    @Override
    MicronautValidatorContext addValueExtractor(ValueExtractor<?> extractor);

    /**
     * Registers a value extractor described in full: the container type it reads, the type of the value it
     * yields, which type argument of the container carries that value, and whether the value is unwrapped by
     * default.
     *
     * @param definition The extractor and what it extracts
     * @param <T>        The container type
     * @return This context
     */
    <T> MicronautValidatorContext addValueExtractor(ValueExtractorDefinition<T> definition);
}
