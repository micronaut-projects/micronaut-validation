/*
 * Copyright 2017-2020 original authors
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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import jakarta.validation.ValidationException;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.PatternSyntaxException;

/**
 * Abstract pattern validator.
 *
 * @param <A> The annotation type.
 * @author graemerocher
 * @since 1.2
 */
abstract class AbstractPatternValidator<A extends Annotation> implements ConstraintValidator<A, CharSequence> {
    private static final Pattern.Flag[] ZERO_FLAGS = new Pattern.Flag[0];
    private static final Map<PatternKey, java.util.regex.Pattern> COMPUTED_PATTERNS = new ConcurrentHashMap<>(10);

    /**
     * Gets the pattern the given annotation metadata requires.
     *
     * @param annotationMetadata The metadata
     * @return The pattern
     * @throws ValidationException When the metadata specifies no pattern
     */
    java.util.regex.Pattern getPattern(@NonNull AnnotationValue<?> annotationMetadata) {
        final String pattern = annotationMetadata.get("regexp", String.class)
            .orElseThrow(() -> new ValidationException("No pattern specified"));
        return compile(pattern, annotationMetadata.get("flags", Pattern.Flag[].class).orElse(ZERO_FLAGS));
    }

    /**
     * Gets the pattern the given annotation metadata may specify.
     *
     * @param annotationMetadata The metadata
     * @return The pattern, {@code null} when the metadata specifies none that restricts the value
     */
    java.util.regex.@Nullable Pattern getOptionalPattern(@NonNull AnnotationValue<?> annotationMetadata) {
        final String pattern = annotationMetadata.get("regexp", String.class).orElse(".*");
        final Pattern.Flag[] flags = annotationMetadata.get("flags", Pattern.Flag[].class).orElse(ZERO_FLAGS);
        if (pattern.equals(".*") && flags.length == 0) {
            return null;
        }
        return compile(pattern, flags);
    }

    private static java.util.regex.Pattern compile(String pattern, Pattern.Flag[] flags) {
        int computedFlag = 0;
        for (Pattern.Flag flag : flags) {
            computedFlag = computedFlag | flag.getValue();
        }

        final PatternKey key = new PatternKey(pattern, computedFlag);
        java.util.regex.Pattern regex = COMPUTED_PATTERNS.get(key);
        if (regex == null) {
            try {
                if (computedFlag != 0) {
                    regex = java.util.regex.Pattern.compile(pattern, computedFlag);
                } else {
                    regex = java.util.regex.Pattern.compile(pattern);
                }
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid regular expression", e);
            }
            COMPUTED_PATTERNS.put(key, regex);
        }
        return regex;
    }

    /**
     * Key used to cache patterns.
     *
     * @param pattern The pattern
     * @param flags   The flags
     */
    private record PatternKey(String pattern, int flags) {
    }
}
