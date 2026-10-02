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
import io.micronaut.core.type.Argument;
import io.micronaut.el.ELMethod;
import io.micronaut.el.ELMethodExecutor;
import jakarta.el.ELContext;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/** Direct dispatch for the locale-aware formatter required by Jakarta Validation. */
@Internal
public final class ValidationFormatterExecutor implements ELMethodExecutor {
    /** Creates the executor discovered by the EL interpreter. */
    public ValidationFormatterExecutor() { }

    @Override
    public @Nullable ELMethod resolve(ELContext context, @Nullable Object base, @Nullable Object method,
                                     Argument<?> @Nullable [] argumentTypes, Object @Nullable [] arguments) {
        return base instanceof ElMessageInterpolator.LocaleFormatter && "format".equals(method)
            && (arguments == null || arguments.length > 0) ? FormatMethod.INSTANCE : null;
    }

    private enum FormatMethod implements ELMethod {
        INSTANCE;

        @Override
        public String getName() {
            return "format";
        }

        @Override
        public Argument<?> getReturnType() {
            return Argument.STRING;
        }

        @Override
        public Argument<?>[] getArguments() {
            return new Argument<?>[]{Argument.STRING, Argument.of(Object[].class)};
        }

        @Override
        public boolean isVarArgs() {
            return true;
        }

        @Override
        public Object invoke(ELContext context, @Nullable Object base, Object @Nullable [] arguments) {
            Objects.requireNonNull(arguments, "Formatter arguments");
            var formatter = (ElMessageInterpolator.LocaleFormatter) Objects.requireNonNull(base, "Formatter");
            String format = (String) context.convertToType(arguments[0], String.class);
            Object[] values = arguments.length == 2 && arguments[1] instanceof Object[] array
                ? array : Arrays.copyOfRange(arguments, 1, arguments.length);
            return formatter.format(format, values);
        }

        @Override
        public boolean isReusable() {
            return true;
        }
    }
}
