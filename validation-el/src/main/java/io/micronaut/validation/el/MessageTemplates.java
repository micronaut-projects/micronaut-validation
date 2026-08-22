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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The scanner of the message templates described by the section 5.3.1.1 of the Jakarta Validation
 * specification.
 *
 * <p>The template is resolved in two passes: the message parameters {@code {param}} are substituted first,
 * recursively, and the expressions {@code ${expression}} of the resulting template are evaluated second.</p>
 *
 * <p>The scanner is shared by {@link ElMessageInterpolator}, which runs both passes at runtime, and by the
 * annotation processor of {@code micronaut-validation-el-processor}, which runs the first pass at compilation
 * time in order to know which expressions the second pass will evaluate. Both must agree on the exact
 * expression strings, because a compiled expression is located by its string, so the scan lives here rather
 * than in either of them.</p>
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
public final class MessageTemplates {

    /**
     * The maximum number of times the message parameters are resolved before the template is considered stable.
     */
    public static final int MAX_RECURSION = 10;

    private static final char ESCAPE = '\\';
    private static final char LEFT_BRACE = '{';
    private static final char RIGHT_BRACE = '}';
    private static final char DOLLAR = '$';

    private MessageTemplates() {
    }

    /**
     * Substitutes the message parameters of the template until it no longer changes.
     *
     * @param template The template
     * @param resolver The resolver of a parameter name, returning an empty optional for an unknown parameter
     * @return The template with its known parameters substituted
     */
    public static String resolveParameters(String template, Function<String, Optional<?>> resolver) {
        String resolved = template;
        for (int i = 0; i < MAX_RECURSION; i++) {
            String next = substituteParameters(resolved, resolver);
            if (next.equals(resolved)) {
                break;
            }
            resolved = next;
        }
        return resolved;
    }

    /**
     * Substitutes the message parameters of the template once.
     *
     * @param template The template
     * @param resolver The resolver of a parameter name, returning an empty optional for an unknown parameter
     * @return The template with its known parameters substituted
     */
    public static String substituteParameters(String template, Function<String, Optional<?>> resolver) {
        StringBuilder result = new StringBuilder(template.length());
        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                result.append(template.charAt(++i));
                continue;
            }
            if (current == LEFT_BRACE) {
                int end = findExpressionEnd(template, i + 1);
                if (end > -1) {
                    String parameter = template.substring(i + 1, end);
                    Optional<?> value = resolver.apply(parameter);
                    if (value.isPresent()) {
                        result.append(value.get());
                    } else {
                        result.append(LEFT_BRACE).append(parameter).append(RIGHT_BRACE);
                    }
                    i = end;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    /**
     * Replaces every expression of the template with the value the evaluator returns for it.
     *
     * @param template  The template, with its message parameters already substituted
     * @param evaluator The evaluator, receiving the expression string, {@code ${...}} included
     * @return The interpolated message
     */
    public static String interpolateExpressions(String template, UnaryOperator<String> evaluator) {
        StringBuilder result = new StringBuilder(template.length());
        scan(template, (expression, plain) -> result.append(plain ? expression : evaluator.apply(expression)));
        return result.toString();
    }

    /**
     * Collects the expressions of the template, in the order in which they are evaluated.
     *
     * @param template The template, with its message parameters already substituted
     * @return The expression strings, {@code ${...}} included, without duplicates
     */
    public static List<String> expressionsOf(String template) {
        List<String> expressions = new ArrayList<>(2);
        scan(template, (expression, plain) -> {
            if (!plain && !expressions.contains(expression)) {
                expressions.add(expression);
            }
        });
        return expressions;
    }

    /**
     * Walks the template, handing every expression and every stretch of plain text to the consumer.
     */
    private static void scan(String template, ChunkConsumer consumer) {
        StringBuilder plain = new StringBuilder(template.length());
        for (int i = 0; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                plain.append(template.charAt(++i));
                continue;
            }
            if (current == DOLLAR && i + 1 < template.length() && template.charAt(i + 1) == LEFT_BRACE) {
                int end = findExpressionEnd(template, i + 2);
                if (end > -1) {
                    if (!plain.isEmpty()) {
                        consumer.accept(plain.toString(), true);
                        plain.setLength(0);
                    }
                    consumer.accept(template.substring(i, end + 1), false);
                    i = end;
                    continue;
                }
            }
            plain.append(current);
        }
        if (!plain.isEmpty()) {
            consumer.accept(plain.toString(), true);
        }
    }

    /**
     * Finds the brace closing the construct opened at the given offset.
     *
     * @param template The template
     * @param offset   The offset of the first character of the body
     * @return The index of the closing brace, or {@code -1} when the construct is not closed
     */
    public static int findExpressionEnd(String template, int offset) {
        for (int i = offset; i < template.length(); i++) {
            char current = template.charAt(i);
            if (current == ESCAPE && i + 1 < template.length()) {
                i++;
                continue;
            }
            if (current == RIGHT_BRACE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The consumer of the chunks of a scanned template.
     */
    private interface ChunkConsumer {

        /**
         * @param chunk The chunk
         * @param plain Whether the chunk is plain text rather than an expression
         */
        void accept(String chunk, boolean plain);
    }
}
