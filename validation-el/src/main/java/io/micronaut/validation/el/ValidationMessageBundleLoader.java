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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.validator.ReflectionSupport;

import jakarta.validation.ValidationException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Reads application message resources without constructing class-based resource bundles
 * reflectively.
 */
@Internal
final class ValidationMessageBundleLoader {
    private ValidationMessageBundleLoader() { }

    static Optional<String> find(String key, Locale locale) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ValidationMessageBundleLoader.class.getClassLoader();
        }
        var control = ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        var candidates = control.getCandidateLocales("ValidationMessages", locale);
        var bundles = loadCandidates(candidates, control, loader);
        // As with ResourceBundle, use the default locale only when the requested locale
        // has no bundle other than root. A missing key does not select a different locale.
        if (!locale.equals(Locale.ROOT)
                && !bundles.localized()
                && !locale.equals(Locale.getDefault())) {
            var fallback =
                    loadCandidates(
                            control.getCandidateLocales("ValidationMessages", Locale.getDefault()),
                            control,
                            loader);
            if (fallback.localized()) {
                bundles = fallback;
            }
        }
        for (ResourceBundle bundle : bundles.values()) {
            if (bundle.containsKey(key)) {
                return Optional.of(bundle.getString(key));
            }
        }
        return Optional.empty();
    }

    private static BundleCandidates loadCandidates(
            List<Locale> candidates, ResourceBundle.Control control, ClassLoader loader) {
        var bundles = new ArrayList<ResourceBundle>();
        boolean localized = false;
        for (Locale candidate : candidates) {
            String bundleName = control.toBundleName("ValidationMessages", candidate);
            if (loader.getResource(control.toResourceName(bundleName, "class")) != null) {
                var introspections =
                        BeanIntrospector.forClassLoader(loader)
                                .findIntrospections(
                                        reference -> reference.getName().equals(bundleName));
                ResourceBundle bundle =
                        introspections.isEmpty()
                                ? ReflectionSupport.get()
                                        .messageBundle("ValidationMessages", candidate, loader)
                                : (ResourceBundle) introspections.iterator().next().instantiate();
                bundles.add(bundle);
                localized |= !candidate.equals(Locale.ROOT);
                continue;
            }
            String name = control.toResourceName(bundleName, "properties");
            try (var stream = loader.getResourceAsStream(name)) {
                if (stream != null) {
                    bundles.add(new PropertyResourceBundle(stream));
                    localized |= !candidate.equals(Locale.ROOT);
                }
            } catch (IOException e) {
                throw new ValidationException("Cannot read validation message resource " + name, e);
            }
        }
        return new BundleCandidates(List.copyOf(bundles), localized);
    }

    private record BundleCandidates(List<ResourceBundle> values, boolean localized) { }
}
