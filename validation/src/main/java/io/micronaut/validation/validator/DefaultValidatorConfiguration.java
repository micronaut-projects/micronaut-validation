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
package io.micronaut.validation.validator;

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.context.MessageSource;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.ConversionServiceAware;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.Toggleable;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.DefaultConstraintValidators;
import io.micronaut.validation.validator.extractors.DefaultValueExtractors;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import io.micronaut.validation.validator.messages.DefaultValidationMessages;
import jakarta.inject.Inject;
import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorContext;
import jakarta.validation.valueextraction.ValueExtractor;

import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * The default configuration for the validator.
 *
 * @author graemerocher
 * @since 1.2
 */
@ConfigurationProperties(ValidatorConfiguration.PREFIX)
public class DefaultValidatorConfiguration implements ValidatorConfiguration, Toggleable, ValidatorContext, ConversionServiceAware {

    @Nullable
    private ConstraintValidatorRegistry constraintValidatorRegistry;

    @Nullable
    private ValueExtractorRegistry valueExtractorRegistry;

    @Nullable
    private ClockProvider clockProvider;

    @Nullable
    private TraversableResolver traversableResolver;

    @Nullable
    private MessageSource messageSource;

    @Nullable
    private ExecutionHandleLocator executionHandleLocator;

    private ConversionService conversionService = ConversionService.SHARED;

    private BeanIntrospector beanIntrospector = BeanIntrospector.SHARED;

    private boolean enabled = true;

    /**
     * Sets the conversion service.
     *
     * @param conversionService The conversion service
     */
    @Inject
    @Override
    public void setConversionService(ConversionService conversionService) {
        this.conversionService = conversionService;
    }

    @Override
    public ConversionService getConversionService() {
        return conversionService;
    }

