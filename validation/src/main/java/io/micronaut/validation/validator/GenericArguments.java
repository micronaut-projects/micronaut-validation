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

import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The arguments a generic signature declares, read from the {@link Type}s the specification API hands over:
 * what a value extractor extracts, what a constraint validator validates, which type argument of a container
 * a type binds. Only the structure of the signature is read - the types and their type arguments - and of the
 * annotations only the two the value extractor contract itself defines, {@link ExtractedValue} and
 * {@link UnwrapByDefault}, which the API hands over on the type of an extractor instance: every other
 * annotation of a type argument is what the generated metadata, or the reflection module where it is present,
 * describes.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class GenericArguments {

    private GenericArguments() {
    }

    /**
     * The argument of a type: the class it erases to, with the type arguments it binds, an unbound variable
     * standing for its erasure.
     *
     * @param type The type
     * @return The argument
     */
    public static Argument<?> of(Type type) {
        return of(type, Map.of());
    }

    /**
     * The argument of an annotated type: its structure, and the {@link ExtractedValue} and
     * {@link UnwrapByDefault} annotations of the type and of its type arguments.
     *
     * @param type The annotated type
     * @return The argument
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Argument<?> of(AnnotatedType type) {
        Argument<?> structure = of(type.getType());
        Argument<?>[] typeParameters = structure.getTypeParameters();
        if (type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] annotatedArguments = parameterized.getAnnotatedActualTypeArguments();
            typeParameters = new Argument[annotatedArguments.length];
            for (int i = 0; i < annotatedArguments.length; i++) {
                typeParameters[i] = of(annotatedArguments[i]);
            }
        }
        return Argument.of((Class) structure.getType(), structure.getName(), metadataOf(type), typeParameters);
    }

    /**
     * The {@link ExtractedValue} and {@link UnwrapByDefault} annotations of an element, as metadata.
     *
     * @param element The element
     * @return The metadata, empty when the element carries neither
     */
    public static AnnotationMetadata metadataOf(AnnotatedElement element) {
        MutableAnnotationMetadata metadata = null;
        for (Annotation annotation : element.getAnnotations()) {
            if (annotation instanceof ExtractedValue extractedValue) {
                metadata = metadata == null ? new MutableAnnotationMetadata() : metadata;
                metadata.addDeclaredAnnotation(ExtractedValue.class.getName(), Map.of("type", new AnnotationClassValue<>(extractedValue.type())));
            } else if (annotation instanceof UnwrapByDefault) {
                metadata = metadata == null ? new MutableAnnotationMetadata() : metadata;
                metadata.addDeclaredAnnotation(UnwrapByDefault.class.getName(), Map.of());
            }
        }
        return metadata == null ? AnnotationMetadata.EMPTY_METADATA : metadata;
    }

    /**
     * The argument of a super type as a type binds it: {@code ConstraintValidator<Size, CharSequence>} for a
     * validator declaring {@code implements ConstraintValidator<Size, CharSequence>}, through every
     * intermediate class and interface, the variables of each resolved to what its sub type binds them to.
     *
     * @param type      The type
     * @param superType The super class or interface to resolve
     * @param <T>       The super type
     * @return The argument, {@code null} when the type does not extend or implement the super type
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> Argument<T> resolveGenericToArgument(Class<?> type, Class<T> superType) {
        if (!superType.isAssignableFrom(type)) {
            return null;
        }
        if (type == superType) {
            return (Argument<T>) of(type);
        }
        return (Argument<T>) resolve(type, superType, Map.of(), new HashSet<>());
    }

    @Nullable
    private static Argument<?> resolve(Class<?> type, Class<?> superType, Map<TypeVariable<?>, Argument<?>> bindings, Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return null;
        }
        Type[] candidates = Arrays.copyOf(type.getGenericInterfaces(), type.getGenericInterfaces().length + 1); // reflection: the generic signature of a type the API hands over
        candidates[candidates.length - 1] = type.getGenericSuperclass(); // reflection: the same, its super class
        for (Type candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Class<?> raw = rawOf(candidate);
            if (raw == null || !superType.isAssignableFrom(raw)) {
                continue;
            }
            Argument<?> argument = of(candidate, bindings);
            if (raw == superType) {
                return argument;
            }
            Argument<?> resolved = resolve(raw, superType, bindingsOf(raw, argument), visited);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private static Map<TypeVariable<?>, Argument<?>> bindingsOf(Class<?> type, Argument<?> argument) {
        TypeVariable<?>[] variables = type.getTypeParameters();
        Argument<?>[] typeParameters = argument.getTypeParameters();
        Map<TypeVariable<?>, Argument<?>> bindings = new HashMap<>();
        for (int i = 0; i < variables.length && i < typeParameters.length; i++) {
            bindings.put(variables[i], typeParameters[i]);
        }
        return bindings;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<?> of(Type type, Map<TypeVariable<?>, Argument<?>> bindings) {
        if (type instanceof Class<?> clazz) {
            return Argument.of(clazz);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type[] actual = parameterized.getActualTypeArguments();
            Argument<?>[] typeParameters = new Argument[actual.length];
            for (int i = 0; i < actual.length; i++) {
                typeParameters[i] = of(actual[i], bindings);
            }
            Class<?> raw = rawOf(parameterized);
            return Argument.of((Class) (raw == null ? Object.class : raw), typeParameters);
        }
        if (type instanceof TypeVariable<?> variable) {
            Argument<?> bound = bindings.get(variable);
            if (bound != null) {
                return bound;
            }
            Type[] bounds = variable.getBounds();
            return Argument.ofTypeVariable((Class) (bounds.length == 0 ? Object.class : erasureOf(bounds[0])), null, variable.getName());
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return of(upper.length == 0 ? Object.class : upper[0], bindings);
        }
        if (type instanceof GenericArrayType array) {
            Class<?> component = erasureOf(array.getGenericComponentType());
            return Argument.of(component.arrayType());
        }
        return Argument.OBJECT_ARGUMENT;
    }

    private static Class<?> erasureOf(Type type) {
        Class<?> raw = rawOf(type);
        return raw == null ? Object.class : raw;
    }

    @Nullable
    private static Class<?> rawOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof TypeVariable<?> variable) {
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : rawOf(bounds[0]);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 ? Object.class : rawOf(upper[0]);
        }
        if (type instanceof GenericArrayType array) {
            return erasureOf(array.getGenericComponentType()).arrayType();
        }
        return null;
    }
}
