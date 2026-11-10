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

import io.micronaut.aop.Intercepted;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.exceptions.BeanInstantiationException;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.InjectionPoint;
import io.micronaut.inject.ProxyBeanDefinition;
import io.micronaut.validation.validator.constraints.ConstraintContainers;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Set;

/**
 * The validation of the beans a bean context creates and of the arguments it injects.
 *
 * @since 5.3.0
 */
@Internal
final class DefaultBeanDefinitionValidator {

    private final DefaultValidator validator;
    private final ReflectionSupport reflectionSupport;
    private final ValidationTraversal traversal;

    DefaultBeanDefinitionValidator(DefaultValidator validator, ReflectionSupport reflectionSupport, ValidationTraversal traversal) {
        this.validator = validator;
        this.reflectionSupport = reflectionSupport;
        this.traversal = traversal;
    }

    <T> void validateBeanArgument(BeanResolutionContext resolutionContext,
                                  InjectionPoint injectionPoint,
                                  Argument<T> argument,
                                  @Nullable T value) {
        final AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        final boolean hasValid = annotationMetadata.hasStereotype(Valid.class);
        final boolean hasConstraint = ConstraintContainers.hasConstraints(reflectionSupport, annotationMetadata, reflectionSupport.classLoader());

        if (!hasConstraint && !hasValid) {
            return;
        }

        DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, value, BeanValidationContext.DEFAULT);

        final Class<?> rootClass = injectionPoint.getDeclaringBean().getBeanType();
        try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(
                rootClass.getName(), rootClass, injectionPoint.getDeclaringBean().getConstructor().getArguments());
             ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addPropertyNode(argument.getName());
             DefaultConstraintValidatorContext.ValidationCloseable ignore4 = context.convertGroups(argument.getAnnotationMetadata())) {
            if (context.validateInGroups(context.findGroupSequences(), canCascade ->
                traversal.visitElement(context, null, argument, value, canCascade))) {
                failOnError(resolutionContext, context.getOverallViolations(), rootClass);
            }
        }

        failOnError(resolutionContext, context.getOverallViolations(), rootClass);
    }

    @SuppressWarnings("unchecked")
    <T> void validateBean(BeanResolutionContext resolutionContext, BeanDefinition<T> definition, T bean) {
        Class<T> beanType;
        if (definition instanceof ProxyBeanDefinition<?> proxyBeanDefinition) {
            beanType = (Class<T>) proxyBeanDefinition.getTargetType();
        } else {
            beanType = definition.getBeanType();
        }
        final BeanIntrospection<T> introspection = validator.getBeanIntrospection(bean, beanType);
        if (introspection != null) {
            Set<ConstraintViolation<T>> errors = validator.validate(introspection, bean);
            failOnError(resolutionContext, errors, beanType);
        } else if (bean instanceof Intercepted && definition.hasStereotype(ConfigurationReader.class)) {
            final Collection<ExecutableMethod<T, ?>> executableMethods = definition.getExecutableMethods();
            if (CollectionUtils.isEmpty(executableMethods)) {
                return;
            }
            final DefaultConstraintValidatorContext<T> context = new DefaultConstraintValidatorContext<>(validator, null, bean, BeanValidationContext.DEFAULT);
            // configuration interfaces are already named by generated executable declarations
            Class<?> declaringType = beanType;
            for (ExecutableMethod<T, ?> executableMethod : executableMethods) {
                if (executableMethod.getDeclaringType().isInterface()) {
                    declaringType = executableMethod.getDeclaringType();
                    break;
                }
            }
            try (ValidationPath.ContextualPath ignored = context.getCurrentPath().addConstructorNode(ValidationPath.simpleName(declaringType), beanType)) {
                for (ExecutableMethod<T, ?> executableMethod : executableMethods) {
                    if (executableMethod.hasAnnotation(Property.class)) {
                        validateConfigurationProperty(resolutionContext, context, bean, beanType, executableMethod);
                    }
                }
            }
            failOnError(resolutionContext, context.getOverallViolations(), beanType);
        } else {
            throw new BeanInstantiationException(resolutionContext, "Cannot validate bean [" + beanType.getName() + "]. No bean introspection present. Please add @Introspected.");
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void validateConfigurationProperty(BeanResolutionContext resolutionContext,
                                                   DefaultConstraintValidatorContext<T> context,
                                                   T bean,
                                                   Class<T> beanType,
                                                   ExecutableMethod<T, ?> executableMethod) {
        final boolean hasConstraint = ConstraintContainers.hasConstraints(reflectionSupport, executableMethod.getAnnotationMetadata(), reflectionSupport.classLoader());
        final boolean isValid = executableMethod.hasStereotype(Valid.class);
        if (!hasConstraint && !isValid) {
            return;
        }
        final Object value = executableMethod.invoke(bean);
        final ReturnType<Object> returnType = (ReturnType<Object>) executableMethod.getReturnType();
        try (ValidationPath.ContextualPath ignored1 = context.getCurrentPath().addPropertyNode(executableMethod.getName());
             DefaultConstraintValidatorContext.ValidationCloseable ignore2 = context.convertGroups(executableMethod.getAnnotationMetadata())) {
            if (context.validateInGroups(context.findGroupSequences(), canCascade ->
                traversal.visitElement(context, bean, returnType.asArgument(), value, canCascade))) {
                failOnError(resolutionContext, context.getOverallViolations(), beanType);
            }
        }
    }

    private static <T> void failOnError(BeanResolutionContext resolutionContext,
                                        Set<ConstraintViolation<T>> errors,
                                        Class<?> beanType) {
        if (!errors.isEmpty()) {
            StringBuilder builder = new StringBuilder()
                .append("Validation failed for bean definition [")
                .append(beanType.getName())
                .append("]\nList of constraint violations:[\n");
            for (ConstraintViolation<?> violation : errors) {
                builder.append('\t').append(violation.getPropertyPath()).append(" - ").append(violation.getMessage()).append('\n');
            }
            builder.append(']');
            throw new BeanInstantiationException(resolutionContext, builder.toString());
        }
    }
}
