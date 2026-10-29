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
package io.micronaut.validation.validator;

import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.core.type.Argument;

import java.util.List;

/**
 * The prepared validation of a property.
 *
 * @param argument The argument of the property, with its metadata
 * @param validated Whether the property as a whole declares something to validate
 * @param members The members validated on their own, empty when the property is validated as a whole
 */
record PropertyPlan(Argument<Object> argument, boolean validated, List<MemberPlan> members) {

    /**
     * The prepared validation of a member of a property.
     *
     * @param member The member
     * @param argument Its argument, with its own metadata and the type-use annotations of its type
     * @param cascaded Whether it cascades the value
     */
    record MemberPlan(BeanPropertyMember<Object, ?> member, Argument<Object> argument, boolean cascaded) {
    }
}
