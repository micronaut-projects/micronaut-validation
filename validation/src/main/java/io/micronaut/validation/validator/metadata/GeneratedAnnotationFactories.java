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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.UsedByGeneratedCode;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Loads generated annotation providers without retaining application class loaders.
 *
 * @since 5.3.0
 */
@Internal
@NullMarked
public final class GeneratedAnnotationFactories {
    private static final WeakHashMap<ClassLoader, WeakReference<List<GeneratedAnnotationProvider>>>
            PROVIDERS = new WeakHashMap<>();

    private static final ClassValue<Optional<GeneratedAnnotationProvider>> DECLARATION_PROVIDERS = new ClassValue<>() {
        @Override
        protected Optional<GeneratedAnnotationProvider> computeValue(Class<?> type) {
            var embedded = BeanIntrospector.forClassLoader(type.getClassLoader()).findIntrospection(type)
                .map(introspection -> embedded(introspection.getAnnotationMetadata()));
            if (embedded.isPresent()) {
                return embedded;
            }
            return providers(type.getClassLoader()).stream().filter(provider -> provider.ownerType() == type).findFirst();
        }
    };

    private GeneratedAnnotationFactories() {
    }

    private static synchronized List<GeneratedAnnotationProvider> providers() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = GeneratedAnnotationFactories.class.getClassLoader();
        }
        return providers(loader);
    }

    private static synchronized List<GeneratedAnnotationProvider> providers(@Nullable ClassLoader loader) {
        if (loader == null) {
            loader = GeneratedAnnotationFactories.class.getClassLoader();
        }
        WeakReference<List<GeneratedAnnotationProvider>> reference = PROVIDERS.get(loader);
        List<GeneratedAnnotationProvider> providers = reference == null ? null : reference.get();
        if (providers == null) {
            providers = List.copyOf(SoftServiceLoader.load(GeneratedAnnotationProvider.class, loader).collectAll());
            PROVIDERS.put(loader, new WeakReference<>(providers));
        }
        return providers;
    }

    /**
     * Finds metadata for one executable declaration.
     *
     * @param type The declaration owner
     * @param name The method name
     * @param parameters The erased signature
     * @return The generated method declaration, or null
     */
    public static @Nullable ValidationDeclaration methodDeclaration(Class<?> type, String name, Class<?>[] parameters) {
        var provider = DECLARATION_PROVIDERS.get(type).orElse(null);
        return provider == null ? null : provider.methodDeclaration(name, parameters);
    }

    /**
     * Preserves declaration-specific type annotations on the property argument.
     *
     * @param beanType The owning introspected type
     * @param member The exact field or getter declaration
     * @return The argument with occurrence-specific type annotations
     */
    public static Argument<?> propertyArgument(Class<?> beanType, BeanPropertyMember<?, ?> member) {
        var provider = DECLARATION_PROVIDERS.get(beanType).orElse(null);
        var argument = member.asArgument();
        return provider == null
                ? argument
                : provider.propertyArgument(
                        member.getDeclaringType().getName()
                                + ":"
                                + member.getElementType()
                                + ":"
                                + member.getName(),
                        argument);
    }

    /**
     * Resolves the generated arguments of an inherited type.
     *
     * @param introspection The generated introspection
     * @param superType The generic declaration
     * @return The structural arguments with declaration-owned type annotations
     */
    public static List<Argument<?>> typeArguments(
            BeanIntrospection<?> introspection, Class<?> superType) {
        var arguments = introspection.getTypeArguments(superType);
        var provider = embedded(introspection.getAnnotationMetadata());
        if (provider == null) {
            return arguments;
        }
        var supplemented = new ArrayList<Argument<?>>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            supplemented.add(
                    provider.propertyArgument(
                            "SUPER:" + superType.getName() + ":" + i, arguments.get(i)));
        }
        return List.copyOf(supplemented);
    }

    /**
     * Used by generated providers to copy argument structure and merge annotations for one
     * occurrence.
     *
     * @param argument The original argument
     * @param metadata The declaration's type annotations
     * @param parameters Its supplemented nested arguments
     * @return The structural copy
     */
    @UsedByGeneratedCode
    public static Argument<?> typeUse(
            Argument<?> argument, AnnotationMetadata metadata, Argument<?>[] parameters) {
        return ExecutableHierarchy.copyArgument(
                argument,
                ExecutableHierarchy.mergeMetadata(
                        List.of(argument.getAnnotationMetadata(), metadata)),
                parameters.length == 0 ? argument.getTypeParameters() : parameters);
    }

    /**
     * Creates a concrete annotation from generated member metadata.
     *
     * @param type The annotation interface
     * @param value The annotation attributes
     * @param <T> The annotation type
     * @return The concrete annotation
     */
    public static <T extends Annotation> T create(Class<T> type, AnnotationValue<?> value) {
        for (GeneratedAnnotationProvider provider : providers()) {
            Annotation annotation = provider.create(value);
            if (annotation != null) {
                return type.cast(annotation);
            }
        }
        return ReflectionSupport.get().annotation(type, value);
    }

    /**
     * Reads typed annotation attributes from generated metadata.
     *
     * @param value The occurrence
     * @return Typed Jakarta attributes, or null
     */
    public static @Nullable Map<String, Object> attributes(AnnotationValue<?> value) {
        for (GeneratedAnnotationProvider provider : providers()) {
            var attributes = provider.attributes(value);
            if (attributes != null) {
                return attributes;
            }
        }
        return null;
    }

    /**
     * Checks or retrieves generated constraint definition metadata.
     *
     * @param type The constraint type
     * @return Its generated definition, or null
     */
    public static @Nullable GeneratedConstraintDefinition definition(Class<?> type) {
        return definition(type.getName());
    }

    /**
     * Checks or retrieves generated constraint definition metadata.
     *
     * @param name The constraint name
     * @return Its generated definition, or null
     */
    public static @Nullable GeneratedConstraintDefinition definition(String name) {
        for (GeneratedAnnotationProvider provider : providers()) {
            var definition = provider.definition(name);
            if (definition != null) {
                return definition;
            }
        }
        return null;
    }

    /**
     * Resolves a generated type reference.
     *
     * @param name The name
     * @param loader The loader
     * @return The generated class or optional lookup
     */
    public static Class<?> type(String name, ClassLoader loader) {
        for (var reference :
                BeanIntrospector.forClassLoader(loader).findIntrospections(
                        reference -> reference.getName().equals(name))) {
            if (reference.getBeanType().getName().equals(name)) {
                return reference.getBeanType();
            }
        }
        for (GeneratedAnnotationProvider provider : providers()) {
            Class<?> type = provider.type(name);
            if (type != null) {
                return type;
            }
        }
        return ReflectionSupport.get().classForName(name, loader);
    }

    /**
     * Retrieves the declared annotation members.
     *
     * @param type The annotation
     * @return Its generated or optional member definitions
     */
    public static Map<String, AnnotationMember> annotationMembers(
            Class<? extends Annotation> type) {
        for (GeneratedAnnotationProvider provider : providers()) {
            var members = provider.annotationMembers(type.getName());
            if (members != null) {
                return members;
            }
        }
        return ReflectionSupport.get().annotationMembers(type);
    }

    /**
     * Retrieves generated enum constants without reflective discovery.
     *
     * @param type The enum interface
     * @return Its compile-time constants, or the optional provider's constants
     */
    public static List<Enum<?>> enumConstants(Class<?> type) {
        for (GeneratedAnnotationProvider provider : providers()) {
            var constants = provider.enumConstants(type.getName());
            if (constants != null) {
                return constants;
            }
        }
        return ReflectionSupport.get().enumConstants(type);
    }

    /**
     * Allocates an array with the generated component type.
     *
     * @param type The component type
     * @param size The array size
     * @return A concrete generated array
     */
    public static Object[] typedArray(Class<?> type, int size) {
        for (GeneratedAnnotationProvider provider : providers()) {
            Object[] array = provider.array(type.getName(), size);
            if (array != null) {
                return array;
            }
        }
        return ReflectionSupport.get().array(type, size);
    }

    /**
     * Allocates an array using generated component metadata.
     *
     * @param type The annotation component type
     * @param size The array size
     * @return A generated array with the requested component type
     */
    public static Annotation[] array(Class<? extends Annotation> type, int size) {
        for (GeneratedAnnotationProvider provider : providers()) {
            Object[] array = provider.array(type.getName(), size);
            if (array != null) {
                return (Annotation[]) array;
            }
        }
        return (Annotation[]) ReflectionSupport.get().array(type, size);
    }

    /**
     * Finds the provider embedded in generated metadata.
     *
     * @param metadata The generated introspection metadata
     * @return Its directly instantiated provider, or null
     */
    public static @Nullable GeneratedAnnotationProvider embedded(AnnotationMetadata metadata) {
        var annotation = metadata.getAnnotation(ValidationMetadata.class);
        if (annotation == null) {
            return null;
        }
        for (var value : annotation.annotationClassValues("provider")) {
            Object instance = value.getInstance().orElse(null);
            if (instance instanceof GeneratedAnnotationProvider provider) {
                return provider;
            }
        }
        return null;
    }

    /**
     * Retrieves generated hierarchy and group metadata.
     *
     * @param type The type
     * @return Its generated hierarchy and group metadata, or null
     */
    public static @Nullable ValidationTypeMetadata typeMetadata(Class<?> type) {
        var embedded = DECLARATION_PROVIDERS.get(type).orElse(null);
        if (embedded != null) {
            return embedded.typeMetadata(type.getName());
        }
        for (GeneratedAnnotationProvider provider : providers(type.getClassLoader())) {
            ValidationTypeMetadata metadata = provider.typeMetadata(type.getName());
            if (metadata != null) {
                return metadata;
            }
        }
        return null;
    }
}
