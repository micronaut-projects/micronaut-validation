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
package io.micronaut.validation.bootstrap;

import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Internal classpath resource path validation for bootstrap XML entries.
 */
final class ValidationResourcePaths {

    private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:.*");

    private ValidationResourcePaths() {
    }

    static String normalizeClasspathResource(@Nullable String path, String role) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            throw new ValidationException("Invalid " + role + " resource path: path is empty");
        }
        if (value.startsWith("//")) {
            throw new ValidationException("Invalid " + role + " resource path: " + path);
        }
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty() || value.endsWith("/") || value.contains("\\") || URI_SCHEME.matcher(value).matches()) {
            throw new ValidationException("Invalid " + role + " resource path: " + path);
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new ValidationException("Invalid " + role + " resource path: " + path);
            }
        }
        return value;
    }
}
