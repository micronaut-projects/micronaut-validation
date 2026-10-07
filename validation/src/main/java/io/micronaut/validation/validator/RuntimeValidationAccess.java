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

import io.micronaut.core.annotation.AnnotationBuilder;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;

/** Optional runtime discovery and construction, supplied by the reflection companion. */
@Internal
public interface RuntimeValidationAccess {
    /**
     * @param baseName The bundle name
     * @param locale The requested locale
     * @param loader The application's loader
     * @return The class-based resource bundle
     */
    default ResourceBundle messageBundle(
            String baseName, Locale locale, ClassLoader loader) {
        throw new ValidationException(
                "Class-based validation message bundles require generated constructors or"
                        + " micronaut-validation-reflection: "
                        + baseName);
    }

    /**
     * @param name The class name
     * @param loader The application's loader
     * @return The optional reflective class lookup
     * @since 5.3.0
     */
    default Class<?> classForName(String name, ClassLoader loader) {
        throw new ValidationException(
                "No generated class reference for "
                        + name
                        + ": add"
                        + " micronaut-validation-reflection");
    }

    /**
     * @param type The array component
     * @param size The size
     * @return An optional reflective array
     */
    default Object[] array(Class<?> type, int size) {
        throw new ValidationException(
                "No generated array factory for "
                        + type.getName()
                        + ": add micronaut-validation-reflection");
    }

    /**
     * @param type The annotation type
     * @return Its members when the optional provider permits
     * discovery
     */
    default Map<String, AnnotationMember> annotationMembers(
            Class<? extends Annotation> type) {
        throw new ValidationException(
                "No generated annotation member metadata for "
                        + type.getName()
                        + ": add micronaut-validation-reflection");
    }

    /**
     * @param type The enum type
     * @return Optional reflective enum constants
     * @since 5.3.0
     */
    default List<Enum<?>> enumConstants(Class<?> type) {
        throw new ValidationException(
                "No generated enum constants for "
                        + type.getName()
                        + ": add"
                        + " micronaut-validation-reflection");
    }

    /**
     * Converts a caller-supplied generic signature when this provider has that capability.
     *
     * @param type The supplied type
     * @return The argument
     * @since 5.3.0
     */
    default Argument<?> argumentOf(Type type) {
        if (type instanceof Class<?> clazz) {
            return Argument.of(clazz);
        }
        throw new ValidationException(
                "Generic signature conversion requires micronaut-validation-reflection: " + type);
    }

    /**
     * @param type The annotation interface
     * @param value The occurrence
     * @return Typed runtime attributes, or null when using ordinary metadata
     */
    default @Nullable Map<String, Object> annotationAttributes(Class<? extends Annotation> type, AnnotationValue<?> value) {
        if (value.stringValues(ValidationAnnotationUtil.RUNTIME_ATTRIBUTES).length > 0) {
            throw new ValidationException("Typed attributes of " + type.getName()
                + " require micronaut-validation-reflection: "
                + String.join(", ", value.stringValues(ValidationAnnotationUtil.RUNTIME_ATTRIBUTES)));
        }
        return null;
    }

    /**
     * Optional fallback for an annotation not described by generated providers.
     *
     * @param type The annotation interface
     * @param value The attributes
     * @param <T> The annotation type
     * @return The annotation implementation
     * @since 5.3.0
     */
    @SuppressWarnings("unchecked")
    default <T extends Annotation> T annotation(Class<T> type, AnnotationValue<?> value) {
        // the constraints of the specification and of this module have a builder generated with this module;
        // one of the application has one where it registers it
        ClassLoader own = RuntimeValidationAccess.class.getClassLoader();
        var builder = AnnotationBuilder.find(type, own);
        if (builder.isEmpty() && type.getClassLoader() != null && type.getClassLoader() != own) {
            builder = AnnotationBuilder.find(type, type.getClassLoader());
        }
        if (builder.isPresent()) {
            return builder.get().build((AnnotationValue<T>) value, ConversionService.SHARED);
        }
        throw new ValidationException(
                "No generated annotation implementation for "
                        + type.getName()
                        + ": add"
                        + " micronaut-validation-reflection");
    }

    /**
     * Optional construction of a bootstrap class missing generated metadata.
     *
     * @param name The class name
     * @param classLoader The application loader
     * @return The instance
     * @since 5.3.0
     */
    default @Nullable Object instantiate(String name, ClassLoader classLoader) {
        throw new ValidationException(
                "No generated constructor for "
                        + name
                        + ": compile with introspection metadata or add"
                        + " micronaut-validation-reflection");
    }
}
