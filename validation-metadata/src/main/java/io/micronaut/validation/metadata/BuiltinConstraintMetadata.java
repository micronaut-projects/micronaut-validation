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
package io.micronaut.validation.metadata;

import io.micronaut.context.annotation.ClassImport;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
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

/** Compilation origin for concrete standard annotations and their declaration metadata. */
@Internal
@ClassImport(classes = {Introspected.class, AssertFalse.class, AssertTrue.class, DecimalMax.class, DecimalMin.class,
    Digits.class, Email.class, Future.class, FutureOrPresent.class, Max.class, Min.class, Negative.class,
    NegativeOrZero.class, NotBlank.class, NotEmpty.class, NotNull.class, Null.class, Past.class,
    PastOrPresent.class, Pattern.class, Positive.class, PositiveOrZero.class, Size.class})
final class BuiltinConstraintMetadata {
    private BuiltinConstraintMetadata() {
    }
}
