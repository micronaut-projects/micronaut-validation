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

import io.micronaut.context.AnnotationReflectionUtils;
import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.inject.reflection.ReflectionExecutableMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * The adapters of the specification API that names an executable by its {@link Method} or
 * {@link Constructor}: the generated metadata is used when it describes the named executable, and the
 * reflection bridge of micronaut-core otherwise — the one reflective step an API defined on
 * {@code java.lang.reflect} imposes.
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
final class ReflectiveExecutables {

    private ReflectiveExecutables() {
    }

    /**
     * The arguments of the constructor named by the caller. An introspection describes one constructor;
     * when the caller validates another one of the type, its arguments are read from the constructor itself.
     *
     * @param introspection The introspection of the declaring type
     * @param constructor   The constructor
     * @return The arguments
     */
    static Argument<?>[] constructorArguments(BeanIntrospection<?> introspection, Constructor<?> constructor) {
        Argument<?>[] arguments = introspection.getConstructorArguments();
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (arguments.length == parameterTypes.length) {
            boolean same = true;
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].getType() != parameterTypes[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return arguments;
            }
        }
        return AnnotationReflectionUtils.argumentsOf(constructor);
    }

    /**
     * The executable method of the method named by the caller: the one of the bean definition when the
     * declaring type is a bean, else the one of the bean introspection, generated or reflective, else one
     * read from the method itself.
     *
     * @param locator      The locator of the executable methods of the beans
     * @param introspector The introspector
     * @param method       The method
     * @param <T>          The declaring type
     * @return The executable method
     */
    @SuppressWarnings("unchecked")
    static <T> ExecutableMethod<T, Object> executableMethod(ExecutionHandleLocator locator,
                                                            BeanIntrospector introspector,
                                                            Method method) {
        Class<T> declaringType = (Class<T>) method.getDeclaringClass();
        Optional<ExecutableMethod<T, Object>> found = locator.findExecutableMethod(
            declaringType, method.getName(), method.getParameterTypes());
        if (found.isPresent()) {
            return found.get();
        }
        BeanIntrospection<T> introspection = introspector.findIntrospection(declaringType).orElse(null);
        if (introspection != null) {
            for (BeanMethod<T, Object> beanMethod : introspection.getBeanMethods()) {
                if (beanMethod.getName().equals(method.getName())
                    && Arrays.equals(Argument.toClassArray(beanMethod.getArguments()), method.getParameterTypes())) {
                    return new IntrospectedExecutableMethod<>(declaringType, beanMethod, method);
                }
            }
        }
        return new ReflectionExecutableMethod<>(declaringType, method);
    }
}
