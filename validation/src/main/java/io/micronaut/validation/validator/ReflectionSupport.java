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
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * What the validator reads through reflection when the {@code micronaut-validation-reflection} module is
 * present, and through the generated metadata alone when it is not.
 *
 * <p>The validator describes a bean, a constraint and an executable through the metadata the annotation
 * processors generate. The Jakarta Validation API also names an executable by its {@link Method} or
 * {@link Constructor}, hands over a value extractor as an instance and composes a constraint from the
 * annotations of its type: where the generated metadata does not describe what such a call names, the
 * reflection module reads it from the class itself. This is the seam between the two, loaded as a service;
 * without an implementation the validator answers from the generated metadata and nothing else.</p>
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public interface ReflectionSupport {

    /**
     * The support in force: the first one registered as a service, else the one reading the generated metadata
     * only.
     *
     * @return The support
     */
    static ReflectionSupport get() {
        return Holder.INSTANCE;
    }

    /**
     * The executable method of the method a caller names: the one of the bean definition when the declaring
     * type is a bean, else the one of the bean introspection, else what the implementation can read from the
     * method itself.
     *
     * @param locator      The locator of the executable methods of the beans
     * @param introspector The introspector
     * @param method       The method
     * @param <T>          The declaring type
     * @return The executable method
     * @throws jakarta.validation.ValidationException When nothing describes the method
     */
    <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method);

    /**
     * The constructor a caller names, with its arguments and annotation metadata: one the introspection
     * describes, else what the implementation can read from the constructor itself.
     *
     * @param introspection The introspection of the declaring type, can be {@code null}
     * @param constructor   The constructor
     * @param <T>           The declaring type
     * @return The bean constructor
     * @throws jakarta.validation.ValidationException When nothing describes the constructor
     */
    <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor);

    /**
     * The hierarchy of an executable: the declarations it overrides or implements, read from the introspections
     * of the super types, and the local one merged with them.
     *
     * @param introspector The introspector of the super types
     * @param local        The executable as validated
     * @param name         Its name
     * @return The resolved hierarchy
     */
    ExecutableHierarchy.Resolved resolveHierarchy(BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name);

    /**
     * Whether an introspection tells the declarations of a type apart from the ones it inherits: which
     * annotations a method declares itself, and the field and the getters of a property by the type declaring
     * each. A generated introspection merges what the super types declare into its own, so it does not.
     *
     * @param introspection The introspection
     * @return Whether the declarations are separated
     */
    boolean separatesDeclarations(BeanIntrospection<?> introspection);

    /**
     * The constraints a constraint type composes, read from the annotations of the type where the generated
     * metadata retains no tree of them: each with the attributes the composed constraint overrides, its groups
     * and payload, and the validators of the constraint.
     *
     * @param constraintType        The composed constraint type
     * @param parentAnnotationValue The occurrence of the composed constraint
     * @return The composing constraints, empty when they cannot be read
     */
    List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue);

    /**
     * Checks the rules of a composition that only the declared form of the constraint annotation type can
     * answer: a constraint composed both directly and inside its repeatable container, which the retained tree
     * of the generated metadata flattens into repeated occurrences; an override naming a member the composing
     * constraint does not declare, or an occurrence it does not have, which the tree cannot tell from a member
     * without a default or from the occurrence it does have.
     *
     * @param constraintType        The composed constraint type
     * @param parentAnnotationValue The occurrence of the composed constraint
     * @throws jakarta.validation.ConstraintDeclarationException When the declared composition breaks a declaration rule
     * @throws jakarta.validation.ConstraintDefinitionException  When it breaks a definition rule
     */
    void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue);

    /**
     * The type a type binds a generic super type's type argument to, with the annotations declared on it: the
     * {@code String} of a {@code class Names implements Iterable<@NotBlank String>} read as an
     * {@code Iterable}. The generated metadata describes what a type declares, not what it binds in a super
     * type it does not restate, so this is read from the class itself.
     *
     * @param declaredType      The type as declared
     * @param containerType     The generic super type it is read as
     * @param typeArgumentIndex The index of the type argument of that super type
     * @return The argument, {@code null} when the type binds no such argument
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex);

    /**
     * Which of a type's own type arguments carries the one a value extractor extracts: a pair declaring
     * {@code implements Map} with its own two arguments the other way round binds them in reverse, so the
     * value a {@code Map} extractor extracts is the first argument of the pair rather than the second.
     *
     * @param declaredType      The type as declared
     * @param containerType     The container type the extractor is written for
     * @param typeArgumentIndex The index of the type argument the extractor extracts
     * @return The index among the type arguments of the declared type
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex);

    /**
     * The argument of a super type as a type binds it: {@code ConstraintValidator<Size, CharSequence>} for a
     * validator declaring {@code implements ConstraintValidator<Size, CharSequence>}. What a type binds in a
     * super type it does not restate is not in the generated metadata, so this is read from the class itself.
     *
     * @param type      The type
     * @param superType The super class or interface to resolve
     * @param <T>       The super type
     * @return The argument, {@code null} when the type does not extend or implement the super type
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType);

    /**
     * Checks a constraint annotation type against the constraint definition rules of the specification: the
     * names and the types of the members it declares, and what its validators support. Only the annotation
     * type itself answers those, so a validator configured to check them strictly needs this read.
     *
     * @param constraintType The constraint annotation type
     * @throws jakarta.validation.ConstraintDefinitionException When the type breaks a rule
     * @throws jakarta.validation.ValidationException When nothing can read the type
     * @since 5.2
     */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType);

    /**
     * The argument of an annotated type, the annotations of the type and of its type arguments included where
     * they can be read.
     *
     * @param type The annotated type
     * @return The argument
     */
    Argument<?> argumentOf(AnnotatedType type);

    /**
     * The annotation metadata of an annotated element, where it can be read.
     *
     * @param element The element
     * @return The metadata, empty when it cannot be read
     */
    AnnotationMetadata annotationMetadataOf(AnnotatedElement element);

    /**
     * One constraint a constraint type composes.
     *
     * @param type  The composing constraint type
     * @param value The occurrence of the composing constraint, with the overrides, the groups and the payload of
     *              the composed one applied and the validators of the constraint resolved
     */
    record ComposingConstraint(Class<? extends Annotation> type, AnnotationValue<Annotation> value) {
    }

    /**
     * Loads the support once.
     */
    final class Holder {

        private static final ReflectionSupport INSTANCE = SoftServiceLoader.load(ReflectionSupport.class)
            .firstAvailable()
            .orElseGet(CompileTimeSupport::new);

        private Holder() {
        }
    }
}
