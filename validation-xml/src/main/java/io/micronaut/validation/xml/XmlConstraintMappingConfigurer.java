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
package io.micronaut.validation.xml;

import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.bootstrap.ConstraintMappingConfigurer;
import io.micronaut.validation.validator.ConstraintValidatorOverrides;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.ReflectionSupport;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.micronaut.validation.xml.XmlMapping.ConstraintDefinition;

/**
 * Applies XML constraint mappings to a validator configuration: the mapped beans through an
 * {@link XmlBeanIntrospector} decorating the configured introspector, and the constraint definitions,
 * which belong to no bean, as the validators of those constraints.
 *
 * @since 5.3.0
 */
@Internal
public final class XmlConstraintMappingConfigurer implements ConstraintMappingConfigurer {
    /** Constructor for service discovery. */
    public XmlConstraintMappingConfigurer() { }

    @Override
    public void configure(DefaultValidatorConfiguration configuration, Set<InputStream> streams) {
        ReflectionSupport reflectionSupport = configuration.getReflectionSupport();
        XmlMappingParser parser = new XmlMappingParser(reflectionSupport, streams);
        if (!parser.beanMappings.isEmpty()) {
            configuration.setBeanIntrospector(new XmlBeanIntrospector(configuration.getBeanIntrospector(), parser.beanMappings));
        }
        if (!parser.constraintDefinitions.isEmpty()) {
            configuration.setConstraintValidatorOverrides(validatorOverrides(reflectionSupport, Map.copyOf(parser.constraintDefinitions)));
        }
    }

    private static ConstraintValidatorOverrides validatorOverrides(ReflectionSupport reflectionSupport,
                                                                 Map<String, ConstraintDefinition> definitions) {
        return (constraintType, declared) -> {
            ConstraintDefinition definition = definitions.get(constraintType.getName());
            if (definition == null) {
                return null;
            }
            List<Class<?>> validators = new ArrayList<>();
            if (definition.includeExistingValidators()) {
                validators.addAll(declared);
                if (validators.isEmpty()) {
                    validators.addAll(reflectionSupport.declaredValidators(constraintType));
                }
            }
            validators.addAll(definition.validatorClasses());
            return List.copyOf(validators);
        };
    }
}
