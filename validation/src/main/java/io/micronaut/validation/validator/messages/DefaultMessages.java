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
package io.micronaut.validation.validator.messages;

import io.micronaut.context.StaticMessageSource;
import io.micronaut.context.annotation.Prototype;
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

/**
 * The default error messages.
 *
 * @author Denis Stepanov
 */
@Internal
@Prototype
public final class DefaultMessages extends StaticMessageSource {

    /**
     * The message suffix to use.
     */
    private static final String MESSAGE_SUFFIX = ".message";

    /**
     * Constructs the default error messages.
     */
    public DefaultMessages() {
        addMessage(AssertTrue.class.getName() + MESSAGE_SUFFIX, "must be true");
        addMessage(AssertFalse.class.getName() + MESSAGE_SUFFIX, "must be false");
        addMessage(DecimalMax.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to {value}");
        addMessage(DecimalMin.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to {value}");
        addMessage(Digits.class.getName() + MESSAGE_SUFFIX, "numeric value out of bounds (<{integer} digits>.<{fraction} digits> expected)");
        addMessage(Email.class.getName() + MESSAGE_SUFFIX, "must be a well-formed email address");
        addMessage(Future.class.getName() + MESSAGE_SUFFIX, "must be a future date");
        addMessage(FutureOrPresent.class.getName() + MESSAGE_SUFFIX, "must be a date in the present or in the future");
        addMessage(Max.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to {value}");
        addMessage(Min.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to {value}");
        addMessage(Negative.class.getName() + MESSAGE_SUFFIX, "must be less than 0");
        addMessage(NegativeOrZero.class.getName() + MESSAGE_SUFFIX, "must be less than or equal to 0");
        addMessage(NotBlank.class.getName() + MESSAGE_SUFFIX, "must not be blank");
        addMessage(NotEmpty.class.getName() + MESSAGE_SUFFIX, "must not be empty");
        addMessage(NotNull.class.getName() + MESSAGE_SUFFIX, "must not be null");
        addMessage(Null.class.getName() + MESSAGE_SUFFIX, "must be null");
        addMessage(Past.class.getName() + MESSAGE_SUFFIX, "must be a past date");
        addMessage(PastOrPresent.class.getName() + MESSAGE_SUFFIX, "must be a date in the past or in the present");
        addMessage(Pattern.class.getName() + MESSAGE_SUFFIX, "must match \"{regexp}\"");

        addMessage(Positive.class.getName() + MESSAGE_SUFFIX, "must be greater than 0");
        addMessage(PositiveOrZero.class.getName() + MESSAGE_SUFFIX, "must be greater than or equal to 0");
        addMessage(Size.class.getName() + MESSAGE_SUFFIX, "size must be between {min} and {max}");

        addMessage(Introspected.class.getName() + MESSAGE_SUFFIX, "Cannot validate {type}. No bean introspection present. Please add @Introspected to the class and ensure Micronaut annotation processing is enabled");
    }
}
