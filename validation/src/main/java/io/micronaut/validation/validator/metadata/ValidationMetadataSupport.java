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
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.annotation.ValidatedElement;
import io.micronaut.validation.validator.ExecutableHierarchy;
import io.micronaut.validation.validator.ReflectionSupport;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves standard metadata before requesting optional runtime discovery. */
@Internal
public final class ValidationMetadataSupport {
    /** Internal hierarchy entries stored in ordinary annotation metadata. */
    public static final String HIERARCHY = "io.micronaut.validation.internal.Hierarchy";

    /** Internal declaration-owned type-use annotations. */
    public static final String TYPE_USE = "io.micronaut.validation.internal.TypeUse";

    /** Parameters before the existing visitor supplements inherited type-use annotations. */
    public static final String DECLARED_PARAMETERS = "io.micronaut.validation.internal.DeclaredParameters";

    private static final List<Class<? extends Annotation>> STANDARD_CONSTRAINTS = List.of(
        AssertFalse.class, AssertTrue.class,
        DecimalMax.class, DecimalMin.class,
        Digits.class, Email.class,
        Future.class, FutureOrPresent.class,
        Max.class, Min.class,
        Negative.class, NegativeOrZero.class,
        NotBlank.class, NotEmpty.class,
        NotNull.class, Null.class,
        Past.class, PastOrPresent.class,
        Pattern.class, Positive.class,
        PositiveOrZero.class, Size.class);

    private ValidationMetadataSupport() { }

