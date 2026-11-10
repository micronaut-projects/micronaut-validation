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
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.ReflectionSupport;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.util.Arrays;
import java.util.List;

/**
 * A declaration resolved from generated metadata, with optional access to its own value.
 *
 * @param declaringType The declaration owner
 * @param name The declared name
 * @param argument The structural type and its annotations
 * @param metadata The declaration annotations
 * @param parameters The executable arguments
 * @param reader The declaration-specific value access, {@link Reader#NONE} for an executable
 * @since 5.3.0
 */
@Internal
public record ValidationDeclaration(
        Class<?> declaringType,
        String name,
        Argument<?> argument,
        AnnotationMetadata metadata,
        List<Argument<?>> parameters,
        Reader reader)
        implements AnnotationMetadataProvider {
    public ValidationDeclaration {
        parameters = List.copyOf(parameters);
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return metadata;
    }

    /**
     * Reads this declaration, preserving the access policy of its provider.
     *
     * @param bean The instance
     * @return The value
     */
    public @Nullable Object read(Object bean) {
        return reader.read(bean);
    }

    /**
     * Finds exactly the declaration requested, without substituting another property member.
     *
     * @param reflectionSupport The access provider captured by the validator factory
     * @param introspection The introspection of the declaring type
     * @param kind The kind of declaration
     * @param name The declared name
     * @param parameterTypes The erased signature of an executable
     * @return The declaration, or null when absent
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static @Nullable ValidationDeclaration generated(ReflectionSupport reflectionSupport,
            BeanIntrospection<?> introspection,
            Kind kind,
            String name,
            List<Class<?>> parameterTypes) {
        return switch (kind) {
            case FIELD, GETTER -> {
                var property = introspection.getProperty(name).orElse(null);
                if (property == null) {
                    yield null;
                }
                ElementType elementType = kind == Kind.FIELD ? ElementType.FIELD : ElementType.METHOD;
                for (var member : property.getMembers()) {
                    if (member.isReadable() && member.getElementType() == elementType) {
                        yield new ValidationDeclaration(
                                member.getDeclaringType(),
                                member.getName(),
                                member.asArgument(),
                                member.getAnnotationMetadata(),
                                List.of(),
                                bean -> reflectionSupport.readMember((BeanPropertyMember) member, bean));
                    }
                }
                yield null;
            }
            case CONSTRUCTOR -> {
                for (var constructor : introspection.getConstructors()) {
                    if (Arrays.asList(Argument.toClassArray(constructor.getArguments())).equals(parameterTypes)) {
                        yield new ValidationDeclaration(
                                introspection.getBeanType(),
                                name,
                                Argument.of(introspection.getBeanType()),
                                constructor.getAnnotationMetadata(),
                                List.of(constructor.getArguments()),
                                Reader.NONE);
                    }
                }
                yield null;
            }
            case METHOD -> {
                for (var method : introspection.getBeanMethods()) {
                    if (method.getName().equals(name)
                            && Arrays.asList(Argument.toClassArray(method.getArguments())).equals(parameterTypes)) {
                        yield new ValidationDeclaration(
                                method.getDeclaringType(),
                                name,
                                method.getReturnType().asArgument(),
                                method.getDeclaredMethodAnnotationMetadata(),
                                List.of(method.getArguments()),
                                Reader.NONE);
                    }
                }
                yield null;
            }
        };
    }

    /**
     * The kind of a declaration a mapping names.
     */
    public enum Kind {
        /** A field. */
        FIELD,
        /** The getter of a property. */
        GETTER,
        /** A constructor. */
        CONSTRUCTOR,
        /** A method. */
        METHOD
    }

    /** Provider-owned access to a single declaration. */
    @FunctionalInterface
    public interface Reader {
        /**
         * The access of a declaration that holds no value of its own: an executable.
         */
        Reader NONE = bean -> {
            throw new UnsupportedOperationException("An executable declaration is not readable");
        };

        /**
         * Reads the value through the generated declaration accessor.
         *
         * @param bean The instance
         * @return The value
         */
        @Nullable Object read(Object bean);
    }
}
