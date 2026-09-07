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
package io.micronaut.validation.annotation;

import io.micronaut.core.annotation.Internal;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The types a {@link jakarta.validation.ConstraintValidator} implementation binds, recorded by the annotation
 * processor in the introspection of the implementation: the constraint it validates and the type it
 * validates. The validator reads them from the introspection instead of from the generic signature of the
 * class, so an introspected validator is described without reflection.
 *
 * <p>Not meant to be declared by hand: the processor sets it on every introspected implementation whose
 * type arguments resolve.</p>
 *
 * @author Denis Stepanov
 * @since 5.2
 */
@Internal
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ConstraintValidatorTypes {

    /**
     * @return The constraint annotation type the validator validates
     */
    Class<? extends Annotation> constraint();

    /**
     * @return The type of the values the validator validates
     */
    Class<?> target();
}
