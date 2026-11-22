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
package io.micronaut.validation.validator.metadata;

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.RegisterAnnotations;
import io.micronaut.validation.annotation.InEnum;
import io.micronaut.validation.annotation.InList;
import io.micronaut.validation.annotation.NotInEnum;
import io.micronaut.validation.annotation.NotInList;
import io.micronaut.validation.annotation.URL;
import io.micronaut.validation.annotation.UniqueElements;
import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.NegativeOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Asks the annotation processor for an {@link io.micronaut.core.annotation.AnnotationBuilder} of each constraint
 * the specification and this module define, so that an instance of one, which
 * {@link jakarta.validation.metadata.ConstraintDescriptor#getAnnotation()} and the {@code initialize} method of a
 * Jakarta validator take, is built without the reflection module.
 *
 * @since 5.3.0
 */
@Internal
@RegisterAnnotations({
    AssertFalse.class, AssertTrue.class,
    DecimalMax.class, DecimalMin.class,
    Digits.class, Email.class,
    Future.class, FutureOrPresent.class,
    Max.class, Min.class,
    Negative.class, NegativeOrZero.class,
    NotBlank.class, NotEmpty.class,
    NotNull.class, Null.class,
    Past.class, PastOrPresent.class,
    Pattern.class, Positive.class,
    PositiveOrZero.class, Size.class,
    InEnum.class, InList.class,
    NotInEnum.class, NotInList.class,
    URL.class, UniqueElements.class
})
final class ConstraintAnnotationBuilders {

    private ConstraintAnnotationBuilders() {
    }
}
