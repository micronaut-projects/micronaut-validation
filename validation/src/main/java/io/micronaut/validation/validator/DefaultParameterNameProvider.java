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
import jakarta.validation.ParameterNameProvider;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link ParameterNameProvider} implementation.
 *
 * @author graemerocher
 * @since 5.1
 */
@Internal
public final class DefaultParameterNameProvider implements ParameterNameProvider {

    @Override
    public List<String> getParameterNames(Constructor<?> constructor) {
        return getParameterNames((Executable) constructor);
    }

    @Override
    public List<String> getParameterNames(Method method) {
        return getParameterNames((Executable) method);
    }

    private static List<String> getParameterNames(Executable executable) {
        Parameter[] parameters = executable.getParameters();
        List<String> names = new ArrayList<>(parameters.length);
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            names.add(parameter.isNamePresent() ? parameter.getName() : "arg" + i);
        }
        return names;
    }
}
