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

import io.micronaut.core.beans.BeanIntrospector;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GeneratedClassLoaderDisposalTest {
    @Test
    void closingFactoryDoesNotRetainApplicationClasses() throws Exception {
        WeakReference<ClassLoader> reference = validateInDisposableLoader();
        // Core's service index holds its most recently used loader. Refresh that independent,
        // bounded cache before testing whether validation metadata and closed owners retain it.
        BeanIntrospector.forClassLoader(getClass().getClassLoader())
            .getIntrospection(GeneratedDeclarationAccessTest.StandardBean.class);
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(reference.get(), "Closed validation caches must not retain an application loader");
    }

    private WeakReference<ClassLoader> validateInDisposableLoader() throws Exception {
        URL output = getClass().getProtectionDomain().getCodeSource().getLocation();
        ClassLoader parent = getClass().getClassLoader();
        var loader = new URLClassLoader(new URL[]{output}, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("io.micronaut.validation.validator.GeneratedDeclarationAccessTest")
                    || name.startsWith("io.micronaut.validation.validator.$GeneratedDeclarationAccessTest")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) { type = findClass(name); }
                        if (resolve) { resolveClass(type); }
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        var configuration = new DefaultValidatorConfiguration();
        configuration.setBeanIntrospector(BeanIntrospector.forClassLoader(loader));
        try (loader; var factory = new DefaultValidatorFactory(configuration)) {
            Class<?> type = loader.loadClass("io.micronaut.validation.validator.GeneratedDeclarationAccessTest$StandardBean");
            Object bean = BeanIntrospector.forClassLoader(loader).getIntrospection(type).instantiate();
            assertEquals(1, factory.getValidator().validate(bean).size());
        } finally {
            thread.setContextClassLoader(previous);
        }
        return new WeakReference<>(loader);
    }
}
