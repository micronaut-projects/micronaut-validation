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
package io.micronaut.validation.validator.constraints;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.annotation.URL;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Validator for the {@link URL} constraint.
 *
 * @since 5.3.0
 */
@Singleton
@Introspected
public class URLValidator extends AbstractPatternValidator<URL> {
    private static final int RANDOM_PORT = -1;

    @Override
    public boolean isValid(@Nullable CharSequence value,
                           AnnotationValue<URL> annotationMetadata,
                           ConstraintValidatorContext context) {
        if (StringUtils.isEmpty(value)) {
            return true;
        }
        java.net.URL url;
        try {
            url = new URI(value.toString()).toURL();
        } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            return false;
        }

        ConstraintMembers.UrlParts parts = ConstraintMembers.urlParts(annotationMetadata);
        String protocol = parts.protocol();
        if (!protocol.isEmpty() && !protocol.equals(url.getProtocol())) {
            return false;
        }
        String host = parts.host();
        if (!host.isEmpty() && !host.equals(url.getHost())) {
            return false;
        }
        int port = parts.port();
        if (port != RANDOM_PORT && port != url.getPort()) {
            return false;
        }

        Pattern pattern = getOptionalPattern(annotationMetadata);
        return pattern == null || pattern.matcher(value).matches();
    }
}