    @Override
    @NonNull
    public ConstraintValidatorRegistry getConstraintValidatorRegistry() {
        if (constraintValidatorRegistry == null) {
            constraintValidatorRegistry = new DefaultConstraintValidators();
        }
        return constraintValidatorRegistry;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether Micronaut's validator is enabled.
     *
     * @param enabled True if it is
     * @return this configuration
     */
    public DefaultValidatorConfiguration setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * Sets the constraint validator registry to use.
     *
     * @param constraintValidatorRegistry The registry to use
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setConstraintValidatorRegistry(@Nullable ConstraintValidatorRegistry constraintValidatorRegistry) {
        this.constraintValidatorRegistry = constraintValidatorRegistry;
        return this;
    }

    @Override
    @NonNull
    public ValueExtractorRegistry getValueExtractorRegistry() {
        if (valueExtractorRegistry == null) {
            valueExtractorRegistry = new DefaultValueExtractors();
        }
        return valueExtractorRegistry;
    }

    /**
     * Sets the value extractor registry use.
     *
     * @param valueExtractorRegistry The registry
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setValueExtractorRegistry(@Nullable ValueExtractorRegistry valueExtractorRegistry) {
        this.valueExtractorRegistry = valueExtractorRegistry;
        return this;
    }

    @Override
    @NonNull
    public ClockProvider getClockProvider() {
        if (clockProvider == null) {
            clockProvider = new DefaultClockProvider();
        }
        return clockProvider;
    }

    /**
     * Sets the clock provider to use.
     *
     * @param clockProvider The clock provider
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setClockProvider(@Nullable ClockProvider clockProvider) {
        this.clockProvider = clockProvider;
        return this;
    }

    @Override
    @NonNull
    public TraversableResolver getTraversableResolver() {
        if (traversableResolver == null) {
            traversableResolver = new TraversableResolver() {
                @Override
                public boolean isReachable(Object object, Path.Node node, Class<?> rootType, Path path, ElementType elementType) {
                    return true;
                }

                @Override
                public boolean isCascadable(Object object, Path.Node node, Class<?> rootType, Path path, ElementType elementType) {
                    return true;
                }
            };
        }
        return traversableResolver;
    }

    /**
     * Sets the traversable resolver to use.
     *
     * @param traversableResolver The resolver
     * @return This configuration
     */
    @Inject
    public DefaultValidatorConfiguration setTraversableResolver(@Nullable TraversableResolver traversableResolver) {
        this.traversableResolver = traversableResolver;
        return this;
    }

    @Override
    @NonNull
    public MessageSource getMessageSource() {
        if (messageSource == null) {
            messageSource = new DefaultValidationMessages();
        }
        return messageSource;
    }

    /**
     * Sets the message source to use.
     *
     * @param messageSource The message source
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setMessageSource(@Nullable MessageSource messageSource) {
        this.messageSource = messageSource;
        return this;
    }

    @Override
    @NonNull
    public ExecutionHandleLocator getExecutionHandleLocator() {
        if (executionHandleLocator == null) {
            executionHandleLocator = ExecutionHandleLocator.EMPTY;
        }
        return executionHandleLocator;
    }

    /**
     * Sets the execution handler locator to use.
     *
     * @param executionHandleLocator The locator
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setExecutionHandleLocator(@Nullable ExecutionHandleLocator executionHandleLocator) {
        this.executionHandleLocator = executionHandleLocator;
        return this;
    }

    @Override
    public ValidatorContext messageInterpolator(MessageInterpolator messageInterpolator) {
        throw new UnsupportedOperationException("Method messageInterpolator(..) not supported");
    }

    @Override
    public ValidatorContext traversableResolver(TraversableResolver traversableResolver) {
        this.traversableResolver = traversableResolver;
        return this;
    }

    @Override
    public ValidatorContext constraintValidatorFactory(ConstraintValidatorFactory factory) {
        throw new UnsupportedOperationException("Method constraintValidatorFactory(..) not supported");
    }

    @Override
    public ValidatorContext parameterNameProvider(ParameterNameProvider parameterNameProvider) {
        throw new UnsupportedOperationException("Method parameterNameProvider(..) not supported");
    }

    @Override
    public ValidatorContext clockProvider(ClockProvider clockProvider) {
        this.clockProvider = clockProvider;
        return this;
    }

    @Override
    public ValidatorContext addValueExtractor(ValueExtractor<?> extractor) {
        List<AnnotatedType> annotatedTypes = new ArrayList<>();
        determineValueExtractorDefinitions(annotatedTypes, extractor.getClass());
        if (annotatedTypes.size() != 1) {
            throw new IllegalStateException("Expected to find one annotation type! Got: " + annotatedTypes);
        }
        Class<Object> clazz = (Class<Object>) Argument.of(annotatedTypes.get(0).getType()).getTypeParameters()[0].getType();
        ValueExtractor<Object> v = (ValueExtractor<Object>) extractor;
        getValueExtractorRegistry().addValueExtractor(clazz, v);
        return this;
    }

    @Override
    public Validator getValidator() {
        return new DefaultValidator(this);
    }

    @Override
    public BeanIntrospector getBeanIntrospector() {
        return beanIntrospector;
    }

    public final void setBeanIntrospector(BeanIntrospector beanIntrospector) {
        this.beanIntrospector = beanIntrospector;
    }

    private static void determineValueExtractorDefinitions(List<AnnotatedType> valueExtractorDefinitions, Class<?> extractorImplementationType) {
        if (!ValueExtractor.class.isAssignableFrom(extractorImplementationType)) {
            return;
        }

        Class<?> superClass = extractorImplementationType.getSuperclass();
        if (superClass != null && !Object.class.equals(superClass)) {
            determineValueExtractorDefinitions(valueExtractorDefinitions, superClass);
        }
        for (Class<?> implementedInterface : extractorImplementationType.getInterfaces()) {
            if (!ValueExtractor.class.equals(implementedInterface)) {
                determineValueExtractorDefinitions(valueExtractorDefinitions, implementedInterface);
            }
        }
        for (AnnotatedType annotatedInterface : extractorImplementationType.getAnnotatedInterfaces()) {
            if (ValueExtractor.class.equals(getClassFromType(annotatedInterface.getType()))) {
                valueExtractorDefinitions.add(annotatedInterface);
            }
        }
    }

    public static Class<?> getClassFromType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return getClassFromType(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        throw new IllegalArgumentException();
    }
}
