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

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The introspections the annotation processors generated, and no other. An introspector also serves what its
 * fallbacks supply, and the {@code micronaut-reflection} module of Micronaut core registers one describing a
 * class reflectively: the validator reading the generated metadata alone must not validate through it, as
 * reflection is what the {@code micronaut-validation-reflection} module is added for. A type described only
 * reflectively is therefore a type without an introspection here.
 *
 * @since 5.3.0
 */
@Internal
final class GeneratedBeanIntrospector implements BeanIntrospector {

    private final BeanIntrospector delegate;

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
        return delegate.findIntrospection(beanType).filter(AbstractInitializableBeanIntrospection.class::isInstance);
    }
}
