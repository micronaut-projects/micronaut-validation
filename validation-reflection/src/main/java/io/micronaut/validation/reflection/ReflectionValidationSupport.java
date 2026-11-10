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
import io.micronaut.core.beans.BeanProperty;
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

    private static final ClassValue<java.util.Map<String, java.lang.reflect.Field>> ACCESSIBLE_FIELDS = new ClassValue<>() {
        @Override
        protected java.util.Map<String, java.lang.reflect.Field> computeValue(Class<?> type) {
            return new java.util.concurrent.ConcurrentHashMap<>();
        }
    };

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
                ValidationField.class, ValidationField.MEMBER_REFLECTION).orElse(false)) {
            try {
                return accessibleField(member.getDeclaringType(), member.getName()).get(bean);
            } catch (ReflectiveOperationException | SecurityException e) {
                throw new ValidationException("Cannot read field " + member.getDeclaringType().getName() + "." + member.getName(), e);
            }
        }
        return member.read(bean);
    }

    /**
     * A field made accessible, looked up once per type and name: a field is read on every validation of its bean.
     * The cache belongs to the type, so it never keeps the class loader of the type alive.
     */
    private static java.lang.reflect.Field accessibleField(Class<?> type, String name) throws NoSuchFieldException {
        java.lang.reflect.Field field = ACCESSIBLE_FIELDS.get(type).get(name);
        if (field == null) {
            field = type.getDeclaredField(name);
            if (!field.trySetAccessible()) {
                throw new ValidationException("Cannot access field " + type.getName() + "." + name);
            }
            ACCESSIBLE_FIELDS.get(type).put(name, field);
        }
        return field;
    }

    @Override
    public <T> @Nullable Object readMember(BeanPropertyMember<T, ?> member, BeanProperty<T, ?> property, T bean) {
        if (!ReflectiveValidation.isEnabled()
            && !member.getAnnotationMetadata().hasAnnotation(ReflectiveAccess.class)) {
            return ReflectionSupport.super.readMember(member, property, bean);
        }
        // Jakarta Validation reads the annotated declaration itself: a field constraint never sees what the getter returns
        return readMember(member, bean);
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
            Class<?> type, ValidationDeclaration.Kind kind, String name, List<Class<?>> parameters) {
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
            if (kind == ValidationDeclaration.Kind.CONSTRUCTOR) {
                Constructor<?> constructor =
                        type.getDeclaredConstructor(parameters.toArray(Class<?>[]::new));
                return declaration(constructor, name, Argument.of(type));
            }
            for (Class<?> current = type;
                    current != null && current != Object.class;
                    current = current.getSuperclass()) {
                if (kind == ValidationDeclaration.Kind.FIELD) {
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
                            kind == ValidationDeclaration.Kind.GETTER
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
                                kind == ValidationDeclaration.Kind.GETTER
                                        ? bean -> {
                                            try {
                                                return method.invoke(bean);
                                            } catch (ReflectiveOperationException e) {
                                                throw new ValidationException(
                                                        "Cannot invoke XML getter " + method, e);
                                            }
                                        }
                                        : ValidationDeclaration.Reader.NONE);
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
                ValidationDeclaration.Reader.NONE);
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
        // a builder generated at compile time first, a proxy only for an annotation without one
        if (generated.annotationBuilder(type) != null) {
            return generated.annotation(type, value);
        }
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
        // copied only once a level changes: an argument already marked at every level is answered as it is
        Argument<?>[] prepared = null;
        var metadata = argument.getAnnotationMetadata();
        boolean marked = metadata.hasAnnotation(ValidatedElement.class);
        boolean validated = marked
            || metadata.hasStereotype(Constraint.class)
            || metadata.hasAnnotation(Valid.class);
        for (int i = 0; i < parameters.length; i++) {
            Argument<?> preparedParameter = prepareArgument(parameters[i]);
            if (preparedParameter != parameters[i]) {
                if (prepared == null) {
                    prepared = parameters.clone();
                }
                prepared[i] = preparedParameter;
            }
            validated |= preparedParameter.getAnnotationMetadata().hasAnnotation(ValidatedElement.class);
        }
        if (validated && !marked) {
            var marker = new MutableAnnotationMetadata();
            marker.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
            metadata = ConfiguredMetadata.merge(metadata, marker);
        } else if (prepared == null) {
            return argument;
        }
        return argument.withTypeParameters(prepared == null ? parameters : prepared).withAnnotationMetadata(metadata);
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
        return generated == null ? List.of(type.getInterfaces()) : generated.getInterfaces(type);
    }

    @Override
    public boolean canResolveHierarchy(Class<?> type) {
        return true;
    }

    @Override
    public @Nullable Class<?> superType(Class<?> type) {
        var generated = ValidationMetadataSupport.hierarchy(type);
        return generated == null ? type.getSuperclass() : generated.getSuperclass(type).orElse(null);
    }

    @Override
    public @Nullable Method declaredMethod(Class<?> declaringType, String name, Class<?>[] parameterTypes) {
        try {
            return declaringType.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    @Override
    public Method targetMethod(MethodReference<?, ?> executable) {
        ReflectionSupport.checkTargetMethod(executable);
        return executable.getTargetMethod();
    }

    @Override
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        ExecutableMethod<T, Object> described = generated.describedExecutableMethod(locator, introspector, method);
        return described != null ? described : ReflectionExecutables.executableMethod(locator, introspector, method);
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        BeanConstructor<T> described = generated.describedBeanConstructor(introspection, constructor);
        return described != null ? described : ReflectionExecutables.beanConstructor(introspection, constructor);
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
        var hierarchy = ValidationMetadataSupport.hierarchy(introspector, type);
        return hierarchy != null && hierarchy.getTypes().stream().allMatch(declared -> ValidationAnnotationUtil.isPlatformType(declared.getName())
            || introspector.findIntrospection(declared).isPresent());
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
        List<String> described = generated.describedParameterNames(executable);
        if (described != null) {
            return described;
        }
        // only signatures absent from generated executable metadata need class-file names
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