    /**
     * @param name The standard constraint name
     * @return Its API class literal, or null
     */
    public static @Nullable Class<? extends Annotation> standardConstraint(String name) {
        for (var type : STANDARD_CONSTRAINTS) {
            if (type.getName().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * @param argument The structural argument
     * @param declaration Its declaration metadata
     * @return The argument with declaration-owned type-use annotations
     */
    public static Argument<?> argument(Argument<?> argument, AnnotationMetadata declaration) {
        var typeUse = declaration.getAnnotation(TYPE_USE);
        return typeUse == null ? argument : typeUse(argument, typeUse);
    }

    /**
     * @param arguments The merged parameter arguments
     * @param declaration The owning method's metadata
     * @return Parameters carrying only declaration-owned annotations
     */
    public static Argument<?>[] declaredArguments(Argument<?>[] arguments, AnnotationMetadata declaration) {
        var snapshot = declaration.getAnnotation(DECLARED_PARAMETERS);
        if (snapshot == null) {
            return arguments;
        }
        var annotations = snapshot.getAnnotations("arguments");
        Argument<?>[] result = arguments.clone();
        for (int i = 0; i < result.length && i < annotations.size(); i++) {
            result[i] = typeUse(withoutAnnotations(arguments[i]), annotations.get(i));
        }
        return result;
    }

    private static Argument<?> withoutAnnotations(Argument<?> argument) {
        return ExecutableHierarchy.copyArgument(argument, AnnotationMetadata.EMPTY_METADATA,
            Arrays.stream(argument.getTypeParameters()).map(ValidationMetadataSupport::withoutAnnotations).toArray(Argument<?>[]::new));
    }

    /**
     * @param introspection The generated description
     * @param superType The generic declaration
     * @return Core arguments with their declaration-owned annotations
     */
    public static List<Argument<?>> typeArguments(BeanIntrospection<?> introspection, Class<?> superType) {
        var arguments = introspection.getTypeArguments(superType);
        var hierarchy = introspection.getAnnotationMetadata().getAnnotation(HIERARCHY);
        if (hierarchy == null) {
            return arguments;
        }
        for (var entry : hierarchy.getAnnotations("arguments")) {
            if (entry.stringValue("type").orElse("").equals(superType.getName())) {
                var annotations = entry.getAnnotations("arguments");
                var result = new ArrayList<Argument<?>>(arguments.size());
                for (int i = 0; i < arguments.size(); i++) {
                    result.add(i < annotations.size() ? typeUse(arguments.get(i), annotations.get(i)) : arguments.get(i));
                }
                return List.copyOf(result);
            }
        }
        return arguments;
    }

    private static Argument<?> typeUse(Argument<?> argument, AnnotationValue<?> typeUse) {
        var metadata = new MutableAnnotationMetadata();
        for (var annotation : typeUse.getAnnotations("annotations")) {
            var values = new LinkedHashMap<>(annotation.getValues());
            if (annotation.getStereotypes() != null) {
                values.put(AnnotationUtil.STEREOTYPES_MEMBER, annotation.getStereotypes());
            }
            metadata.addDeclaredAnnotation(annotation.getAnnotationName(), values);
            if (annotation.getDefaultValues() != null) {
                metadata.addDefaultAnnotationValues(annotation.getAnnotationName(), annotation.getDefaultValues());
            }
            if (annotation.getStereotypes() != null) {
                for (var stereotype : annotation.getStereotypes()) {
                    metadata.addDeclaredStereotype(List.of(annotation.getAnnotationName()), stereotype.getAnnotationName(), stereotype.getValues());
                }
            }
        }
        Argument<?>[] parameters = argument.getTypeParameters().clone();
        var nested = typeUse.getAnnotations("arguments");
        for (int i = 0; i < parameters.length && i < nested.size(); i++) {
            parameters[i] = typeUse(parameters[i], nested.get(i));
        }
        var merged = ConfiguredMetadata.merge(argument.getAnnotationMetadata(), metadata);
        if (merged.hasStereotype(Constraint.class) || merged.hasAnnotation(Valid.class)
            || Arrays.stream(parameters).anyMatch(parameter -> parameter.getAnnotationMetadata().hasAnnotation(ValidatedElement.class))) {
            metadata.addDeclaredAnnotation(ValidatedElement.class.getName(), Map.of());
            merged = ConfiguredMetadata.merge(argument.getAnnotationMetadata(), metadata);
        }
        return ExecutableHierarchy.copyArgument(argument, merged, parameters);
    }

    /**
     * @param metadata The owning introspection metadata
     * @param name The method name
     * @param parameters The signature
     * @return Whether the described type declares this method
     */
    public static boolean declares(AnnotationMetadata metadata, String name, Class<?>[] parameters) {
        var hierarchy = metadata.getAnnotation(HIERARCHY);
        return hierarchy == null || hierarchy.getAnnotations("methods").stream()
            .anyMatch(method -> method.stringValue("name").orElse("").equals(name)
                && Arrays.equals(method.classValues("parameters"), parameters));
    }

    /**
     * @param metadata The owning introspection metadata
     * @param type The hierarchy entry to find
     * @return Its ordinary annotation value
     */
    public static @Nullable AnnotationValue<Annotation> hierarchy(AnnotationMetadata metadata, Class<?> type) {
        var value = metadata.getAnnotation(HIERARCHY);
        if (value != null) {
            for (var entry : value.getAnnotations("types")) {
                if (entry.classValue("type").orElse(null) == type) {
                    return entry;
                }
            }
        }
        return null;
    }

    /**
     * @param type The introspected type
     * @return Its hierarchy metadata, if compiled
     */
    public static @Nullable AnnotationValue<Annotation> hierarchy(Class<?> type) {
        return BeanIntrospector.forClassLoader(type.getClassLoader()).findIntrospection(type)
            .map(introspection -> hierarchy(introspection.getAnnotationMetadata(), type)).orElse(null);
    }

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The annotation interface
     * @param value The attributes
     * @param <T> The annotation type
     * @return Its optional runtime implementation
     */
    public static <T extends Annotation> T create(ReflectionSupport reflectionSupport, Class<T> type, AnnotationValue<?> value) {
        return reflectionSupport.annotation(type, value);
    }

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param name The type name
     * @param loader The application loader
     * @return An introspected class or optional runtime lookup
     */
    public static Class<?> type(ReflectionSupport reflectionSupport, String name, ClassLoader loader) {
        var standard = standardConstraint(name);
        if (standard != null) {
            return standard;
        }
        for (var reference : reflectionSupport.introspector().findIntrospections(reference -> reference.getName().equals(name))) {
            if (reference.getBeanType().getName().equals(name)) {
                return reference.getBeanType();
            }
        }
        return reflectionSupport.classForName(name, loader);
    }

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The annotation interface
     * @return Its optional member definitions
     */
    public static Map<String, AnnotationMember> annotationMembers(ReflectionSupport reflectionSupport, Class<? extends Annotation> type) {
        if (standardConstraint(type.getName()) != type) {
            return reflectionSupport.annotationMembers(type);
        }
        var members = new LinkedHashMap<String, AnnotationMember>();
        members.put("message", new AnnotationMember(String.class, false));
        members.put("groups", new AnnotationMember(Class[].class, false));
        members.put("payload", new AnnotationMember(Class[].class, false));
        switch (type.getName()) {
            case "jakarta.validation.constraints.Min", "jakarta.validation.constraints.Max" -> members.put("value", new AnnotationMember(long.class, true));
            case "jakarta.validation.constraints.DecimalMin", "jakarta.validation.constraints.DecimalMax" -> {
                members.put("value", new AnnotationMember(String.class, true));
                members.put("inclusive", new AnnotationMember(boolean.class, false));
            }
            case "jakarta.validation.constraints.Digits" -> {
                members.put("integer", new AnnotationMember(int.class, true));
                members.put("fraction", new AnnotationMember(int.class, true));
            }
            case "jakarta.validation.constraints.Size" -> {
                members.put("min", new AnnotationMember(int.class, false));
                members.put("max", new AnnotationMember(int.class, false));
            }
            case "jakarta.validation.constraints.Pattern", "jakarta.validation.constraints.Email" -> {
                members.put("regexp", new AnnotationMember(String.class, type == Pattern.class));
                members.put("flags", new AnnotationMember(Pattern.Flag[].class, false));
            }
            default -> { }
        }
        return Map.copyOf(members);
    }

    /**
     * @param type The annotation interface
     * @return Standard Jakarta defaults, or an empty map for application annotations
     */
    public static Map<CharSequence, Object> standardDefaults(Class<?> type) {
        if (standardConstraint(type.getName()) != type) {
            return Map.of();
        }
        var defaults = new LinkedHashMap<CharSequence, Object>();
        defaults.put("message", "{" + type.getName() + ".message}");
        defaults.put("groups", new Class<?>[0]);
        defaults.put("payload", new Class<?>[0]);
        switch (type.getName()) {
            case "jakarta.validation.constraints.DecimalMin", "jakarta.validation.constraints.DecimalMax" -> defaults.put("inclusive", true);
            case "jakarta.validation.constraints.Size" -> {
                defaults.put("min", 0); defaults.put("max", Integer.MAX_VALUE);
            }
            case "jakarta.validation.constraints.Email" -> {
                defaults.put("regexp", ".*"); defaults.put("flags", new Pattern.Flag[0]);
            }
            case "jakarta.validation.constraints.Pattern" -> defaults.put("flags", new Pattern.Flag[0]);
            default -> { }
        }
        return defaults;
    }

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The enum type
     * @return Its optional runtime constants
     */
    public static List<Enum<?>> enumConstants(ReflectionSupport reflectionSupport, Class<?> type) {
        if (type == Pattern.Flag.class) {
            return List.of(Pattern.Flag.values());
        }
        return reflectionSupport.enumConstants(type);
    }

    /**
     * @param reflectionSupport The access provider captured by the validator factory
     * @param type The array component
     * @param size The requested size
     * @return Its optional typed array
     */
    public static Object[] typedArray(ReflectionSupport reflectionSupport, Class<?> type, int size) {
        if (type == Pattern.Flag.class) {
            return new Pattern.Flag[size];
        }
        return reflectionSupport.array(type, size);
    }
}
