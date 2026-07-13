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
import io.micronaut.core.util.StringUtils;
import io.micronaut.validation.annotation.URL;
import jakarta.inject.Singleton;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Validator for the {@link URL} constraint.
 *
 * @since 5.1.0
 */
@Singleton
public class URLValidator extends AbstractPatternValidator<URL> {
    private static final String MEMBER_PROTOCOL = "protocol";
    private static final String MEMBER_HOST = "host";
    private static final String MEMBER_PORT = "port";
    private static final String EMPTY_STRING = "";
    private static final int ANY_PORT = -1;
    private static final int RANDOM_PORT = ANY_PORT;

    @Override
    public boolean isValid(@Nullable CharSequence value,
                           @NonNull AnnotationValue<URL> annotationMetadata,
                           @NonNull ConstraintValidatorContext context) {
        if (StringUtils.isEmpty(value)) {
            return true;
        }
        java.net.URL url;
        try {
            url = new java.net.URI(value.toString()).toURL();
        } catch (MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            return false;
        }

        String protocol = annotationMetadata.stringValue(MEMBER_PROTOCOL).orElse(EMPTY_STRING);
        if (!protocol.isEmpty() && !protocol.equals(url.getProtocol())) {
            return false;
        }
        String host = annotationMetadata.stringValue(MEMBER_HOST).orElse(EMPTY_STRING);
        if (!host.isEmpty() && !host.equals(url.getHost())) {
            return false;
        }
        int port = annotationMetadata.intValue(MEMBER_PORT).orElse(RANDOM_PORT);
        if (port != RANDOM_PORT && port != url.getPort()) {
            return false;
        }

        Pattern pattern = getPattern(annotationMetadata, true);
        return pattern == null || pattern.matcher(value).matches();
    }
}
