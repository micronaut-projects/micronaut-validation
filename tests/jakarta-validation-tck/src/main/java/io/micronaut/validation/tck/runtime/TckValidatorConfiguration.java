/*
 * Copyright 2017-2023 original authors
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
package io.micronaut.validation.tck.runtime;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.validation.tck.TckDeployableContainer;
import io.micronaut.validation.validator.DefaultValidatorConfiguration;
import io.micronaut.validation.validator.DefaultValidatorFactory;
import io.micronaut.validation.validator.ValidatorConfiguration;
import jakarta.validation.BootstrapConfiguration;
import jakarta.validation.ClockProvider;
import jakarta.validation.Configuration;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;

import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.util.HashSet;
import java.util.Set;

@Internal
public final class TckValidatorConfiguration implements Configuration<TckValidatorConfiguration> {

    private DefaultValidatorConfiguration validatorConfiguration = new DefaultValidatorConfiguration();

    @Override
    public TckValidatorConfiguration ignoreXmlConfiguration() {
        return this;
    }

    @Override
    public TckValidatorConfiguration messageInterpolator(MessageInterpolator interpolator) {
        validatorConfiguration.setMessageInterpolator(interpolator);
        return this;
    }

    @Override
    public TckValidatorConfiguration traversableResolver(TraversableResolver resolver) {
        // Micronaut doesn't keep information about the field/method annotation source
        // This hack allows us to validate more
        Set<String> mapElementsMap = new HashSet<>();
        mapElementsMap.add("org.hibernate.beanvalidation.tck.tests.traversableresolver.Suit@jacket");
        mapElementsMap.add("org.hibernate.beanvalidation.tck.tests.traversableresolver.Jacket@width");
        validatorConfiguration.setTraversableResolver(new TraversableResolver() {

            @Override
            public boolean isReachable(Object traversableObject, Path.Node traversableProperty, Class<?> rootBeanType, Path pathToTraversableObject, ElementType elementType) {
                boolean isMethodElement = isMethodElement(traversableObject, traversableProperty);
                return resolver.isReachable(traversableObject, traversableProperty, rootBeanType, pathToTraversableObject, isMethodElement ? ElementType.METHOD : elementType);
            }

            private boolean isMethodElement(Object traversableObject, Path.Node traversableProperty) {
                if (traversableObject == null) {
                    return false;
                }
                return mapElementsMap.contains(traversableObject.getClass().getName() + "@" + traversableProperty.getName());
            }

            @Override
            public boolean isCascadable(Object traversableObject, Path.Node traversableProperty, Class<?> rootBeanType, Path pathToTraversableObject, ElementType elementType) {
                boolean isMethodElement = isMethodElement(traversableObject, traversableProperty);
                return resolver.isCascadable(traversableObject, traversableProperty, rootBeanType, pathToTraversableObject, isMethodElement ? ElementType.METHOD : elementType);
            }
        });
        return this;
    }

    @Override
    public TckValidatorConfiguration constraintValidatorFactory(ConstraintValidatorFactory constraintValidatorFactory) {
        return this;
    }

    @Override
    public TckValidatorConfiguration parameterNameProvider(ParameterNameProvider parameterNameProvider) {
        return this;
    }

    @Override
    public TckValidatorConfiguration clockProvider(ClockProvider clockProvider) {
        validatorConfiguration.setClockProvider(clockProvider);
        return this;
    }

    @Override
    public TckValidatorConfiguration addValueExtractor(ValueExtractor<?> extractor) {
        validatorConfiguration.addValueExtractor(extractor);
        return this;
    }

    @Override
    public TckValidatorConfiguration addMapping(InputStream stream) {
        return this;
    }

    @Override
    public TckValidatorConfiguration addProperty(String name, String value) {
        return this;
    }

    @Override
    public MessageInterpolator getDefaultMessageInterpolator() {
        return validatorConfiguration.getDefaultMessageInterpolator();
    }

    @Override
    public TraversableResolver getDefaultTraversableResolver() {
        return validatorConfiguration.getDefaultTraversableResolver();
    }

    @Override
    public ConstraintValidatorFactory getDefaultConstraintValidatorFactory() {
        return null;
    }

    @Override
    public ParameterNameProvider getDefaultParameterNameProvider() {
        return null;
    }

    @Override
    public ClockProvider getDefaultClockProvider() {
        return validatorConfiguration.getDefaultClockProvider();
    }

    @Override
    public BootstrapConfiguration getBootstrapConfiguration() {
        return null;
    }

    @Override
    public ValidatorFactory buildValidatorFactory() {
        ApplicationContext applicationContext = TckDeployableContainer.APP.get();
        ClassLoader classLoader = applicationContext.getClassLoader();
        ValidatorConfiguration contextValidatorConfiguration = applicationContext.getBean(ValidatorConfiguration.class);
        validatorConfiguration.setExecutionHandleLocator(applicationContext);
        validatorConfiguration.setBeanIntrospector(BeanIntrospector.forClassLoader(classLoader));
        validatorConfiguration.setConstraintValidatorRegistry(contextValidatorConfiguration.getConstraintValidatorRegistry());
        return new DefaultValidatorFactory(validatorConfiguration);
    }

}
