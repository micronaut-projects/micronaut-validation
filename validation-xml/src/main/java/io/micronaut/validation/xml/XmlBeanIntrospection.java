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
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The description of a bean the annotation processor never introspected, read from what a
 * constraint mapping declares about it: the mapping names the fields and the getters that carry
 * constraints, and each of them names through its generic signature the type it holds and the type
 * arguments a container element constraint is declared for.
 *
 * <p>A mapping that does not ignore the annotations of a member asks for them as well, and a type
 * with no introspection has them nowhere but on the member itself, so the constraints and the
 * cascades the member declares are read from it - those, and nothing else: what a type declares
 * beyond the members a mapping names is what a generated introspection, or the reflection module
 * where it is present, describes. The annotations this description carries are the ones {@link
 * XmlValidationMetadataProvider} then merges the mapping into, or replaces where the mapping
 * ignores them, exactly as it does over a generated introspection.
 *
 * @param <T> The bean type
 * @since 5.2
 */
@Internal
final class XmlBeanIntrospection<T> implements BeanIntrospection<T> {

    private final Class<T> beanType;
    private final List<BeanProperty<T, Object>> properties;

    XmlBeanIntrospection(Class<T> beanType, Map<String, ValidationDeclaration> members) {
        this.beanType = beanType;
        List<BeanProperty<T, Object>> mapped = new ArrayList<>(members.size());
        for (Map.Entry<String, ValidationDeclaration> member : members.entrySet()) {
            mapped.add(new XmlBeanProperty(member.getKey(), member.getValue()));
        }
        this.properties = List.copyOf(mapped);
    }

    @Override
    public Class<T> getBeanType() {
        return beanType;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return AnnotationMetadata.EMPTY_METADATA;
    }

    @Override
    public Collection<BeanProperty<T, Object>> getBeanProperties() {
        return properties;
    }

    @Override
    public Collection<BeanProperty<T, Object>> getIndexedProperties(Class<? extends Annotation> annotationType) {
        return List.of();
    }

    @Override
    public Optional<BeanProperty<T, Object>> getIndexedProperty(Class<? extends Annotation> annotationType, String annotationValue) {
        return Optional.empty();
    }

    @Override
    public Builder<T> builder() {
        throw new UnsupportedOperationException(unsupported());
    }

    @Override
    public T instantiate() {
        throw new UnsupportedOperationException(unsupported());
    }

    @Override
    public T instantiate(boolean strictNullable, Object... arguments) {
        throw new UnsupportedOperationException(unsupported());
    }

    private String unsupported() {
        return "Cannot instantiate a bean described by validation XML alone: " + beanType.getName();
    }

    /** A property the mapping names, read through the field or the getter it names it by. */
    private final class XmlBeanProperty implements BeanProperty<T, Object> {

        private final String name;
        private final ValidationDeclaration source;
        private final Argument<Object> argument;
        private final AnnotationMetadata annotationMetadata;

        @SuppressWarnings("unchecked")
        private XmlBeanProperty(String name, ValidationDeclaration source) {
            this.name = name;
            this.source = source;
            this.argument = (Argument<Object>) source.argument().withName(name);
            this.annotationMetadata = source.metadata();
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
            return annotationMetadata;
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
            throw new UnsupportedOperationException(readOnly());
        }

        @Override
        public T withValue(T bean, @Nullable Object value) {
            throw new UnsupportedOperationException(readOnly());
        }

        private String readOnly() {
            return "Cannot write a property described by validation XML alone: " + beanType.getName() + "." + name;
        }
    }
}
