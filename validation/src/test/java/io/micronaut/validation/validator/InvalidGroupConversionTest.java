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
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.beans.BeanIntrospection;
import jakarta.validation.ConstraintDeclarationException;
import jakarta.validation.Valid;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A relationship converting the same group twice is a constraint declaration error wherever it is met, not a
 * failure of a map or an optional.
 */
class InvalidGroupConversionTest {

    @Test
    void convertingAGroupTwiceIsADeclarationError() {
        var validator = new DefaultValidator(new DefaultValidatorConfiguration());
        BeanIntrospection<Holder> introspection = BeanIntrospection.getIntrospection(Holder.class);
        AnnotationMetadata metadata = introspection.getRequiredProperty("item", Object.class).getAnnotationMetadata();
        var context = new DefaultConstraintValidatorContext<>(validator, introspection, null, BeanValidationContext.DEFAULT);
        assertThrows(ConstraintDeclarationException.class, () -> context.convertGroups(metadata));
        // and validating the bean reports it as well
        assertThrows(ConstraintDeclarationException.class, () -> validator.validate(new Holder(new Holder(null))));
    }

    interface First {
    }

    interface Second {
    }

    @Introspected
    record Holder(@Valid
                  @ConvertGroup(from = Default.class, to = First.class)
                  @ConvertGroup(from = Default.class, to = Second.class)
                  @Nullable Holder item) {
    }
}
