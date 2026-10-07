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
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.MethodReference;
import io.micronaut.inject.annotation.AnnotationMetadataSupport;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.reflection.MethodHierarchy;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.reflection.ReflectionArguments;
import io.micronaut.reflection.ReflectionExecutables;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.ValidationAnnotationUtil;
import io.micronaut.validation.validator.metadata.ValidationEnumValues;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.ConfiguredMetadata;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationField;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.GroupSequence;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * The {@link ReflectionSupport} over the reflection module of micronaut-core: what the generated
 * metadata does not describe is read from the class itself.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
public final class ReflectionValidationSupport implements ReflectionSupport {

    private final BeanIntrospector introspector;
    private final ClassLoader classLoader;
    private final ReflectionSupport generated;

    /** Creates the support; it is instantiated by the service loader. */
    public ReflectionValidationSupport() {
        this(BeanIntrospector.forClassLoader(ReflectionValidationSupport.class.getClassLoader()));
    }

    private ReflectionValidationSupport(BeanIntrospector introspector) {
        this(introspector, ReflectionValidationSupport.class.getClassLoader());
    }

    private ReflectionValidationSupport(BeanIntrospector introspector, ClassLoader classLoader) {
        this.introspector = introspector;
        this.classLoader = classLoader;
        this.generated = ReflectionSupport.generated(introspector, classLoader);
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public ReflectionSupport withClassLoader(ClassLoader loader) {
        return new ReflectionValidationSupport(BeanIntrospector.forClassLoader(loader), loader);
    }

    @Override
    public BeanIntrospector introspector() {
        return introspector;
    }

    @Override
    public ReflectionSupport withIntrospector(BeanIntrospector introspector) {
        return new ReflectionValidationSupport(introspector, classLoader);
    }

    @Override
    public BeanIntrospector supplemented(BeanIntrospector introspector) {
        return ReflectiveValidation.supplemented(introspector);
    }

    @Override
    public ResourceBundle messageBundle(
            String baseName, Locale locale, ClassLoader loader) {
        if (!ReflectiveValidation.isEnabled()) {
            return ReflectionSupport.super.messageBundle(baseName, locale, loader);
        }
        return ResourceBundle.getBundle(baseName, locale, loader);
    }

    @Override
    public boolean isReflectionEnabled() {
        return ReflectiveValidation.isEnabled();
    }

    @Override
    public <T> @Nullable Object readMember(BeanPropertyMember<T, ?> member, T bean) {
        if (!ReflectiveValidation.isEnabled()
            && !member.getAnnotationMetadata().hasAnnotation(ReflectiveAccess.class)) {
            return ReflectionSupport.super.readMember(member, bean);
        }
        if (member.getAnnotationMetadata().booleanValue(
                ValidationField.class, "reflection").orElse(false)) {
            try {
                var field = member.getDeclaringType().getDeclaredField(member.getName());
                if (!field.trySetAccessible()) {
                    throw new ValidationException("Cannot access field " + member.getDeclaringType().getName() + "." + member.getName());
                }
                return field.get(bean);
            } catch (ReflectiveOperationException | SecurityException e) {
                throw new ValidationException("Cannot read field " + member.getDeclaringType().getName() + "." + member.getName(), e);
            }
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
        return (Object[]) Array.newInstance(type, size);
    }

    @Override
    public Map<String, AnnotationMember> annotationMembers(
            Class<? extends Annotation> type) {
        var result = new LinkedHashMap<String, AnnotationMember>();
        for (Method member : type.getDeclaredMethods()) {
            result.put(
                    member.getName(),
                    new AnnotationMember(member.getReturnType(), member.getDefaultValue() == null));
        }
        return Map.copyOf(result);
    }

    @Override
    public @Nullable ValidationDeclaration declaration(
            Class<?> type, String kind, String name, List<Class<?>> parameters) {
        var introspection = BeanIntrospector.forClassLoader(type.getClassLoader()).findIntrospection(type).orElse(null);
        if (introspection != null) {
            var generated = ValidationDeclaration.generated(this, introspection, kind, name, parameters);
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
                                ReflectionArguments.of(field, type),
                                ReflectionAnnotations.metadataOf(field),
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
                                            && Arrays.asList(method.getParameterTypes())
                                                    .equals(parameters);
                    if (matches) {
                        method.trySetAccessible();
                        var value =
                                ReflectionArguments.of(
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
            parameters.add(ReflectionArguments.of(parameter));
        }
        return new ValidationDeclaration(
                executable.getDeclaringClass(),
                name,
                value,
                ReflectionAnnotations.metadataOf(executable),
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
        return ReflectionArguments.of(type);
    }

    @Override
    public <T extends Annotation> T annotation(Class<T> type, AnnotationValue<?> value) {
        return AnnotationMetadataSupport.buildAnnotation(
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
    public Argument<?> prepareArgument(Argument<?> argument) {
        Argument<?>[] parameters = argument.getTypeParameters();
        Argument<?>[] prepared = new Argument<?>[parameters.length];
        boolean validated = argument.getAnnotationMetadata().hasAnnotation(ValidatedElement.class)
            || argument.getAnnotationMetadata().hasStereotype(Constraint.class)
            || argument.getAnnotationMetadata().hasAnnotation(Valid.class);
        for (int i = 0; i < parameters.length; i++) {
            prepared[i] = prepareArgument(parameters[i]);
            validated |= prepared[i].getAnnotationMetadata().hasAnnotation(ValidatedElement.class);
        }
        var metadata = argument.getAnnotationMetadata();
        if (validated && !metadata.hasAnnotation(ValidatedElement.class)) {
            var marker = new MutableAnnotationMetadata();
            marker.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
            metadata = ConfiguredMetadata.merge(metadata, marker);
        }
        return ExecutableHierarchy.copyArgument(argument, metadata, prepared);
    }

    @Override
    public @Nullable Map<String, Object> annotationAttributes(Class<? extends Annotation> type, AnnotationValue<?> value) {
        if (ValidationMetadataSupport.standardConstraint(type.getName()) == type
            || (value.booleanValue(ValidationAnnotationUtil.DEFINITION_CHECKED).orElse(false)
                && value.stringValues(ValidationAnnotationUtil.RUNTIME_ATTRIBUTES).length == 0)) {
            return null;
        }
        var annotation = annotation(type, value);
        var attributes = new LinkedHashMap<String, Object>();
        for (var member : type.getDeclaredMethods()) {
            try {
                member.setAccessible(true);
                attributes.put(member.getName(), member.invoke(annotation));
            } catch (ReflectiveOperationException e) {
                throw new ValidationException("Cannot read constraint attributes for " + type.getName(), e);
            }
        }
        return attributes;
    }

    @Override
    public List<Class<?>> interfaces(Class<?> type) {
        var generated = ValidationMetadataSupport.hierarchy(type);
        return generated == null ? List.of(type.getInterfaces()) : List.of(generated.classValues("interfaces"));
    }

    @Override
    public boolean canResolveHierarchy(Class<?> type) {
        return true;
    }

    @Override
    public @Nullable Class<?> superType(Class<?> type) {
        var generated = ValidationMetadataSupport.hierarchy(type);
        return generated == null ? type.getSuperclass() : generated.classValue("superType").orElse(null);
    }

    @Override
    public Method targetMethod(MethodReference<?, ?> executable) {
        return executable.getTargetMethod();
    }

    @Override
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        try {
            return generated.executableMethod(locator, introspector, method);
        } catch (ValidationException missing) {
            return ReflectionExecutables.executableMethod(locator, introspector, method);
        }
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        try {
            return generated.beanConstructor(introspection, constructor);
        } catch (ValidationException missing) {
            return ReflectionExecutables.beanConstructor(introspection, constructor);
        }
    }

    @Override
    public ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name) {
        if (!ReflectiveValidation.isEnabled() || hasGeneratedHierarchy(local.declaringType())) {
            return ExecutableHierarchy.resolve(this, introspector, local, name);
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

    private boolean hasGeneratedHierarchy(Class<?> type) {
        var metadata = introspector.findIntrospection(type).map(BeanIntrospection::getAnnotationMetadata).orElse(null);
        var hierarchy = metadata == null ? null : metadata.getAnnotation(ValidationMetadataSupport.HIERARCHY);
        return hierarchy != null && hierarchy.getAnnotations("types").stream().allMatch(entry -> {
            Class<?> declared = entry.classValue("type").orElse(null);
            return declared != null && (declared.getName().startsWith("java.")
                || declared.getName().startsWith("jakarta.") || introspector.findIntrospection(declared).isPresent());
        });
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
        return ReflectedComposition.composingConstraints(this, constraintType, parentAnnotationValue);
    }

    @Override
    public void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        if (parentAnnotationValue.booleanValue(ValidationAnnotationUtil.DEFINITION_CHECKED).orElse(false)) {
            parentAnnotationValue.stringValue(ValidationAnnotationUtil.COMPOSITION_DEFINITION_ERROR).ifPresent(error -> {
                throw new ConstraintDefinitionException(error);
            });
        } else {
            ReflectedComposition.checkDeclaredComposition(this, constraintType, parentAnnotationValue);
        }
    }

    @Override
    public @Nullable Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        var argument = generated.boundTypeArgument(declaredType, containerType, typeArgumentIndex);
        return argument == null
            ? ReflectionContainerTypeArguments.boundTypeArgument(declaredType, containerType, typeArgumentIndex)
            : argument;
    }

    @Override
    public @Nullable Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        try {
            return generated.extractedTypeArgumentIndex(declaredType, containerType, typeArgumentIndex);
        } catch (ValidationException missing) {
            return ReflectionContainerTypeArguments.extractedTypeArgumentIndex(declaredType, containerType, typeArgumentIndex);
        }
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
        ReflectedConstraintDefinitions.validate(constraintType);
    }

    @Override
    public <T> @Nullable Argument<T> genericSuperArgument(Class<?> type, Class<T> superType) {
        try {
            return generated.genericSuperArgument(type, superType);
        } catch (ValidationException missing) {
            return ReflectionArguments.resolveGenericToArgument(type, superType);
        }
    }

    @Override
    public AnnotationValue<? extends Annotation> withDeclaredValidators(AnnotationValue<? extends Annotation> value,
                                                                        Class<? extends Annotation> constraintType) {
        return value.booleanValue(ValidationAnnotationUtil.DEFINITION_CHECKED).orElse(false)
            ? value : ReflectedConstraints.withDeclaredValidators(value, constraintType);
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
        return introspector.findIntrospection(group).map(value -> value.hasAnnotation(GroupSequence.class))
            .orElseGet(() -> group.isAnnotationPresent(GroupSequence.class));
    }

    @Override
    public Set<ValidationTarget> supportedValidationTargets(Class<?> validatorType) {
        var introspection = introspector.findIntrospection(validatorType).orElse(null);
        if (introspection != null) {
            return ValidationEnumValues.targets(introspection.getAnnotationMetadata());
        }
        SupportedValidationTarget supported = validatorType.getAnnotation(SupportedValidationTarget.class);
        return supported == null ? Set.of() : Set.of(supported.value());
    }

    @Override
    public List<String> parameterNames(Executable executable) {
        try {
            return generated.parameterNames(executable);
        } catch (ValidationException missing) {
            // Only signatures absent from generated executable metadata need class-file names.
        }
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
        try {
            return generated.valueExtractorArgument(extractorType);
        } catch (ValidationException missing) {
            return ReflectedValueExtractors.argumentOf(extractorType);
        }
    }
}
