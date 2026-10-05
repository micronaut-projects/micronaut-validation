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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.WatchableBeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.DevelopmentMode;
import io.micronaut.context.reload.ClassChange;
import io.micronaut.context.reload.ClassChangeEvent;
import io.micronaut.context.reload.ReloadStrategy;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.validation.RequiresValidation;
import io.micronaut.validation.Validated;
import io.micronaut.validation.ValidatingInterceptor;
import io.micronaut.validation.validator.constraints.ConstraintValidatorRegistry;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;
import io.micronaut.validation.validator.extractors.ValueExtractorRegistry;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Recreates the validator, and the registries and caches it is built from, when what they were built
 * from changes in development mode: a constraint validator or value extractor definition added or
 * removed, or a reload that retires a classloader or changes a validated type. It exists only in
 * development mode, so nothing of it is on the path of a validation: the beans are recreated, and the
 * next validation builds fresh caches.
 *
 * <p>Each bean is recreated through {@link WatchableBeanContext#recreate(Object)}, which destroys the beans
 * that received it, as the dependency graph of a development context records, so that they are created again
 * on top of the new one. A bean of the list that is not a dependent of one recreated before it is recreated in
 * turn. A context that does not track bean dependencies recreates nothing: the beans are kept, rather than
 * replaced under beans that would keep the old ones, and the change is seen after a restart.</p>
 *
 * <p>It holds the context only, never a validation bean: a bean that received one is a dependent of it,
 * which recreating it would destroy along with its watches.</p>
 *
 * @author graemerocher
 * @since 5.3.0
 */
@Internal
@Context
@Requires(condition = DevelopmentMode.Active.class)
final class DevelopmentValidationReloader {

    private static final Logger LOG = LoggerFactory.getLogger(DevelopmentValidationReloader.class);

    /**
     * The beans that hold what a validation resolved, in the order they are built on each other: a bean
     * recreated later receives the beans recreated before it.
     */
    private static final List<Class<?>> RECREATED = List.of(
        ValueExtractorRegistry.class,
        ConstraintValidatorRegistry.class,
        InternalConstraintValidatorFactory.class,
        ValidatorConfiguration.class,
        Validator.class,
        ValidatorFactory.class,
        ValidatingInterceptor.class
    );

    private final BeanContext beanContext;

    /**
     * @param beanContext The context, watched when it can be
     */
    DevelopmentValidationReloader(BeanContext beanContext) {
        this.beanContext = beanContext;
        if (beanContext instanceof WatchableBeanContext watchable) {
            // the first batch is what the registries were, or will be, built from: only what changes after it matters
            // the Micronaut constraint validators are Jakarta ones too
            watchable.watchDefinitions(ConstraintValidator.class, null, change -> {
                if (!change.initial()) {
                    rebuild("constraint validator definitions changed");
                }
            });
            watchable.watchDefinitions(ValueExtractor.class, null, change -> {
                if (!change.initial()) {
                    rebuild("value extractor definitions changed");
                }
            });
            watchable.watchClassChanges(change -> {
                if (affectsValidation(change, beanContext)) {
                    rebuild("validated classes changed");
                }
            });
        }
    }

    /**
     * Whether a class change concerns what validation resolved. A change that restarts the application
     * creates new validation beans with the new context. One applied in place does not: the caches are
     * keyed by class, method and metadata, so a change that retires a classloader leaves them holding
     * what was resolved for the retired generation, and one that redefines in place a validated type, or a bean
     * that validates its methods, changes the constraints read from it.
     *
     * <p>A class counts when it is validated now or when the class it replaces was: an edit that removes the last
     * constraint, {@link Validated} or {@link GroupSequence} of a class leaves a class that no longer looks
     * validated, while the caches still hold what was resolved from the one it replaces. The one it replaces is
     * read from the metadata the context was compiled with, the introspections and bean definitions validation
     * resolved from, which a change applied in place leaves as they were. It is looked up by name: nothing of a
     * previous class is kept.</p>
     *
     * @param change The class change
     * @param beanContext The context
     * @return Whether to recreate the validation beans
     */
    private static boolean affectsValidation(ClassChangeEvent change, BeanContext beanContext) {
        if (change.strategy() == ReloadStrategy.RESTART) {
            return false;
        }
        if (!change.retiredLoaders().isEmpty()) {
            return true;
        }
        for (ClassChange classChange : change.changes()) {
            String className = classChange.className();
            if (className.endsWith("$Introspection")
                || isValidated(className, change.newLoader())
                || wasValidated(className, beanContext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the class a change replaces was validated, as the context was compiled: it has an introspection,
     * or a bean definition of it validates its methods. The references are matched by name: only the definitions
     * and the introspection of that name are loaded.
     *
     * @param className The changed class
     * @param beanContext The context
     * @return Whether validation resolved something from the class it replaces
     */
    private static boolean wasValidated(String className, BeanContext beanContext) {
        // the compiled definitions of a class are named after it: $Outer$Inner$Definition, and $Outer$Inner$Definition$...
        // for its proxy, so that the references are matched without loading a bean type
        int lastDot = className.lastIndexOf('.');
        String definition = className.substring(0, lastDot + 1) + '$' + className.substring(lastDot + 1) + "$Definition";
        for (BeanDefinitionReference<?> reference : beanContext.getBeanDefinitionReferences()) {
            String name = reference.getBeanDefinitionName();
            if ((name.equals(definition) || name.startsWith(definition + '$')) && validatesMethods(reference)) {
                return true;
            }
        }
        try {
            return !BeanIntrospector.SHARED.findIntrospections(reference -> className.equals(reference.getName())).isEmpty();
        } catch (RuntimeException | LinkageError e) {
            // an introspection of that name that no longer loads: the retired state is unknown, so it counts
            return true;
        }
    }

    private static boolean isValidated(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            return type.isAnnotationPresent(Introspected.class)
                || type.isAnnotationPresent(Constraint.class)
                || type.isAnnotationPresent(GroupSequence.class)
                || ConstraintValidator.class.isAssignableFrom(type)
                || type.isAnnotationPresent(Validated.class)
                || ValueExtractor.class.isAssignableFrom(type)
                || BeanIntrospector.SHARED.findIntrospection(type).isPresent()
                || hasMethodConstraints(type);
        } catch (ClassNotFoundException | LinkageError e) {
            // removed, or not loadable on its own: nothing validation can have resolved from the new generation
            return false;
        }
    }

    /**
     * Whether a compiled bean definition validates its methods, as the processor marks them.
     *
     * @param reference The reference to the definition
     * @return Whether the interceptor validates a method of it
     */
    private static boolean validatesMethods(BeanDefinitionReference<?> reference) {
        try {
            if (requiresValidation(reference.getAnnotationMetadata())) {
                return true;
            }
            BeanDefinition<?> definition = reference.load();
            if (definition == null) {
                return false;
            }
            if (requiresValidation(definition.getAnnotationMetadata())) {
                return true;
            }
            for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
                if (requiresValidation(method.getAnnotationMetadata())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException | LinkageError e) {
            // a definition of that name that no longer loads: what it was is unknown, so it counts
            return true;
        }
    }

    private static boolean requiresValidation(AnnotationMetadata metadata) {
        return metadata.hasStereotype(Validated.class) || metadata.hasStereotype(RequiresValidation.class);
    }

    /**
     * Whether a bean validates its methods: a method marked {@link Validated}, or a method whose return
     * value or parameters carry a constraint or {@link Valid}. The interceptor caches what it decided
     * for each method.
     *
     * @param type The changed class
     * @return Whether a method of it is validated
     */
    private static boolean hasMethodConstraints(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Validated.class) || anyConstraint(method.getAnnotations())) {
                return true;
            }
            for (Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
                if (anyConstraint(parameterAnnotations)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyConstraint(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof Valid || annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recreates the validation beans the context holds. Nothing is created that was not created already:
     * a bean nobody asked for yet is built from the current definitions when it is first asked for.
     *
     * @param reason Why, for the log
     */
    private void rebuild(String reason) {
        if (!(beanContext instanceof WatchableBeanContext context)) {
            return;
        }
        // taken first: recreating one destroys the beans that received it, as the graph records them
        List<Object> beans = new ArrayList<>();
        for (Class<?> type : RECREATED) {
            for (BeanRegistration<?> registration : beanContext.getActiveBeanRegistrations(type)) {
                Object bean = registration.bean();
                if (beans.stream().noneMatch(taken -> taken == bean)) {
                    beans.add(bean);
                }
            }
        }
        if (beans.isEmpty()) {
            return;
        }
        LOG.debug("Recreating the validator: {}", reason);
        for (Object bean : beans) {
            // false for a bean destroyed with one it received: it is created again when next asked for. False for
            // all of them in a context that does not track bean dependencies: they are kept, read again after a restart
            context.recreate(bean);
        }
    }
}
