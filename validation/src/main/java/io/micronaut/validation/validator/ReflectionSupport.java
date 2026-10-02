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
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.MethodReference;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import io.micronaut.validation.validator.metadata.ValidationField;
import io.micronaut.validation.validator.metadata.ValidationMetadataSupport;
import jakarta.validation.ValidationException;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.constraintvalidation.ValidationTarget;
import jakarta.validation.groups.Default;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
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
 * @since 5.3.0
 */
@Internal
public interface ReflectionSupport extends RuntimeValidationAccess {

    /**
     * Creates access to generated metadata without runtime discovery.
     * @param introspector The generated introspector
     * @param loader The application loader
     * @return The generated metadata provider
     */
    static ReflectionSupport generated(BeanIntrospector introspector, ClassLoader loader) {
        return new CompileTimeSupport(introspector, loader);
    }

    /**
     * The support in force: the first one registered as a service, else the one reading the
     * generated metadata only.
     *
     * @return The support
     */
    static ReflectionSupport get() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return forClassLoader(loader == null ? ReflectionSupport.class.getClassLoader() : loader);
    }

    /**
     * Resolves the access provider once for an application loader.
     * @param loader The application loader
     * @return The access provider
     */
    static ReflectionSupport forClassLoader(ClassLoader loader) {
        return ReflectionSupportServiceDiscovery.get(loader);
    }

    /** @return The application loader captured during provider discovery. */
    default ClassLoader classLoader() {
        return ReflectionSupport.class.getClassLoader();
    }

    /**
     * Binds a discovered provider to its application loader.
     * @param loader The application loader
     * @return The bound provider
     */
    default ReflectionSupport withClassLoader(ClassLoader loader) {
        return withIntrospector(BeanIntrospector.forClassLoader(loader));
    }

    /** @return The generated introspector associated with this provider. */
    default BeanIntrospector introspector() {
        return BeanIntrospector.forClassLoader(classLoader());
    }

    /**
     * Binds a provider to the factory's generated metadata.
     * @param introspector The configured introspector
     * @return The bound provider
     */
    default ReflectionSupport withIntrospector(BeanIntrospector introspector) {
        return this;
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
     * Normalizes metadata loaded by the optional provider before validation traversal.
     *
     * @param argument The described argument
     * @return The argument carrying the standard validation markers
     */
    default Argument<?> prepareArgument(Argument<?> argument) {
        return argument;
    }

    /**
     * Reads a declaration through generated access. Inaccessible fields require the reflection provider.
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
            throw new ValidationException("Cannot read field " + member.getDeclaringType().getName()
                + "." + member.getName() + ": direct field access requires micronaut-validation-reflection");
        }
        return member.read(bean);
    }

    /**
     * Reads through the generated property accessor when property access was selected.
     * A differently typed getter cannot supply the value of a field declaration.
     *
     * @param member The declaration
     * @param property The generated property
     * @param bean The instance
     * @param <T> The bean type
     * @return The value for the configured access mode
     * @since 5.3.0
     */
    default <T> @Nullable Object readMember(BeanPropertyMember<T, ?> member, BeanProperty<T, ?> property, T bean) {
        if (member.getAnnotationMetadata().booleanValue(ValidationField.class, "property").orElse(false)
            && member.asArgument().equalsStructure(property.asArgument())) {
            return property.get(bean);
        }
        return readMember(member, bean);
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
        var introspection = introspector().findIntrospection(type).orElse(null);
        if (introspection == null) {
            throw new ValidationException(
                    "No generated declaration for "
                            + type.getName()
                            + "."
                            + name
                            + ": add micronaut-validation-reflection");
        }
        var declaration = ValidationDeclaration.generated(this, introspection, kind, name, parameters);
        return declaration;
    }

    /**
     * @param type The type
     * @return Its generated interfaces
     * @since 5.3.0
     */
    default List<Class<?>> interfaces(Class<?> type) {
        if (type == Object.class || type == Default.class) {
            return List.of();
        }
        var metadata = introspector().findIntrospection(type)
            .map(value -> ValidationMetadataSupport.hierarchy(value.getAnnotationMetadata(), type)).orElse(null);
        if (metadata == null) {
            throw new ValidationException(
                    "No generated hierarchy for "
                            + type.getName()
                            + ": add micronaut-validation-reflection or compile the type with"
                            + " micronaut-validation-processor");
        }
        return List.of(metadata.classValues("interfaces"));
    }

    /**
     * @param type The hierarchy to resolve
     * @return Whether generated metadata or the optional provider can describe it
     */
    default boolean canResolveHierarchy(Class<?> type) {
        return introspector().findIntrospection(type)
            .map(value -> ValidationMetadataSupport.hierarchy(value.getAnnotationMetadata(), type)).orElse(null) != null;
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
        var metadata = introspector().findIntrospection(type)
            .map(value -> ValidationMetadataSupport.hierarchy(value.getAnnotationMetadata(), type)).orElse(null);
        if (metadata == null) {
            throw new ValidationException(
                    "No generated hierarchy for "
                            + type.getName()
                            + ": add micronaut-validation-reflection or compile the type with"
                            + " micronaut-validation-processor");
        }
        return metadata.classValue("superType").orElse(null);
    }

    /**
     * Returns a caller-supplied signature, or requests optional member lookup.
     *
     * @param executable The generated executable
     * @return The Jakarta signature
     * @since 5.3.0
     */
    default Method targetMethod(MethodReference<?, ?> executable) {
        if (executable instanceof CallerSuppliedExecutable<?, ?> supplied) {
            return supplied.suppliedMethod();
        }
        if (executable instanceof IntrospectedExecutable<?, ?> supplied) {
            return supplied.suppliedMethod();
        }
        throw new ValidationException(
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
     * the type declaring each. Core declaration metadata separates these owners when the introspection exposes members;
     * older or incomplete descriptions may still merge them.
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
     * as an {@code Iterable}. Generated introspection type arguments describe these bindings, with retained type-use
     * annotations. The optional companion resolves types without generated metadata.
     *
     * @param declaredType The type as declared
     * @param containerType The generic super type it is read as
     * @param typeArgumentIndex The index of the type argument of that super type
     * @return The argument, {@code null} when the type binds no such argument
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.3.0
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
     * @since 5.3.0
     */
    @Nullable
    Integer extractedTypeArgumentIndex(Class<?> declaredType, Class<?> containerType, int typeArgumentIndex);

    /**
     * The argument of a super type as a type binds it: {@code ConstraintValidator<Size,
     * CharSequence>} for a validator declaring {@code implements ConstraintValidator<Size,
     * CharSequence>}. Generated introspections retain these bindings, including intermediate super types.
     * The optional companion resolves signatures for types without an introspection.
     *
     * @param type The type
     * @param superType The super class or interface to resolve
     * @param <T> The super type
     * @return The argument, {@code null} when the type does not extend or implement the super type
     * @throws jakarta.validation.ValidationException When nothing can read it
     * @since 5.3.0
     */
    @Nullable
    <T> Argument<T> genericSuperArgument(Class<?> type, Class<T> superType);

    /**
     * Checks a constraint annotation type against the constraint definition rules of the
     * specification: the names and the types of the members it declares, and what its validators
     * support. The processor retains definition results on compiled occurrences. This type-only operation
     * supplies the optional fallback for constraints without those results.
     *
     * @param constraintType The constraint annotation type
     * @throws jakarta.validation.ConstraintDefinitionException When the type breaks a rule
     * @throws jakarta.validation.ValidationException When nothing can read the type
     * @since 5.3.0
     */
    void checkConstraintDefinition(Class<? extends Annotation> constraintType);

    /**
     * Checks a definition retained by the processor, with runtime discovery only for missing metadata.
     * @param constraintType The constraint annotation
     * @param occurrence The compiled occurrence
     */
    default void checkConstraintDefinition(Class<? extends Annotation> constraintType, AnnotationValue<?> occurrence) {
        if (occurrence.booleanValue(ValidationAnnotationUtil.DEFINITION_CHECKED).orElse(false)) {
            occurrence.stringValue(ValidationAnnotationUtil.DEFINITION_ERROR).ifPresent(error -> {
                throw new ConstraintDefinitionException(error);
            });
        } else {
            checkConstraintDefinition(constraintType);
        }
    }

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
     * @since 5.3.0
     */
    @Nullable <T> T instantiate(Class<T> type);

    /**
     * The argument describing a value extractor the specification API hands over as an instance:
     * the {@code ValueExtractor} signature its class declares, with the annotations of that
     * signature and of the class. An introspected implementation supplies the signature even when its instance is
     * registered at runtime; other implementations require the optional companion.
     *
     * @param extractorType The class of the extractor instance
     * @return The argument
     * @throws jakarta.validation.ValidationException When the class declares no single extractor
     * signature, or nothing can read it
     * @since 5.3.0
     */
    Argument<?> valueExtractorArgument(Class<?> extractorType);

    /**
     * The names of the parameters of an executable the specification API names by its {@link
     * java.lang.reflect.Executable}. Generated executable metadata supplies the names; the optional companion reads
     * class-file parameter information when generated metadata is unavailable.
     *
     * @param executable The executable
     * @return The names, one per parameter
     * @throws jakarta.validation.ValidationException When nothing can read them
     * @since 5.3.0
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
     * @since 5.3.0
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
     * @since 5.3.0
     */
    boolean reportsAsSingleViolation(Class<? extends Annotation> constraintType);

    /**
     * The validator classes a constraint annotation type declares, for a constraint whose
     * occurrences carry none: the annotation processor records them on every occurrence it
     * compiles.
     *
     * @param constraintType The constraint annotation type
     * @return The validator classes, empty where the type declares none
     * @since 5.3.0
     */
    List<Class<?>> declaredValidators(Class<? extends Annotation> constraintType);

    /**
     * The validation targets a validator class declares through {@link
     * jakarta.validation.constraintvalidation.SupportedValidationTarget}, for a validator the
     * archive holds no introspection of.
     *
     * @param validatorType The validator class
     * @return The targets, empty where the class declares none
     * @since 5.3.0
     */
    Set<ValidationTarget> supportedValidationTargets(Class<?> validatorType);

    /**
     * Whether a group is a group sequence, for a group the archive holds no introspection of. A
     * group conversion may not name one as its source, which is the rule this answers.
     *
     * @param group The group
     * @return Whether the group declares {@link jakarta.validation.GroupSequence}
     * @since 5.3.0
     */
    boolean isGroupSequence(Class<?> group);

    /**
     * Whether an annotation type is a constraint, for an occurrence that does not say so itself.
     * The annotation processor retains the contract on every occurrence it compiles, so this is
     * reached only for metadata described reflectively, where no stereotype was recorded.
     *
     * @param annotationType The annotation type
     * @return Whether the type declares {@link jakarta.validation.Constraint}
     * @since 5.3.0
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
