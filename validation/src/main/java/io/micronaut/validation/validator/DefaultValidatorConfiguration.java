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

import io.micronaut.context.BeanContext;
import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.convert.ConversionServiceAware;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.Toggleable;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.DefaultConstraintValidators;
import io.micronaut.validation.validator.constraints.DefaultInternalConstraintValidatorFactory;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.DefaultValueExtractors;
import io.micronaut.validation.validator.extractors.ValueExtractorDefinition;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import io.micronaut.validation.validator.messages.DefaultMessageInterpolator;
import io.micronaut.validation.validator.messages.DefaultMessages;
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

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The default configuration for the validator.
 *
 * @author graemerocher
 * @since 1.2
 */
@ConfigurationProperties(ValidatorConfiguration.PREFIX)
public class DefaultValidatorConfiguration implements ValidatorConfiguration, Toggleable, ValidatorContext, ConversionServiceAware {

    @Nullable
    private InternalConstraintValidatorFactory constraintValidatorFactory;

    @Nullable
    private ConstraintValidatorRegistry constraintValidatorRegistry;

    @Nullable
    private ValueExtractorRegistry valueExtractorRegistry;

    @Nullable
    private ClockProvider clockProvider;

    @Nullable
    private ClockProvider defaultClockProvider;

    @Nullable
    private TraversableResolver traversableResolver;

    @Nullable
    private TraversableResolver defaultTraversableResolver;

    @Nullable
    private MessageInterpolator defaultMessageInterpolator;

    @Nullable
    private MessageInterpolator messageInterpolator;

    @Nullable
    private ExecutionHandleLocator executionHandleLocator;

    private ConversionService conversionService = ConversionService.SHARED;

    private BeanIntrospector beanIntrospector = BeanIntrospector.SHARED;

