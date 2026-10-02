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

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.metadata.ContainerMapping;
import io.micronaut.validation.validator.metadata.ContainerMappings;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.GroupSequence;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ValidationException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.valueextraction.ValueExtractor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The {@link ReflectionSupport} reading the generated metadata and nothing else: an executable a
 * caller names is the one of a bean definition or of a bean introspection, the hierarchy of an
 * executable is the one the introspections of the super types describe, and what only reflection
 * could read is not read.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
@NullMarked
final class CompileTimeSupport implements ReflectionSupport {
    private final BeanIntrospector introspector;
    private final ClassLoader classLoader;

    CompileTimeSupport() {
        this(BeanIntrospector.forClassLoader(CompileTimeSupport.class.getClassLoader()));
    }

    CompileTimeSupport(BeanIntrospector introspector) {
        this(introspector, CompileTimeSupport.class.getClassLoader());
    }

    CompileTimeSupport(BeanIntrospector introspector, ClassLoader classLoader) {
        this.introspector = introspector;
        this.classLoader = classLoader;
    }

    @Override
    public ClassLoader classLoader() {
        return classLoader;
    }

    @Override
    public ReflectionSupport withClassLoader(ClassLoader loader) {
        return new CompileTimeSupport(BeanIntrospector.forClassLoader(loader), loader);
    }

    @Override
    public BeanIntrospector introspector() {
        return introspector;
    }

    @Override
    public ReflectionSupport withIntrospector(BeanIntrospector introspector) {
        return new CompileTimeSupport(introspector, classLoader);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method) {
        Class<T> declaringType = (Class<T>) method.getDeclaringClass();
        Optional<ExecutableMethod<T, Object>> found = locator.findExecutableMethod(declaringType, method.getName(), method.getParameterTypes());
        // the locator answers for any bean of the type, a sub type overriding the method included,
        // and falls
        // back to a match on the name alone: only the method the type of the method declares is the
        // one named
        if (found.isPresent()
            && found.get().getDeclaringType() == declaringType
            && Arrays.equals(found.get().getArgumentTypes(), method.getParameterTypes())) {
            return new CallerSuppliedExecutable<>(found.get(), method);
        }
        BeanIntrospection<T> introspection = introspector.findIntrospection(declaringType).orElse(null);
        if (introspection != null) {
            for (BeanMethod<T, Object> beanMethod : introspection.getBeanMethods()) {
                if (beanMethod.getName().equals(method.getName())
                    && Arrays.equals(Argument.toClassArray(beanMethod.getArguments()), method.getParameterTypes())) {
                    return new IntrospectedExecutable<>(declaringType, beanMethod, method);
                }
            }
        }
        throw new ValidationException("No metadata describes the method " + method.getName() + Arrays.toString(method.getParameterTypes())
            + " of " + declaringType.getName() + ": the type is neither a bean nor introspected with the method listed as"
                        + " executable, and the micronaut-validation-reflection module, which would"
                        + " describe it reflectively, is not present");
    }

