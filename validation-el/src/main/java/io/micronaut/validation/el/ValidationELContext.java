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
package io.micronaut.validation.el;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.el.resolver.CommonELResolver;
import io.micronaut.el.resolver.ELResolverChain;
import io.micronaut.el.resolver.IntrospectionELResolver;
import io.micronaut.el.resolver.StreamELResolver;
import io.micronaut.el.runtime.MapVariableMapper;
import jakarta.el.ELContext;
import jakarta.el.ELResolver;
import jakarta.el.FunctionMapper;
import jakarta.el.ListELResolver;
import jakarta.el.MapELResolver;
import jakarta.el.ResourceBundleELResolver;
import jakarta.el.VariableMapper;
import org.jspecify.annotations.Nullable;

/** EL context whose property access uses generated metadata and container resolvers only. */
@Introspected(classes = Class.class, includes = {"name", "simpleName"})
final class ValidationELContext extends ELContext {
    private final ELResolver resolver;
    private final VariableMapper variables = new MapVariableMapper();

    ValidationELContext(BeanIntrospector introspector) {
        resolver = new ELResolverChain(
            new IntrospectionELResolver(introspector, true),
            new CommonELResolver(),
            new StreamELResolver(),
            new MapELResolver(true),
            new ResourceBundleELResolver(),
            new ListELResolver(true)
        );
    }

    @Override
    public ELResolver getELResolver() {
        return resolver;
    }

    @Override
    public @Nullable FunctionMapper getFunctionMapper() {
        return null;
    }

    @Override
    public VariableMapper getVariableMapper() {
        return variables;
    }
}
