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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospectionReference;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.inject.beans.AbstractInitializableBeanIntrospection;
import io.micronaut.validation.validator.metadata.BeanIntrospectorDecorator;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * The introspections the annotation processors generated, and no other. An introspector also serves what its
 * fallbacks supply, and the {@code micronaut-reflection} module of Micronaut core registers one describing a
 * class reflectively: the validator reading the generated metadata alone must not validate through it, as
 * reflection is what the {@code micronaut-validation-reflection} module is added for. A type described only
 * reflectively is therefore a type without an introspection here.
 *
 * <p>The registered references are read once through {@link #findIntrospections(Predicate)}, which never asks a
 * fallback and, with a filter accepting none, loads none: a type without a reference of its own is a miss and no
 * fallback runs, while a type with one is looked up through the delegate, which finds the reference before any
 * fallback, and what it serves is kept when it is a generated introspection. A type of a class loader the delegate
 * does not see has its references read from that class loader, as the delegate looks them up there.</p>
 *
 * @since 5.3.0
 */
@Internal
final class GeneratedBeanIntrospector implements BeanIntrospector {

    private final BeanIntrospector delegate;
    // the names the references of the class loaders of bean types the delegate does not see describe: names only,
    // so that nothing held here keeps a class loader from being collected
    private final Map<ClassLoader, Set<String>> loaderNames = Collections.synchronizedMap(new WeakHashMap<>());
    private volatile @Nullable Map<String, BeanIntrospectionReference<?>> references;

    private GeneratedBeanIntrospector(BeanIntrospector delegate) {
        this.delegate = delegate;
    }

    /**
     * @param introspector An introspector
     * @return The introspector serving the generated introspections of it only: itself when it already does,
     * or decorates one that does
     */
    static BeanIntrospector of(BeanIntrospector introspector) {
        if (introspector instanceof GeneratedBeanIntrospector) {
            return introspector;
        }
        if (introspector instanceof BeanIntrospectorDecorator decorator && of(decorator.getDecorated()) == decorator.getDecorated()) {
            return introspector;
        }
        return new GeneratedBeanIntrospector(introspector);
    }

    @Override
    public Collection<BeanIntrospection<Object>> findIntrospections(Predicate<? super BeanIntrospectionReference<?>> filter) {
        return delegate.findIntrospections(filter);
    }

    @Override
    public Collection<Class<?>> findIntrospectedTypes(Predicate<? super BeanIntrospectionReference<?>> filter) {
        return delegate.findIntrospectedTypes(filter);
    }

    @Override
    public <T> Optional<BeanIntrospection<T>> findIntrospection(Class<T> beanType) {
        if (!isGenerated(references(), beanType)) {
            ClassLoader classLoader = beanType.getClassLoader();
            if (classLoader == null || !loaderNames.computeIfAbsent(classLoader,
                loader -> index(BeanIntrospector.forClassLoader(loader)).keySet()).contains(beanType.getName())) {
                return Optional.empty();
            }
        }
        // what the delegate serves for a registered type is still one the processors generated
        return delegate.findIntrospection(beanType).filter(AbstractInitializableBeanIntrospection.class::isInstance);
    }

    private Map<String, BeanIntrospectionReference<?>> references() {
        Map<String, BeanIntrospectionReference<?>> found = references;
        if (found == null) {
            found = index(delegate);
            references = found;
        }
        return found;
    }

    private static boolean isGenerated(Map<String, BeanIntrospectionReference<?>> references, Class<?> beanType) {
        BeanIntrospectionReference<?> reference = references.get(beanType.getName());
        // a class loader can define a class of the same name: its reference is not one of this type
        return reference != null && reference.getBeanType() == beanType;
    }

    private static Map<String, BeanIntrospectionReference<?>> index(BeanIntrospector introspector) {
        Map<String, BeanIntrospectionReference<?>> index = new HashMap<>();
        introspector.findIntrospections(reference -> {
            if (reference.isPresent()) {
                index.putIfAbsent(reference.getName(), reference);
            }
            return false;
        });
        return Map.copyOf(index);
    }
}
