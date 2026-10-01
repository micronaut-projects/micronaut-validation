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

import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanPropertyMember;
import io.micronaut.validation.validator.metadata.ValidationField;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.ElementType;
import java.lang.reflect.Field;

/**
 * The default module's field-specific reflective access exception.
 *
 * @since 5.3.0
 */
@Internal
@NullMarked
final class AnnotatedFieldAccessor {
    private AnnotatedFieldAccessor() {
    }

    static <T> @Nullable Object read(BeanPropertyMember<T, ?> member, T bean) {
        if (member.getElementType() != ElementType.FIELD
            || !member.getAnnotationMetadata().booleanValue(ValidationField.class, "authorized").orElse(false)) {
            throw new ValidationException("Cannot read field " + member.getDeclaringType().getName() + "." + member.getName()
                + ": annotate this field with @ReflectiveAccess or add micronaut-validation-reflection");
        }
        try {
            Field field = member.getDeclaringType().getDeclaredField(member.getName());
            if (!field.trySetAccessible()) {
                throw new ValidationException("Cannot access annotated field " + member.getDeclaringType().getName() + "." + member.getName());
            }
            return field.get(bean);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new ValidationException("Cannot read annotated field " + member.getDeclaringType().getName() + "." + member.getName(), e);
        }
    }
}
