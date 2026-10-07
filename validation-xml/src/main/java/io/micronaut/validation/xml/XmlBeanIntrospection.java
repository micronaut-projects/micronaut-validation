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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanMethod;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.beans.BeanTypeHierarchy;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * XML declarations exposed through the same arguments and metadata as a generated introspection.
 *
 * @param <T> The bean type
 */
@Internal
final class XmlBeanIntrospection<T> implements BeanIntrospection<T> {
    private final Class<T> beanType;
    private final AnnotationMetadata metadata;
    private final List<BeanProperty<T, Object>> properties;
    private final List<BeanMethod<T, Object>> methods;
    private final List<BeanConstructor<T>> constructors;
    private final @Nullable BeanIntrospection<T> original;

    XmlBeanIntrospection(Class<T> beanType, AnnotationMetadata metadata,
                         Map<String, ValidationDeclaration> properties,
                         Collection<ValidationDeclaration> methods,
                         Collection<ValidationDeclaration> constructors,
                         @Nullable BeanIntrospection<T> original) {
        this.beanType = beanType;
        this.metadata = metadata;
        this.properties = properties.entrySet().stream()
            .<BeanProperty<T, Object>>map(entry -> new XmlBeanProperty(entry.getKey(), entry.getValue())).toList();
        this.methods = methods.stream().<BeanMethod<T, Object>>map(XmlBeanMethod::new).toList();
        this.constructors = constructors.stream().<BeanConstructor<T>>map(XmlBeanConstructor::new).toList();
        this.original = original;
    }

    @Override
    public Class<T> getBeanType() {
        return beanType;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return metadata;
    }

    @Override
    public Collection<BeanProperty<T, Object>> getBeanProperties() {
        return properties;
    }

    @Override
    public Collection<BeanMethod<T, Object>> getBeanMethods() {
        return methods;
    }

    @Override
    public List<BeanConstructor<T>> getConstructors() {
        return constructors;
    }

    @Override
    public BeanConstructor<T> getConstructor() {
        if (constructors.isEmpty()) {
            if (original != null) {
                return original.getConstructor();
            }
            throw new UnsupportedOperationException(unsupported());
        }
        return constructors.getFirst();
    }

    @Override
    public List<Argument<?>> getTypeArguments(@Nullable Class<?> superType) {
        return original == null ? List.of() : original.getTypeArguments(superType);
    }

    @Override
    public Optional<BeanTypeHierarchy> getTypeHierarchy() {
        // the hierarchy is the type's, whatever a mapping declares on it
        return original == null ? Optional.empty() : original.getTypeHierarchy();
    }

    @Override
    public Collection<BeanProperty<T, Object>> getIndexedProperties(Class<? extends Annotation> annotationType) {
        return properties.stream().filter(property -> property.getAnnotationMetadata().hasStereotype(annotationType)).toList();
    }

    @Override
    public Optional<BeanProperty<T, Object>> getIndexedProperty(Class<? extends Annotation> annotationType, String annotationValue) {
        return getIndexedProperties(annotationType).stream()
            .filter(property -> property.getAnnotationMetadata().stringValue(annotationType).filter(annotationValue::equals).isPresent())
            .findFirst();
    }

    @Override
    public Builder<T> builder() {
        if (original != null) {
            return original.builder();
        }
        throw new UnsupportedOperationException(unsupported());
    }

    @Override
    public T instantiate() {
        if (original != null) {
            return original.instantiate();
        }
        throw new UnsupportedOperationException(unsupported());
    }

    @Override
    public T instantiate(boolean strictNullable, Object... arguments) {
        if (original != null) {
            return original.instantiate(strictNullable, arguments);
        }
        throw new UnsupportedOperationException(unsupported());
    }

    private String unsupported() {
        return "XML metadata cannot instantiate " + beanType.getName();
    }

    private final class XmlBeanProperty implements BeanProperty<T, Object> {
        private final String name;
        private final ValidationDeclaration source;
        private final Argument<Object> argument;

        @SuppressWarnings("unchecked")
        private XmlBeanProperty(String name, ValidationDeclaration source) {
            this.name = name;
            this.source = source;
            this.argument = (Argument<Object>) source.argument().withName(name);
        }

        @Override

        public String getName() {
            return name;
        }

        @Override
        public BeanIntrospection<T> getDeclaringBean() {
            return XmlBeanIntrospection.this;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return argument.getAnnotationMetadata();
        }

        @Override
        public Argument<Object> asArgument() {
            return argument;
        }

        @Override
        public Class<Object> getType() {
            return argument.getType();
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public boolean isWriteOnly() {
            return false;
        }

        @Override
        public @Nullable Object get(T bean) {
            return source.read(bean);
        }

        @Override
        public void set(T bean, @Nullable Object value) {
            throw new UnsupportedOperationException(unsupported());
        }

        @Override
        public T withValue(T bean, @Nullable Object value) {
            throw new UnsupportedOperationException(unsupported());
        }
    }

    private final class XmlBeanMethod implements BeanMethod<T, Object> {
        private final ValidationDeclaration source;

        private XmlBeanMethod(ValidationDeclaration source) {
            this.source = source;
        }

        @Override
        public String getName() {
            return source.name();
        }

        @Override
        public BeanIntrospection<T> getDeclaringBean() {
            return XmlBeanIntrospection.this;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return source.metadata();
        }

        @Override
        public AnnotationMetadata getDeclaredMethodAnnotationMetadata() {
            return source.metadata();
        }

        @Override
        public Argument<?>[] getArguments() {
            return source.parameters().toArray(Argument.ZERO_ARGUMENTS);
        }

        @Override
        public ReturnType<Object> getReturnType() {
            return new ReturnType<>() {
                @Override
                @SuppressWarnings("unchecked") public Class<Object> getType() {
                    return (Class<Object>) source.argument().getType();
                }

                @Override
                public Argument<?>[] getTypeParameters() {
                    return source.argument().getTypeParameters();
                }

                @Override
                public Map<String, Argument<?>> getTypeVariables() {
                    return source.argument().getTypeVariables();
                }

                @Override
                public AnnotationMetadata getAnnotationMetadata() {
                    return source.argument().getAnnotationMetadata();
                }

                @Override
                @SuppressWarnings("unchecked") public Argument<Object> asArgument() {
                    return (Argument<Object>) source.argument();
                }
            };
        }

        @Override
        public @Nullable Object invoke(T instance, Object... arguments) {
            throw new UnsupportedOperationException("XML executable metadata is used for validation: " + source.name());
        }
    }

    private final class XmlBeanConstructor implements BeanConstructor<T> {
        private final ValidationDeclaration source;

        private XmlBeanConstructor(ValidationDeclaration source) {
            this.source = source;
        }

        @Override
        public Class<T> getDeclaringBeanType() {
            return beanType;
        }

        @Override
        public Argument<?>[] getArguments() {
            return source.parameters().toArray(Argument.ZERO_ARGUMENTS);
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return source.metadata();
        }

        @Override
        public T instantiate(@Nullable Object... arguments) {
            throw new UnsupportedOperationException(unsupported());
        }
    }
}