    @Override
    public <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor) {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (introspection != null) {
            for (BeanConstructor<T> candidate : introspection.getConstructors()) {
                if (Arrays.equals(Argument.toClassArray(candidate.getArguments()), parameterTypes)) {
                    return candidate;
                }
            }
        }
        throw new ValidationException("No metadata describes the constructor " + constructor.getDeclaringClass().getName() + Arrays.toString(parameterTypes)
            + ": the type is not introspected with that constructor, and the"
                        + " micronaut-validation-reflection module, which would describe it"
                        + " reflectively, is not present");
    }

    @Override
    public ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name) {
        if (introspector.findIntrospection(local.declaringType()).isEmpty()) {
            return ExecutableHierarchy.merge(local, local, List.of());
        }
        return ExecutableHierarchy.resolve(this, introspector, local, name);
    }

    @Override
    public boolean separatesDeclarations(BeanIntrospection<?> introspection) {
        return introspection.separatesDeclarations();
    }

    @Override
    public List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        return List.of();
    }

    @Override
    public void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue) {
        parentAnnotationValue.stringValue(ValidationAnnotationUtil.COMPOSITION_DEFINITION_ERROR).ifPresent(error -> {
            throw new ConstraintDefinitionException(error);
        });
    }

    /**
     * Reads inherited bindings from generated introspection type arguments, retaining their
     * declaration-owned validation annotations.
     */
    @Override
    public @Nullable Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        BeanIntrospection<?> introspection =
                introspector.findIntrospection(declaredType).orElse(null);
        if (introspection == null) {
            return null;
        }
        List<Argument<?>> arguments =
                ValidationMetadataSupport.typeArguments(introspection, containerType);
        return typeArgumentIndex >= 0 && typeArgumentIndex < arguments.size()
                ? arguments.get(typeArgumentIndex)
                : null;
    }

    /**
     * Uses the processor's variable mappings for renamed or reordered container arguments.
     * Fixed JDK collection contracts preserve their known argument order. Other containers
     * without generated mappings require the optional reflection provider.
     */
    @Override
    public @Nullable Integer extractedTypeArgumentIndex(
            Class<?> declaredType, Class<?> containerType, int typeArgumentIndex) {
        if (declaredType == containerType) {
            return typeArgumentIndex;
        }
        BeanIntrospection<?> introspection =
                introspector.findIntrospection(declaredType).orElse(null);
        if (introspection != null) {
            AnnotationValue<ContainerMappings> mappings =
                    introspection.getAnnotationMetadata().getAnnotation(ContainerMappings.class);
            for (AnnotationValue<ContainerMapping> mapping :
                    mappings == null
                            ? List.<AnnotationValue<ContainerMapping>>of()
                            : mappings.getAnnotations("value", ContainerMapping.class)) {
                if (mapping.stringValue("type").orElse("").equals(containerType.getName())) {
                    int[] indexes = mapping.intValues("indexes");
                    return typeArgumentIndex >= 0
                                    && typeArgumentIndex < indexes.length
                                    && indexes[typeArgumentIndex] >= 0
                            ? indexes[typeArgumentIndex]
                            : null;
                }
            }
        }
        // These JDK contracts have a specified, unchanged variable order.
        if (declaredType == ArrayList.class
                || declaredType == LinkedList.class
                || declaredType == HashSet.class
                || declaredType == LinkedHashSet.class
                || declaredType == TreeSet.class
                || declaredType == List.class
                || declaredType == Set.class
                || declaredType == Collection.class
                || declaredType == HashMap.class
                || declaredType == LinkedHashMap.class
                || declaredType == TreeMap.class
                || declaredType == SortedMap.class
                || declaredType == NavigableMap.class
                || declaredType == SortedSet.class
                || declaredType == NavigableSet.class) {
            return typeArgumentIndex;
        }
        throw missing(
                "container-variable mappings from "
                        + declaredType.getName()
                        + " to "
                        + containerType.getName());
    }

    @Override
    public <T> T instantiate(Class<T> type) {
        throw missing("a constructor of " + type.getName() + ", which is neither introspected nor a bean");
    }

    @Override
    public void checkConstraintDefinition(Class<? extends Annotation> constraintType) {
        throw missing("the members " + constraintType.getName() + " declares, which the constraint definition"
            + " rules are checked against");
    }

    @Override
    public <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType) {
        BeanIntrospection<?> introspection =
                introspector.findIntrospection(type).orElse(null);
        if (introspection != null) {
            List<Argument<?>> arguments =
                    ValidationMetadataSupport.typeArguments(introspection, superType);
            if (!arguments.isEmpty()) {
                return Argument.of(superType, arguments.toArray(Argument.ZERO_ARGUMENTS));
            }
        }
        throw missing("what " + type.getName() + " binds the type arguments of " + superType.getName() + " to");
    }

    /**
     * The annotation processor records the validators a constraint declares on every occurrence it
     * compiles, so an occurrence carrying none is one of a constraint that declares none. A
     * constraint compiled without the processor is the case this cannot tell apart, and the
     * reflection module reads the type for it.
     */
    @Override
    public AnnotationValue<? extends Annotation> withDeclaredValidators(AnnotationValue<? extends Annotation> value,
                                                                        Class<? extends Annotation> constraintType) {
        return value;
    }

    /**
     * The processor retains the marker on the occurrence of a constraint that carries it, so an
     * occurrence without it is of a constraint that is not marked. The one case this cannot tell is
     * a marker on a constraint composed inside another composed constraint, which the retained tree
     * does not reach: the reflection module reads the type for it.
     */
    @Override
    public boolean reportsAsSingleViolation(Class<? extends Annotation> constraintType) {
        return false;
    }

    /**
     * The processor records the validators a constraint declares on every occurrence it compiles,
     * so a constraint the metadata says nothing about declares none.
     */
    @Override
    public List<Class<?>> declaredValidators(Class<? extends Annotation> constraintType) {
        return List.of();
    }

    /**
     * The targets are read from the introspection of a validator; a validator without one is
     * described by nothing here.
     */
    @Override
    public Set<ValidationTarget> supportedValidationTargets(Class<?> validatorType) {
        return Set.of();
    }

    /**
     * A group sequence is read from the introspection of the group; a group the archive never
     * introspected is not known to be one, and the rule that a conversion may not name one goes
     * unchecked rather than wrong.
     */
    @Override
    public boolean isGroupSequence(Class<?> group) {
        return introspector.findIntrospection(group)
            .map(introspection -> introspection.hasAnnotation(GroupSequence.class)).orElse(false);
    }

    /**
     * The contract is retained on every occurrence the processor compiles, so an occurrence that
     * does not carry it is not one of a constraint as far as the generated metadata goes.
     */
    @Override
    public boolean isConstraintAnnotation(Class<?> annotationType) {
        return false;
    }

    @Override
    public List<String> parameterNames(Executable executable) {
        BeanIntrospection<?> introspection =
                introspector
                        .findIntrospection(executable.getDeclaringClass())
                        .orElse(null);
        if (introspection != null) {
            if (executable instanceof Method method) {
                for (var candidate : introspection.getBeanMethods()) {
                    if (candidate.getName().equals(method.getName())
                            && Arrays.equals(
                                    Argument.toClassArray(candidate.getArguments()),
                                    method.getParameterTypes())) {
                        return Arrays.stream(candidate.getArguments())
                                .map(Argument::getName)
                                .toList();
                    }
                }
            } else {
                for (var candidate : introspection.getConstructors()) {
                    if (Arrays.equals(
                            Argument.toClassArray(candidate.getArguments()),
                            executable.getParameterTypes())) {
                        return Arrays.stream(candidate.getArguments())
                                .map(Argument::getName)
                                .toList();
                    }
                }
            }
        }
        throw missing("the parameter names of " + executable.getName() + " of "
            + executable.getDeclaringClass().getName());
    }

    @Override
    public Argument<?> valueExtractorArgument(Class<?> extractorType) {
        Argument<?> argument =
                genericSuperArgument(
                        extractorType, ValueExtractor.class);
        if (argument != null) {
            AnnotationMetadata declared =
                    introspector.getIntrospection(extractorType).getAnnotationMetadata();
            return argument.withAnnotationMetadata(
                    ExecutableHierarchy.mergeMetadata(
                            List.of(argument.getAnnotationMetadata(), declared)));
        }
        throw missing(
                "the extractor signature "
                        + extractorType.getName()
                        + " declares, for an instance registered through the configuration API");
    }

    /**
     * The generated metadata does not describe what was asked for, and reading it means reading the
     * class, which is what the reflection module is for.
     */
    private static ValidationException missing(String what) {
        return new ValidationException(
                "No generated metadata describes "
                        + what
                        + ": reading it means reading the class, and the"
                        + " micronaut-validation-reflection module, which would read it, is not"
                        + " present");
    }
}
