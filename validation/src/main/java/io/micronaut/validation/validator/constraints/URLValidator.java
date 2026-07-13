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

        String protocol = annotationMetadata.stringValue("protocol").orElse("");
        if (!protocol.isEmpty() && !protocol.equals(url.getProtocol())) {
            return false;
        }
        String host = annotationMetadata.stringValue("host").orElse("");
        if (!host.isEmpty() && !host.equals(url.getHost())) {
            return false;
        }
        int port = annotationMetadata.intValue("port").orElse(-1);
        if (port != -1 && port != url.getPort()) {
            return false;
        }

        Pattern pattern = getPattern(annotationMetadata, true);
        return pattern == null || pattern.matcher(value).matches();
    }
}
