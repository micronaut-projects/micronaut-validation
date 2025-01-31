/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.validation.validator.messages;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.http.server.util.locale.HttpLocaleResolver;
import jakarta.inject.Singleton;

import java.util.Locale;
import java.util.Optional;

@Internal
@Requires(beans = HttpLocaleResolver.class)
@Singleton
class HttpInterpolatorLocaleResolver implements InterpolatorLocaleResolver {

    private final HttpLocaleResolver localeResolver;

    HttpInterpolatorLocaleResolver(HttpLocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @Override
    public Optional<Locale> resolve() {
        return ServerRequestContext.currentRequest().map(localeResolver::resolveOrDefault);
    }
}
