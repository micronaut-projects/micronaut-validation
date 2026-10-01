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
package io.micronaut.validation.reflection;

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.reflection.ReflectionExecutables;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import jakarta.validation.Constraint;
import jakarta.validation.GroupSequence;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * The {@link ReflectionSupport} over the reflection module of micronaut-core: what the generated
 * metadata does not describe is read from the class itself.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class ReflectionValidationSupport implements ReflectionSupport {

    /** Creates the support; it is instantiated by the service loader. */
    public ReflectionValidationSupport() {
        // the service loader needs a no-arg constructor and there is no state to set up
    }

    @Override
    public BeanIntrospector supplemented(BeanIntrospector introspector) {
        return ReflectiveValidation.supplemented(introspector);
    }

    @Override
    public boolean isSupplemented(BeanIntrospector introspector) {
        return ReflectiveValidation.isSupplemented(introspector);
    }

    @Override
    public java.util.ResourceBundle messageBundle(
            String baseName, java.util.Locale locale, ClassLoader loader) {
        if (!ReflectiveValidation.isEnabled()) {
            return ReflectionSupport.super.messageBundle(baseName, locale, loader);
        }
        return java.util.ResourceBundle.getBundle(baseName, locale, loader);
    }

    @Override
    public boolean isReflectionEnabled() {
        return ReflectiveValidation.isEnabled();
    }

    @Override
    public <T> @Nullable Object readMember(BeanPropertyMember<T, ?> member, T bean) {
        if (!ReflectiveValidation.isEnabled()) {
            return ReflectionSupport.super.readMember(member, bean);
        }
        return member.read(bean);
    }

    @Override
    public Class<?> classForName(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new ValidationException("Cannot load validation class " + name, e);
        }
    }

    @Override
    public Object[] array(Class<?> type, int size) {
        return (Object[]) java.lang.reflect.Array.newInstance(type, size);
    }

    @Override
    public java.util.Map<String, AnnotationMember> annotationMembers(
            Class<? extends Annotation> type) {
        var result = new LinkedHashMap<String, AnnotationMember>();
        for (Method member : type.getDeclaredMethods()) {
            result.put(
                    member.getName(),
                    new AnnotationMember(member.getReturnType(), member.getDefaultValue() == null));
        }
        return java.util.Map.copyOf(result);
    }

    @Override
    public @Nullable ValidationDeclaration declaration(
            Class<?> type, String kind, String name, List<Class<?>> parameters) {
        var introspection = BeanIntrospector.forClassLoader(type.getClassLoader()).findIntrospection(type).orElse(null);
        if (introspection != null) {
            var generated = ValidationDeclaration.generated(introspection, kind, name, parameters);
            if (generated != null) {
                return generated;
            }
        }
        if (!ReflectiveValidation.isEnabled()) {
            return ReflectionSupport.super.declaration(type, kind, name, parameters);
        }
        try {
            if (kind.equals("constructor")) {
                Constructor<?> constructor =
                        type.getDeclaredConstructor(parameters.toArray(Class<?>[]::new));
                return declaration(constructor, name, Argument.of(type));
            }
            for (Class<?> current = type;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                if (kind.equals("field")) {
                    try {
                        var field = current.getDeclaredField(name);
                        field.trySetAccessible();
                        return new ValidationDeclaration(
                                current,
                                name,
                                io.micronaut.reflection.ReflectionArguments.of(field, type),
                                io.micronaut.reflection.ReflectionAnnotations.metadataOf(field),
                                List.of(),
                                bean -> {
                                    try {
                                        return field.get(bean);
                                    } catch (IllegalAccessException e) {
                                        throw new ValidationException(
                                                "Cannot read XML field " + field, e);
                                    }
                                });
                    } catch (NoSuchFieldException ignored) {
                        continue;
                    }
                }
                for (Method method : current.getDeclaredMethods()) {
                    boolean matches =
                            kind.equals("getter")
                                    ? method.getParameterCount() == 0
                                            && (method.getName()
                                                            .equals(
                                                                    "get"
                                                                            + Character.toUpperCase(
                                                                                    name.charAt(0))
                                                                            + name.substring(1))
                                                    || method.getName()
                                                                    .equals(
                                                                            "is"
                                                                                    + Character
                                                                                            .toUpperCase(
                                                                                                    name
                                                                                                            .charAt(
                                                                                                                    0))
                                                                                    + name
                                                                                            .substring(
                                                                                                    1))
                                                            && (method.getReturnType()
                                                                            == boolean.class
                                                                    || method.getReturnType()
                                                                            == Boolean.class))
                                    : method.getName().equals(name)
                                            && java.util.Arrays.asList(method.getParameterTypes())
                                                    .equals(parameters);
                    if (matches) {
                        method.trySetAccessible();
                        var value =
                                io.micronaut.reflection.ReflectionArguments.of(
                                        method.getAnnotatedReturnType());
                        var declaration = declaration(method, method.getName(), value);
                        return new ValidationDeclaration(
                                declaration.declaringType(),
                                declaration.name(),
                                value,
                                declaration.metadata(),
                                declaration.parameters(),
                                kind.equals("getter")
                                        ? bean -> {
                                            try {
                                                return method.invoke(bean);
                                            } catch (ReflectiveOperationException e) {
                                                throw new ValidationException(
                                                        "Cannot invoke XML getter " + method, e);
                                            }
                                        }
                                        : null);
                    }
                }
            }
        } catch (NoSuchMethodException ignored) {
            return null;
        }
        return null;
    }

    private static ValidationDeclaration declaration(
            Executable executable, String name, Argument<?> value) {
        var parameters = new ArrayList<Argument<?>>();
        for (Parameter parameter : executable.getParameters()) {
            parameters.add(io.micronaut.reflection.ReflectionArguments.of(parameter));
        }
        return new ValidationDeclaration(
                executable.getDeclaringClass(),
                name,
                value,
                io.micronaut.reflection.ReflectionAnnotations.metadataOf(executable),
                parameters,
                null);
    }

    @Override
    public List<Enum<?>> enumConstants(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        List<Enum<?>> result = new ArrayList<>();
        if (constants != null) {
            for (Object value : constants) {
                result.add((Enum<?>) value);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Argument<?> argumentOf(Type type) {
        return io.micronaut.reflection.ReflectionArguments.of(type);
    }

    @Override
    public <T extends Annotation> T annotation(Class<T> type, AnnotationValue<?> value) {
        return io.micronaut.inject.annotation.AnnotationMetadataSupport.buildAnnotation(
                type, (AnnotationValue<T>) value);
    }

    @Override
    public @Nullable Object instantiate(String name, ClassLoader classLoader) {
        try {
            return instantiate(Class.forName(name, false, classLoader));
        } catch (ClassNotFoundException e) {
            throw new ValidationException("Cannot find bootstrap class " + name, e);
        }
    }

    @Override
    public List<Class<?>> interfaces(Class<?> type) {
        var generated = GeneratedAnnotationFactories.typeMetadata(type);
        return generated == null ? List.of(type.getInterfaces()) : generated.interfaces();
    }

    @Override
    public @Nullable Class<?> superType(Class<?> type) {
        var generated = GeneratedAnnotationFactories.typeMetadata(type);
        return generated == null ? type.getSuperclass() : generated.superType();
    }

    @Override
    public Method targetMethod(io.micronaut.inject.MethodReference<?, ?> executable) {
        return executable.getTargetMethod();
    }

    @Override
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        return ReflectionExecutables.executableMethod(locator, introspector, method);
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        return ReflectionExecutables.beanConstructor(introspection, constructor);
    }

    @Override
    public ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name) {
        if (!ReflectiveValidation.isEnabled()) {
            return ExecutableHierarchy.resolve(introspector, local, name);
        }
        MethodHierarchy hierarchy = MethodHierarchy.resolve(introspector, toCore(local), name);
        // the levels are merged here rather than taken merged from core: a declaration of the
        // validator carries
        // the method annotations on its return argument, which a reflective declaration of core
        // does not
        return ExecutableHierarchy.merge(local,
            fromCore(hierarchy.declared()),
            hierarchy.inherited().stream().map(ReflectionValidationSupport::fromCore).toList());
    }

    private static MethodHierarchy.Declaration toCore(ExecutableHierarchy.Declaration declaration) {
        return new MethodHierarchy.Declaration(declaration.declaringType(),
            declaration.annotationMetadata(),
            declaration.arguments(),
            declaration.returnArgument(),
            declaration.exact());
    }

    private static ExecutableHierarchy.Declaration fromCore(MethodHierarchy.Declaration declaration) {
        return new ExecutableHierarchy.Declaration(declaration.declaringType(),
            declaration.annotationMetadata(),
            declaration.arguments(),
            declaration.returnArgument(),
            declaration.exact());
    }

    @Override
    public boolean separatesDeclarations(BeanIntrospection<?> introspection) {
        return introspection.separatesDeclarations();
    }

    @Override
    public List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        return ReflectedComposition.composingConstraints(constraintType, parentAnnotationValue);
    }

    @Override
    public void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        if (!io.micronaut.validation.validator.metadata.GeneratedConstraintRules.composition(
                constraintType)) {
            ReflectedComposition.checkDeclaredComposition(constraintType, parentAnnotationValue);
        }
    }

    @Override
    public @Nullable Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        return ReflectionContainerTypeArguments.boundTypeArgument(declaredType, containerType, typeArgumentIndex);
    }

    @Override
    public @Nullable Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        return ReflectionContainerTypeArguments.extractedTypeArgumentIndex(declaredType, containerType, typeArgumentIndex);
    }

    @Override
    public <T> @Nullable T instantiate(Class<T> type) {
        Constructor<T> constructor;
        try {
            constructor = type.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            return null;
        }
        try {
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InvocationTargetException e) {
            throw new ValidationException("Cannot instantiate the constraint validator: " + type.getName(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot instantiate the constraint validator: " + type.getName(), e);
        }
    }

    @Override
    public void checkConstraintDefinition(Class<? extends Annotation> constraintType) {
        if (!io.micronaut.validation.validator.metadata.GeneratedConstraintRules.definition(
                constraintType)) {
            ReflectedConstraintDefinitions.validate(constraintType);
        }
    }

    @Override
    public <T> @Nullable Argument<T> genericSuperArgument(Class<?> type, Class<T> superType) {
        return io.micronaut.reflection.ReflectionArguments.resolveGenericToArgument(type, superType);
    }

    @Override
    public AnnotationValue<? extends Annotation> withDeclaredValidators(AnnotationValue<? extends Annotation> value,
                                                                        Class<? extends Annotation> constraintType) {
        return ReflectedConstraints.withDeclaredValidators(value, constraintType);
    }

    @Override
    public boolean reportsAsSingleViolation(Class<? extends Annotation> constraintType) {
        return constraintType.isAnnotationPresent(ReportAsSingleViolation.class);
    }

    @Override
    public List<Class<?>> declaredValidators(Class<? extends Annotation> constraintType) {
        return ReflectedConstraints.declaredValidators(constraintType);
    }

    @Override
    public boolean isConstraintAnnotation(Class<?> annotationType) {
        return annotationType.isAnnotationPresent(Constraint.class);
    }

    @Override
    public boolean isGroupSequence(Class<?> group) {
        return group.isAnnotationPresent(GroupSequence.class);
    }

    @Override
    public Set<ValidationTarget> supportedValidationTargets(Class<?> validatorType) {
        SupportedValidationTarget supported = validatorType.getAnnotation(SupportedValidationTarget.class);
        return supported == null ? Set.of() : Set.of(supported.value());
    }

    @Override
    public List<String> parameterNames(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        List<String> names = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            names.add(parameter.isNamePresent() ? parameter.getName() : "arg" + i);
        }
        return names;
    }

    @Override
    public Argument<?> valueExtractorArgument(Class<?> extractorType) {
        if (BeanIntrospector.SHARED.findIntrospection(extractorType).isPresent()) {
            var generated =
                    genericSuperArgument(
                            extractorType, jakarta.validation.valueextraction.ValueExtractor.class);
            if (generated != null) {
                var metadata =
                        BeanIntrospector.SHARED
                                .getIntrospection(extractorType)
                                .getAnnotationMetadata();
                return generated.withAnnotationMetadata(
                        ExecutableHierarchy.mergeMetadata(
                                List.of(generated.getAnnotationMetadata(), metadata)));
            }
        }
        return ReflectedValueExtractors.argumentOf(extractorType);
    }
}
