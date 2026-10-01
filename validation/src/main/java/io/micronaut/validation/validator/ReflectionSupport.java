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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.metadata.AnnotationMember;
import io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationField;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * What the validator reads through reflection when the {@code micronaut-validation-reflection}
 * module is present, and through the generated metadata alone when it is not.
 *
 * <p>The validator describes a bean, a constraint and an executable through the metadata the
 * annotation processors generate. The Jakarta Validation API also names an executable by its {@link
 * Method} or {@link Constructor}, hands over a value extractor as an instance and composes a
 * constraint from the annotations of its type: where the generated metadata does not describe what
 * such a call names, the reflection module reads it from the class itself. This is the seam between
 * the two, loaded as a service; without an implementation the validator answers from the generated
 * metadata and nothing else.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public interface ReflectionSupport {

    /**
     * The support in force: the first one registered as a service, else the one reading the
     * generated metadata only.
     *
     * @return The support
     */
    static ReflectionSupport get() {
        return ReflectionSupportServiceDiscovery.get();
    }

    /**
     * Supplements generated introspections when the optional provider permits it.
     *
     * @param introspector The generated introspector
     * @return The introspector used by bootstrap
     * @since 5.3.0
     */
    default BeanIntrospector supplemented(BeanIntrospector introspector) {
        return introspector;
    }

    /**
     * @param introspector The introspector
     * @return Whether it is already supplemented
     * @since 5.3.0
     */
    default boolean isSupplemented(BeanIntrospector introspector) {
        return false;
    }

    /**
     * @return Whether optional reflection is enabled
     * @since 5.3.0
     */
    default boolean isReflectionEnabled() {
        return false;
    }

    /**
     * Reads a declaration through generated access or an explicitly authorized field accessor.
     *
     * @param member The declaration
     * @param bean The instance
     * @param <T> The bean type
     * @return The declared value
     * @since 5.3.0
     */
    default <T> @Nullable Object readMember(BeanPropertyMember<T, ?> member, T bean) {
        if (member.getAnnotationMetadata()
                .booleanValue(ValidationField.class, "reflection")
                .orElse(false)) {
            return AnnotatedFieldAccessor.read(member, bean);
        }
        return member.read(bean);
    }

    /**
     * @param baseName The bundle name
     * @param locale The requested locale
     * @param loader The application's loader
     * @return The class-based resource bundle
     */
    default java.util.ResourceBundle messageBundle(
            String baseName, java.util.Locale locale, ClassLoader loader) {
        throw new jakarta.validation.ValidationException(
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
        throw new jakarta.validation.ValidationException(
                "No generated class reference for "
                        + name
                        + ": compile with micronaut-validation-processor or add"
                        + " micronaut-validation-reflection");
    }

    /**
     * Resolves metadata named by XML without granting runtime lookup to XML itself.
     *
     * @param type The bean type
     * @param kind The declaration kind
     * @param name The declared name
     * @param parameters The erased signature
     * @return The declaration, or null when absent
     */
    default @Nullable ValidationDeclaration declaration(
            Class<?> type, String kind, String name, List<Class<?>> parameters) {
        var introspection = BeanIntrospector.forClassLoader(type.getClassLoader()).findIntrospection(type).orElse(null);
        if (introspection == null) {
            throw new jakarta.validation.ValidationException(
                    "No generated declaration for "
                            + type.getName()
                            + "."
                            + name
                            + ": add micronaut-validation-reflection");
        }
        var declaration = ValidationDeclaration.generated(introspection, kind, name, parameters);
        return declaration == null && kind.equals("method")
            ? io.micronaut.validation.validator.metadata.GeneratedAnnotationFactories.methodDeclaration(type, name, parameters.toArray(Class<?>[]::new))
            : declaration;
    }

    /**
     * @param type The array component
     * @param size The size
     * @return An optional reflective array
     */
    default Object[] array(Class<?> type, int size) {
        throw new jakarta.validation.ValidationException(
                "No generated array factory for "
                        + type.getName()
                        + ": add micronaut-validation-reflection");
    }

    /**
     * @param type The annotation type
     * @return Its members when the optional provider permits
     * discovery
     */
    default java.util.Map<String, AnnotationMember> annotationMembers(
            Class<? extends Annotation> type) {
        throw new jakarta.validation.ValidationException(
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
        throw new jakarta.validation.ValidationException(
                "No generated enum constants for "
                        + type.getName()
                        + ": compile with micronaut-validation-processor or add"
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
        throw new jakarta.validation.ValidationException(
                "Generic signature conversion requires micronaut-validation-reflection: " + type);
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
    default <T extends Annotation> T annotation(Class<T> type, AnnotationValue<?> value) {
        throw new jakarta.validation.ValidationException(
                "No generated annotation implementation for "
                        + type.getName()
                        + ": compile with micronaut-validation-processor or add"
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
        throw new jakarta.validation.ValidationException(
                "No generated constructor for "
                        + name
                        + ": compile with introspection metadata or add"
                        + " micronaut-validation-reflection");
    }

    /**
     * @param type The type
     * @return Its generated interfaces
     * @since 5.3.0
     */
    default List<Class<?>> interfaces(Class<?> type) {
        if (type == Object.class || type == jakarta.validation.groups.Default.class) {
            return List.of();
        }
        var metadata = GeneratedAnnotationFactories.typeMetadata(type);
        if (metadata == null) {
            throw new jakarta.validation.ValidationException(
                    "No generated hierarchy for "
                            + type.getName()
                            + ": add micronaut-validation-reflection or compile the type with"
                            + " micronaut-validation-processor");
        }
        return metadata.interfaces();
    }

    /**
     * @param type The type
     * @return Its superclass
     * @since 5.3.0
     */
    default @Nullable Class<?> superType(Class<?> type) {
        if (type == Object.class) {
            return null;
        }
        var metadata = GeneratedAnnotationFactories.typeMetadata(type);
        if (metadata == null) {
            throw new jakarta.validation.ValidationException(
                    "No generated hierarchy for "
                            + type.getName()
                            + ": add micronaut-validation-reflection or compile the type with"
                            + " micronaut-validation-processor");
        }
        return metadata.superType();
    }

    /**
     * Returns a caller-supplied signature, or requests optional member lookup.
     *
     * @param executable The generated executable
     * @return The Jakarta signature
     * @since 5.3.0
     */
    default Method targetMethod(io.micronaut.inject.MethodReference<?, ?> executable) {
        if (executable instanceof CallerSuppliedExecutable<?, ?> supplied) {
            return supplied.suppliedMethod();
        }
        if (executable instanceof IntrospectedExecutable<?, ?> supplied) {
            return supplied.suppliedMethod();
        }
        throw new jakarta.validation.ValidationException(
                "Runtime method lookup requires micronaut-validation-reflection: "
                        + executable.getDeclaringType().getName()
                        + "."
                        + executable.getMethodName());
    }

    /**
     * The executable method of the method a caller names: the one of the bean definition when the
     * declaring type is a bean, else the one of the bean introspection, else what the
     * implementation can read from the method itself.
     *
     * @param locator The locator of the executable methods of the beans
     * @param introspector The introspector
     * @param method The method
     * @param <T> The declaring type
     * @return The executable method
     * @throws jakarta.validation.ValidationException When nothing describes the method
     */
    <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator, BeanIntrospector introspector, Method method);

    /**
     * The constructor a caller names, with its arguments and annotation metadata: one the
     * introspection describes, else what the implementation can read from the constructor itself.
     *
     * @param introspection The introspection of the declaring type, can be {@code null}
     * @param constructor The constructor
     * @param <T> The declaring type
     * @return The bean constructor
     * @throws jakarta.validation.ValidationException When nothing describes the constructor
     */
    <T> BeanConstructor<T> beanConstructor(@Nullable BeanIntrospection<T> introspection, Constructor<T> constructor);

    /**
     * The hierarchy of an executable: the declarations it overrides or implements, read from the
     * introspections of the super types, and the local one merged with them.
     *
     * @param introspector The introspector of the super types
     * @param local The executable as validated
     * @param name Its name
     * @return The resolved hierarchy
     */
    ExecutableHierarchy.Resolved resolveHierarchy(
            BeanIntrospector introspector, ExecutableHierarchy.Declaration local, String name);

    /**
     * Whether an introspection tells the declarations of a type apart from the ones it inherits:
     * which annotations a method declares itself, and the field and the getters of a property by
     * the type declaring each. A generated introspection merges what the super types declare into
     * its own, so it does not.
     *
     * @param introspection The introspection
     * @return Whether the declarations are separated
     */
    boolean separatesDeclarations(BeanIntrospection<?> introspection);

    /**
     * The constraints a constraint type composes, read from the annotations of the type where the
     * generated metadata retains no tree of them: each with the attributes the composed constraint
     * overrides, its groups and payload, and the validators of the constraint.
     *
     * @param constraintType The composed constraint type
     * @param parentAnnotationValue The occurrence of the composed constraint
     * @return The composing constraints, empty when they cannot be read
     */
    List<ComposingConstraint> composingConstraints(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue);

    /**
     * Checks the rules of a composition that only the declared form of the constraint annotation
     * type can answer: a constraint composed both directly and inside its repeatable container,
     * which the retained tree of the generated metadata flattens into repeated occurrences; an
     * override naming a member the composing constraint does not declare, or an occurrence it does
     * not have, which the tree cannot tell from a member without a default or from the occurrence
     * it does have.
     *
     * @param constraintType The composed constraint type
     * @param parentAnnotationValue The occurrence of the composed constraint
     * @throws jakarta.validation.ConstraintDeclarationException When the declared composition
     * breaks a declaration rule
     * @throws jakarta.validation.ConstraintDefinitionException When it breaks a definition rule
     */
    void checkComposition(Class<? extends Annotation> constraintType, AnnotationValue<? extends Annotation> parentAnnotationValue);

    /**
     * The type a type binds a generic super type's type argument to, with the annotations declared
     * on it: the {@code String} of a {@code class Names implements Iterable<@NotBlank String>} read
     * as an {@code Iterable}. The generated metadata describes what a type declares, not what it
     * binds in a super type it does not restate, so this is read from the class itself.
     *
     * @param declaredType The type as declared
     * @param containerType The generic super type it is read as
     * @param typeArgumentIndex The index of the type argument of that super type
     * @return The argument, {@code null} when the type binds no such argument
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    Argument<?> boundTypeArgument(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex);

    /**
     * Which of a type's own type arguments carries the one a value extractor extracts: a pair
     * declaring {@code implements Map} with its own two arguments the other way round binds them in
     * reverse, so the value a {@code Map} extractor extracts is the first argument of the pair
     * rather than the second.
     *
     * @param declaredType The type as declared
     * @param containerType The container type the extractor is written for
     * @param typeArgumentIndex The index of the type argument the extractor extracts
     * @return The index among the type arguments of the declared type
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex);

    /**
     * The argument of a super type as a type binds it: {@code ConstraintValidator<Size,
     * CharSequence>} for a validator declaring {@code implements ConstraintValidator<Size,
     * CharSequence>}. What a type binds in a super type it does not restate is not in the generated
     * metadata, so this is read from the class itself.
     *
     * @param type The type
     * @param superType The super class or interface to resolve
     * @param <T> The super type
     * @return The argument, {@code null} when the type does not extend or implement the super type
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.2
     */
    @Nullable
    <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType);

    /**
     * Checks a constraint annotation type against the constraint definition rules of the
     * specification: the names and the types of the members it declares, and what its validators
     * support. Only the annotation type itself answers those, so a validator configured to check
     * them strictly needs this read.
     *
     * @param constraintType The constraint annotation type
     * @throws jakarta.validation.ConstraintDefinitionException When the type breaks a rule
     * @throws jakarta.validation.ValidationException When nothing can read the type
     * @since 5.2
     */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType);

    /**
     * Instantiates a type the container does not build, through the no-argument constructor it
     * declares: the specification asks the default constraint validator factory to call that
     * constructor for a validator it knows nothing else about, whatever its visibility.
     *
     * @param type The type
     * @param <T> The type
     * @return The instance, {@code null} when the type declares no such constructor
     * @throws jakarta.validation.ValidationException When the constructor fails, or nothing can
     * call it
     * @since 5.2
     */
    @Nullable <T> T instantiate(Class<T> type);

    /**
     * The argument describing a value extractor the specification API hands over as an instance:
     * the {@code ValueExtractor} signature its class declares, with the annotations of that
     * signature and of the class. Nothing generated describes an instance registered at runtime, so
     * its class is read.
     *
     * @param extractorType The class of the extractor instance
     * @return The argument
     * @throws jakarta.validation.ValidationException When the class declares no single extractor
     * signature, or nothing can read it
     * @since 5.2
     */
    Argument<?> valueExtractorArgument(Class<?> extractorType);

    /**
     * The names of the parameters of an executable the specification API names by its {@link
     * java.lang.reflect.Executable}. Only the class file carries them, and only when it was
     * compiled to, so a caller that asks by reflection is answered by reflection.
     *
     * @param executable The executable
     * @return The names, one per parameter
     * @throws jakarta.validation.ValidationException When nothing can read them
     * @since 5.2
     */
    List<String> parameterNames(Executable executable);

    /**
     * The occurrence of a constraint with the validators its annotation type declares, for an
     * occurrence whose metadata does not carry them. The annotation processor records the
     * validators of every constraint it compiles, so this is reached only for a constraint compiled
     * without it.
     *
     * @param value The occurrence
     * @param constraintType The constraint annotation type
     * @return The occurrence, with the declared validators where the type declares any
     * @since 5.2
     */
    AnnotationValue<? extends Annotation> withDeclaredValidators(AnnotationValue<? extends Annotation> value,
                                                                 Class<? extends Annotation> constraintType);

    /**
     * Whether a constraint annotation type is marked {@link
     * jakarta.validation.ReportAsSingleViolation}, for an occurrence whose metadata does not record
     * the marker.
     *
     * @param constraintType The constraint annotation type
     * @return Whether the type is marked
     * @since 5.2
     */
    boolean reportsAsSingleViolation(Class<? extends Annotation> constraintType);

    /**
     * The validator classes a constraint annotation type declares, for a constraint whose
     * occurrences carry none: the annotation processor records them on every occurrence it
     * compiles.
     *
     * @param constraintType The constraint annotation type
     * @return The validator classes, empty where the type declares none
     * @since 5.2
     */
    List<Class<?>> declaredValidators(Class<? extends Annotation> constraintType);

    /**
     * The validation targets a validator class declares through {@link
     * jakarta.validation.constraintvalidation.SupportedValidationTarget}, for a validator the
     * archive holds no introspection of.
     *
     * @param validatorType The validator class
     * @return The targets, empty where the class declares none
     * @since 5.2
     */
    Set<ValidationTarget> supportedValidationTargets(Class<?> validatorType);

    /**
     * Whether a group is a group sequence, for a group the archive holds no introspection of. A
     * group conversion may not name one as its source, which is the rule this answers.
     *
     * @param group The group
     * @return Whether the group declares {@link jakarta.validation.GroupSequence}
     * @since 5.2
     */
    boolean isGroupSequence(Class<?> group);

    /**
     * Whether an annotation type is a constraint, for an occurrence that does not say so itself.
     * The annotation processor retains the contract on every occurrence it compiles, so this is
     * reached only for metadata described reflectively, where no stereotype was recorded.
     *
     * @param annotationType The annotation type
     * @return Whether the type declares {@link jakarta.validation.Constraint}
     * @since 5.2
     */
    boolean isConstraintAnnotation(Class<?> annotationType);

    /**
     * One constraint a constraint type composes.
     *
     * @param type The composing constraint type
     * @param value The occurrence of the composing constraint, with the overrides, the groups and
     * the payload of the composed one applied and the validators of the constraint resolved
     */
    record ComposingConstraint(
            Class<? extends Annotation> type, AnnotationValue<Annotation> value) { }
}
