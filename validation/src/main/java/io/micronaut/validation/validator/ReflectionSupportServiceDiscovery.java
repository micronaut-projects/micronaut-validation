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

import io.micronaut.core.io.service.SoftServiceLoader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Optional provider discovery without retaining an application class loader. */
@NullMarked
final class ReflectionSupportServiceDiscovery {
    private static volatile List<Provider> providers = List.of();
    private static final ReflectionSupport GENERATED = new CompileTimeSupport();

    private ReflectionSupportServiceDiscovery() { }

    static ReflectionSupport get() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ReflectionSupport.class.getClassLoader();
        }
        ReflectionSupport cached = find(loader);
        return cached == null ? load(loader) : cached;
    }

    private static @Nullable ReflectionSupport find(ClassLoader loader) {
        for (Provider provider : providers) {
            if (provider.loader().get() == loader) {
                return provider.support().get();
            }
        }
        return null;
    }

    private static synchronized ReflectionSupport load(ClassLoader loader) {
        ReflectionSupport cached = find(loader);
        if (cached != null) {
            return cached;
        }
        ReflectionSupport support = SoftServiceLoader.load(ReflectionSupport.class, loader)
            .firstAvailable().orElse(GENERATED);
        List<Provider> live = new ArrayList<>();
        for (Provider provider : providers) {
            if (provider.loader().get() != null && provider.support().get() != null) {
                live.add(provider);
            }
        }
        live.add(new Provider(new WeakReference<>(loader), new WeakReference<>(support)));
        providers = List.copyOf(live);
        return support;
    }

    private record Provider(WeakReference<ClassLoader> loader, WeakReference<ReflectionSupport> support) { }
}
