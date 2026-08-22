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
package io.micronaut.validation.el.processor;

import io.micronaut.core.annotation.Creator;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A bean whose constraint messages carry Jakarta EL expressions, compiled by
 * {@link ConstraintMessageELVisitor} while this type is processed.
 */
@Introspected
public final class Book {

    @Size(min = 1, max = 8, message = "the title is ${validatedValue.length()} long, not between {min} and {max}")
    private final String title;

    @DecimalMin(value = "1.0", message = "${formatter.format('%.2f', validatedValue)} is below {value}")
    private final double price;

    @Tiny(max = 5)
    private final String code;

    private final List<@Size(max = 2, message = "the tag ${validatedValue.trim()} is longer than {max}") String> tags;

    public Book(String title, double price) {
        this(title, price, "ok", List.of());
    }

    @Creator
    public Book(String title, double price, String code, List<String> tags) {
        this.title = title;
        this.price = price;
        this.code = code;
        this.tags = tags;
    }

    public String getCode() {
        return code;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getTitle() {
        return title;
    }

    public double getPrice() {
        return price;
    }
}