    private boolean enabled = true;
    private boolean prependPropertyPath = true;

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
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        if (constraintValidatorFactory == null) {
            constraintValidatorFactory = new DefaultInternalConstraintValidatorFactory(beanIntrospector, null);
        }
        return constraintValidatorFactory;
    }

    /**
     * Inject internal {@link InternalConstraintValidatorFactory}.
     * @param constraintValidatorFactory the factory
     */
    @Internal
    @Inject
    void setInternalConstraintValidatorFactory(InternalConstraintValidatorFactory constraintValidatorFactory) {
        this.constraintValidatorFactory = constraintValidatorFactory;
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

    @Override
    public boolean isPrependPropertyPath() {
        return prependPropertyPath;
    }

    /**
     * If true, then the path to the property will be automatically added to the error message.
     * <p>
     * Default: true
     *
     * @param prependPropertyPath If true, then the path to the property will be automatically added to the error message.
     * @return this configuration
     */
    public DefaultValidatorConfiguration setPrependPropertyPath(boolean prependPropertyPath) {
        this.prependPropertyPath = prependPropertyPath;
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
            return getDefaultClockProvider();
        }
        return clockProvider;
    }

    @Override
    public ClockProvider getDefaultClockProvider() {
        if (defaultClockProvider == null) {
            defaultClockProvider = new DefaultClockProvider();
        }
        return defaultClockProvider;
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
    public TraversableResolver getDefaultTraversableResolver() {
        if (defaultTraversableResolver == null) {
            defaultTraversableResolver = new TraversableResolver() {
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
        return defaultTraversableResolver;
    }

    @Override
    @NonNull
    public TraversableResolver getTraversableResolver() {
        if (traversableResolver == null) {
            return getDefaultTraversableResolver();
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

    @NonNull
    @Override
    public MessageInterpolator getMessageInterpolator() {
        if (messageInterpolator == null) {
            return getDefaultMessageInterpolator();
        }
        return messageInterpolator;
    }

    @NonNull
    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        if (defaultMessageInterpolator == null) {
            defaultMessageInterpolator = new DefaultMessageInterpolator(new DefaultMessages());
        }
        return defaultMessageInterpolator;
    }

    /**
     * Sets the message interpolator to use.
     *
     * @param messageInterpolator The message interpolator
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setMessageInterpolator(@Nullable MessageInterpolator messageInterpolator) {
        this.messageInterpolator = messageInterpolator;
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
     * @param beanContext The beanContext
     * @return this configuration
     */
    @Inject
    public DefaultValidatorConfiguration setExecutionHandleLocator(@Nullable BeanContext beanContext) {
        this.executionHandleLocator = new ExecutionHandleLocator() {

            @Override
            public <T, R> Optional<ExecutableMethod<T, R>> findExecutableMethod(Class<T> beanType, String method, Class<?>... arguments) {
                if (beanType == null) {
                    return Optional.empty();
                }
                Collection<BeanDefinition<T>> definitions = beanContext.getBeanDefinitions(beanType);
                if (definitions.isEmpty()) {
                    return Optional.empty();
                }
                Optional<BeanDefinition<T>> optionalBeanDefinition = definitions.stream().filter(bd -> bd.getBeanType().equals(beanType)).findFirst();
                if (optionalBeanDefinition.isPresent()) {
                    Optional<ExecutableMethod<T, R>> foundMethod = optionalBeanDefinition.get().findMethod(method, arguments);
                    if (foundMethod.isPresent()) {
                        return foundMethod;
                    }
                }
                BeanDefinition<T> beanDefinition = definitions.iterator().next();
                Optional<ExecutableMethod<T, R>> foundMethod = beanDefinition.findMethod(method, arguments);
                if (foundMethod.isPresent()) {
                    return foundMethod;
                }
                return beanDefinition.<R>findPossibleMethods(method).findFirst();
            }
        };
        return this;
    }

    @Override
    public ValidatorContext messageInterpolator(MessageInterpolator messageInterpolator) {
        this.messageInterpolator = messageInterpolator;
        return this;
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
        Class<? extends ValueExtractor> extractorClass = extractor.getClass();
        determineValueExtractorDefinitions(annotatedTypes, extractorClass);
        if (annotatedTypes.size() != 1) {
            throw new IllegalStateException("Expected to find one annotation type! Got: " + annotatedTypes);
        }
        ValueExtractorRegistry valueExtractorRegistry1 = getValueExtractorRegistry();
        Argument<ValueExtractor<Object>> argument = (Argument<ValueExtractor<Object>>) argumentOf(annotatedTypes.get(0));
        if (extractorClass.getAnnotations().length > 0) {
            argument = Argument.of(
                argument.getType(),
                new AnnotationMetadataHierarchy(argument.getAnnotationMetadata(), annotationMetadataOf(extractorClass)),
                argument.getTypeParameters());
        }
        valueExtractorRegistry1.addValueExtractor(new ValueExtractorDefinition<>(
            argument,
            (ValueExtractor<Object>) extractor
        ));
        return this;
    }

    @NonNull
    private static Argument<?> argumentOf(@NonNull AnnotatedType type) {
        if (type instanceof AnnotatedParameterizedType annotatedParameterizedType) {
            return Argument.of(
                getClassFromType(type.getType()),
                annotationMetadataOf(type),
                Arrays.stream(annotatedParameterizedType.getAnnotatedActualTypeArguments()).map(DefaultValidatorConfiguration::argumentOf).toArray(Argument[]::new)
            );
        }
        return Argument.of(getClassFromType(type.getType()), annotationMetadataOf(type));
    }

    private static AnnotationMetadata annotationMetadataOf(AnnotatedElement annotatedElement) {
        Annotation[] annotations = annotatedElement.getAnnotations();
        if (annotations.length == 0) {
            return AnnotationMetadata.EMPTY_METADATA;
        }
        MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
        for (Annotation annotation : annotations) {
            Map<CharSequence, Object> values = new LinkedHashMap<>();
            Class<? extends Annotation> annotationType = annotation.annotationType();
            Method[] methods = annotationType.getMethods();
            for (Method method : methods) {
                if (!method.getDeclaringClass().equals(annotationType)) {
                    continue;
                }
                try {
                    Object value = method.invoke(annotation);
                    if (value != null) {
                        values.put(method.getName(), value);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            mutableAnnotationMetadata.addAnnotation(annotationType.getName(), values);
        }
        return mutableAnnotationMetadata;
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
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return getClassFromType(parameterizedType.getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        if (type instanceof WildcardType wildcardType) {
            return getClassFromType(wildcardType.getUpperBounds()[0]);
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}
