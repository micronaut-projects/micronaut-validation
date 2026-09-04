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
package io.micronaut.validation.validator.metadata;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.type.Argument;
import io.micronaut.core.order.Ordered;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.metadata.BeanDescriptor;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Optional;

/**
 * Service provider interface for optional Jakarta Validation bean metadata.
 * <p>
 * Implementations are ordered and may contribute descriptors, synthetic
 * annotation metadata, annotation-ignore decisions, or replacement validator
 * class lists. This SPI is primarily used by optional compliance modules such
 * as XML and reflection support, but it is part of the public configuration
 * contract because {@link io.micronaut.validation.validator.ValidatorConfiguration}
 * exposes configured providers.
 *
 * @since 5.1
 */
public interface ValidationMetadataProvider extends Ordered {

    /**
     * @param beanType The bean type
     * @return A bean descriptor if this provider has metadata for the type
     */
    Optional<BeanDescriptor> getConstraintsForClass(Class<?> beanType);

    /**
     * @param beanType The bean type
     * @return Additional class-level annotation metadata for validation
     * @since 5.1
     */
    default AnnotationMetadata getBeanAnnotationMetadata(Class<?> beanType) {
        return AnnotationMetadata.EMPTY_METADATA;
    }

    /**
     * @param beanType The bean type
     * @return Whether regular class annotations should be ignored
     * @since 5.1
     */
    default boolean isBeanAnnotationMetadataIgnored(Class<?> beanType) {
        return false;
    }

    /**
     * @param beanType The bean type
     * @param propertyName The property name
     * @return Additional property-level annotation metadata for validation
     * @since 5.1
     */
    default AnnotationMetadata getPropertyAnnotationMetadata(Class<?> beanType, String propertyName) {
        return AnnotationMetadata.EMPTY_METADATA;
    }

    /**
     * @param beanType The bean type
     * @param propertyName The property name
     * @return Whether regular property annotations should be ignored
     * @since 5.1
     */
    default boolean isPropertyAnnotationMetadataIgnored(Class<?> beanType, String propertyName) {
        return false;
    }

    /**
     * The argument of a property with the configured container element constraints: the type arguments of the
     * argument carry the annotations declared on them, the configuration merges into them, or replaces them
     * when the annotations are ignored. The annotations of the property itself are served by
     * {@link #getPropertyAnnotationMetadata(Class, String)}.
     *
     * @param beanType     The bean type
     * @param propertyName The property name
     * @param argument     The argument as declared
     * @return The argument as configured, the given one when nothing is configured
     */
    default Argument<?> getPropertyArgument(Class<?> beanType, String propertyName, Argument<?> argument) {
        return argument;
    }

    /**
     * The parameters of a method with the configured constraints, cascades, group conversions and container
     * element constraints merged into the declared ones, or replacing them when the annotations are ignored.
     *
     * @param beanType   The bean type
     * @param methodName The method name
     * @param arguments  The parameters as declared
     * @return The parameters as configured, the given ones when nothing is configured
     */
    default Argument<?>[] getMethodParameterArguments(Class<?> beanType, String methodName, Argument<?>[] arguments) {
        return arguments;
    }

    /**
     * The annotations of a method — its cross-parameter and return value constraints — with the configured ones.
     *
     * @param beanType           The bean type
     * @param methodName         The method name
     * @param parameterTypes     The parameter types
     * @param annotationMetadata The annotations as declared
     * @return The annotations as configured, the given ones when nothing is configured
     */
    default AnnotationMetadata getMethodAnnotationMetadata(Class<?> beanType, String methodName, Class<?>[] parameterTypes, AnnotationMetadata annotationMetadata) {
        return annotationMetadata;
    }

    /**
     * The return value of a method with the configured constraints and container element constraints.
     *
     * @param beanType       The bean type
     * @param methodName     The method name
     * @param parameterTypes The parameter types
     * @param argument       The return value as declared
     * @return The return value as configured, the given one when nothing is configured
     */
    default Argument<?> getMethodReturnArgument(Class<?> beanType, String methodName, Class<?>[] parameterTypes, Argument<?> argument) {
        return argument;
    }

    /**
     * The parameters of a constructor with the configured constraints.
     *
     * @param beanType  The bean type
     * @param arguments The parameters as declared
     * @return The parameters as configured, the given ones when nothing is configured
     */
    default Argument<?>[] getConstructorParameterArguments(Class<?> beanType, Argument<?>[] arguments) {
        return arguments;
    }

    /**
     * The annotations of a constructor — its cross-parameter and return value constraints — with the configured ones.
     *
     * @param beanType           The bean type
     * @param parameterTypes     The parameter types
     * @param annotationMetadata The annotations as declared
     * @return The annotations as configured, the given ones when nothing is configured
     */
    default AnnotationMetadata getConstructorAnnotationMetadata(Class<?> beanType, Class<?>[] parameterTypes, AnnotationMetadata annotationMetadata) {
        return annotationMetadata;
    }

    /**
     * The return value of a constructor with the configured constraints and container element constraints.
     *
     * @param beanType       The bean type
     * @param parameterTypes The parameter types
     * @param argument       The return value as declared
     * @return The return value as configured, the given one when nothing is configured
     */
    default Argument<?> getConstructorReturnArgument(Class<?> beanType, Class<?>[] parameterTypes, Argument<?> argument) {
        return argument;
    }

    /**
     * @param beanType The bean type
     * @param methodName The method name
     * @param parameterTypes The method parameter types
     * @param parameterIndex The parameter index
     * @return Whether regular method parameter annotations should be ignored
     * @since 5.1
     */
    default boolean isMethodParameterAnnotationMetadataIgnored(Class<?> beanType,
                                                              String methodName,
                                                              Class<?>[] parameterTypes,
                                                              int parameterIndex) {
        return false;
    }

    /**
     * @param beanType The bean type
     * @param methodName The method name
     * @param parameterTypes The method parameter types
     * @return Whether regular method return value annotations should be ignored
     * @since 5.1
     */
    default boolean isMethodReturnValueAnnotationMetadataIgnored(Class<?> beanType,
                                                                String methodName,
                                                                Class<?>[] parameterTypes) {
        return false;
    }

    /**
     * @param constraintType The constraint annotation type
     * @param existingValidatorClasses The existing validator classes declared by the constraint annotation
     * @param <A> The constraint annotation type
     * @return The replacement validator classes, or empty if this provider does not override them
     * @since 5.1
     */
    default <A extends Annotation> Optional<List<Class<? extends ConstraintValidator<A, ?>>>> getConstraintValidatorClasses(
        Class<A> constraintType,
        List<Class<? extends ConstraintValidator<A, ?>>> existingValidatorClasses) {
        return Optional.empty();
    }
}
