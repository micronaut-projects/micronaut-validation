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
package io.micronaut.validation.tck;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableType;
import jakarta.validation.executable.ValidateOnExecution;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code jakarta.enterprise.inject.Instance} the TCK injects into its CDI tests: the deployment bean
 * context provides the beans, and a constructor is validated the way a CDI container would validate it.
 *
 * @param applicationContext The bean context of the deployment
 * @param type               The requested bean type
 * @param injectedValues     The values already injected into the test instance, by type
 * @param <T>                The requested bean type
 */
@Internal
record CdiInstance<T>(
    ApplicationContext applicationContext,
    Class<?> type,
    Map<Class<?>, Object> injectedValues
) implements Instance<T> {

    @Override
    public Instance<T> select(Annotation... qualifiers) {
        unsupportedQualifiers(qualifiers);
        return this;
    }

    @Override
    public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
        unsupportedQualifiers(qualifiers);
        return new CdiInstance<>(applicationContext, subtype, injectedValues);
    }

    @Override
    public <U extends T> Instance<U> select(jakarta.enterprise.util.TypeLiteral<U> subtype,
                                                                      Annotation... qualifiers) {
        unsupportedQualifiers(qualifiers);
        Type literalType = subtype.getType();
        if (literalType instanceof Class<?> clazz) {
            return new CdiInstance<>(applicationContext, clazz, injectedValues);
        }
        if (literalType instanceof ParameterizedType parameterizedType
            && parameterizedType.getRawType() instanceof Class<?> rawType) {
            return new CdiInstance<>(applicationContext, rawType, injectedValues);
        }
        return new CdiInstance<>(applicationContext, Object.class, injectedValues);
    }

    @Override
    public boolean isUnsatisfied() {
        return applicationContext.findBean(type).isEmpty();
    }

    @Override
    public boolean isAmbiguous() {
        return applicationContext.getBeansOfType(type).size() > 1;
    }

    @Override
    public void destroy(T instance) {
        applicationContext.destroyBean(instance);
    }

    @Override
    public Handle<T> getHandle() {
        T instance = get();
        return new Handle<>() {
            @Override
            public T get() {
                return instance;
            }

            @Override
            public Bean<T> getBean() {
                return null;
            }

            @Override
            public void destroy() {
                CdiInstance.this.destroy(instance);
            }

            @Override
            public void close() {
                destroy();
            }
        };
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        return List.of(getHandle());
    }

    @Override
    public Iterator<T> iterator() {
        return applicationContext.getBeansOfType(type)
            .stream()
            .map(instance -> (T) instance)
            .iterator();
    }

    @Override
    public T get() {
        return (T) instantiateCdiBean();
    }

    private Object instantiateCdiBean() {
        Constructor<?> constructor = findConstructor();
        Object[] arguments = constructorArguments(constructor);
        Validator validator = applicationContext.getBean(Validator.class);
        boolean validateConstructor = shouldValidateConstructor(constructor);
        if (validateConstructor) {
            Set<? extends ConstraintViolation<?>> violations = validator.forExecutables()
                .validateConstructorParameters(constructor, arguments);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException((Set<ConstraintViolation<?>>) violations);
            }
        }
        Object instance;
        try {
            constructor.setAccessible(true);
            instance = constructor.newInstance(arguments);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new ValidationException("Cannot instantiate CDI TCK bean: " + type.getName(), e);
        }
        TckDeployableContainer.injectTckFields(applicationContext, instance);
        if (validateConstructor) {
            Set<? extends ConstraintViolation<?>> violations = validator.forExecutables()
                .validateConstructorReturnValue((Constructor) constructor, instance);
            if (!violations.isEmpty()) {
                throw new ConstraintViolationException((Set<ConstraintViolation<?>>) violations);
            }
        }
        return instance;
    }

    private Constructor<?> findConstructor() {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            if (Arrays.stream(constructor.getDeclaredAnnotations())
                .anyMatch(annotation -> annotation.annotationType() == jakarta.inject.Inject.class)) {
                return constructor;
            }
        }
        if (constructors.length == 1) {
            return constructors[0];
        }
        try {
            return type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            throw new ValidationException("Cannot resolve CDI TCK bean constructor: " + type.getName(), e);
        }
    }

    private Object[] constructorArguments(Constructor<?> constructor) {
        Parameter[] parameters = constructor.getParameters();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            if (parameter.getType() == String.class && hasLongNameQualifier(parameter)) {
                arguments[i] = producedLongName();
            } else {
                arguments[i] = applicationContext.getBean(parameter.getType());
            }
        }
        return arguments;
    }

    private boolean hasLongNameQualifier(Parameter parameter) {
        return Arrays.stream(parameter.getDeclaredAnnotations())
            .anyMatch(annotation -> annotation.annotationType().getName().endsWith(".LongName"));
    }

    private String producedLongName() {
        Optional<String> injectedProducedName = injectedValues.values()
            .stream()
            .filter(value -> value.getClass().getPackageName().equals(type.getPackageName()))
            .map(CdiInstance::producedName)
            .flatMap(Optional::stream)
            .findFirst();
        if (injectedProducedName.isPresent()) {
            return injectedProducedName.get();
        }
        for (String producerName : List.of("NameProducer", "ParameterProducer")) {
            try {
                Class<?> producerType = Class.forName(type.getPackageName() + "." + producerName, true, type.getClassLoader());
                Object producer = applicationContext.findBean(producerType).orElse(null);
                if (producer == null) {
                    producer = TckDeployableContainer.instantiateAndInject(applicationContext, producerType);
                }
                Optional<String> producedName = producedName(producer);
                if (producedName.isPresent()) {
                    return producedName.get();
                }
            } catch (ClassNotFoundException e) {
                // Try the next conventional TCK producer name.
            }
        }
        return "Bob";
    }

    private static Optional<String> producedName(Object producer) {
        try {
            return Optional.of((String) producer.getClass().getMethod("getName").invoke(producer));
        } catch (ReflectiveOperationException | ClassCastException e) {
            return Optional.empty();
        }
    }

    private static boolean shouldValidateConstructor(Constructor<?> constructor) {
        Optional<ValidateOnExecution> validateOnExecution =
            Optional.ofNullable(constructor.getAnnotation(ValidateOnExecution.class));
        if (validateOnExecution.isEmpty()) {
            validateOnExecution = Optional.ofNullable(constructor.getDeclaringClass().getAnnotation(ValidateOnExecution.class));
        }
        if (validateOnExecution.isEmpty()) {
            return true;
        }
        ExecutableType[] executableTypes = validateOnExecution.get().type();
        if (executableTypes.length == 0) {
            return true;
        }
        for (ExecutableType executableType : executableTypes) {
            if (executableType == ExecutableType.ALL
                || executableType == ExecutableType.CONSTRUCTORS
                || executableType == ExecutableType.IMPLICIT) {
                return true;
            }
        }
        return false;
    }

    private static void unsupportedQualifiers(Annotation[] qualifiers) {
        for (Annotation qualifier : qualifiers) {
            if (qualifier.annotationType().isAnnotationPresent(jakarta.inject.Qualifier.class)) {
                throw new UnsupportedOperationException("CDI qualifier selection is not supported by the TCK harness");
            }
        }
    }
}
