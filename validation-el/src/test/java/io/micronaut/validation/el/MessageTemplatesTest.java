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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scan is shared by the interpolator and by the annotation processor, and a compiled expression is located
 * by its string, so these are the cases on which the two must agree.
 */
class MessageTemplatesTest {

    private static final Map<String, Object> PARAMETERS = Map.of("min", 1, "max", 8, "value", 5);

    @Test
    void resolvesTheParametersItKnowsAndLeavesTheOthersInPlace() {
        assertEquals(
            "size must be between 1 and 8, {unknown}",
            resolve("size must be between {min} and {max}, {unknown}")
        );
    }

    @Test
    void resolvesTheParametersRecursively() {
        assertEquals("1", MessageTemplates.resolveParameters("{a}",
            name -> Optional.ofNullable(Map.of("a", "{b}", "b", "1").get(name))));
    }

    @Test
    void stopsResolvingTheParametersOfACycle() {
        assertEquals("{a}", MessageTemplates.resolveParameters("{a}",
            name -> Optional.ofNullable(Map.<String, Object>of("a", "{b}", "b", "{a}").get(name))));
    }

    @Test
    void substitutesTheParameterOfAnExpressionBeforeTheExpressionIsCollected() {
        // The parameter pass runs first, as required by the section 5.3.1.1, so ${value} never reaches the
        // expression pass while ${value * 2} does. The processor has to see the same thing.
        assertEquals("must be $5 at least", resolve("must be ${value} at least"));
        assertEquals(List.of(), MessageTemplates.expressionsOf(resolve("must be ${value} at least")));
        assertEquals(List.of("${value * 2}"), MessageTemplates.expressionsOf(resolve("must be ${value * 2} at least")));
    }

    @Test
    void collectsTheExpressionsWithTheirDelimitersAndWithoutDuplicates() {
        assertEquals(
            List.of("${validatedValue}", "${validatedValue.length()}"),
            MessageTemplates.expressionsOf("${validatedValue} ${validatedValue.length()} ${validatedValue}")
        );
    }

    @Test
    void ignoresAnUnclosedExpression() {
        assertEquals(List.of(), MessageTemplates.expressionsOf("${validatedValue"));
    }

    @Test
    void ignoresAnEscapedExpression() {
        assertEquals(List.of(), MessageTemplates.expressionsOf("\\${validatedValue}"));
    }

    @Test
    void interpolatesTheExpressionsAndKeepsTheTextAround() {
        assertEquals(
            "a <${one}> b <${two}> c",
            MessageTemplates.interpolateExpressions("a ${one} b ${two} c", expression -> "<" + expression + ">")
        );
    }

    @Test
    void unescapesTheTextItKeeps() {
        assertEquals("${literal}", MessageTemplates.interpolateExpressions("\\${literal}", expression -> "!"));
    }

    private static String resolve(String template) {
        return MessageTemplates.resolveParameters(template, name -> Optional.ofNullable(PARAMETERS.get(name)));
    }
}
