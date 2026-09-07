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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.validation.validator.GenericArguments;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import jakarta.validation.ValidationException;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The description of a bean the annotation processor never introspected, read from what a constraint mapping
 * declares about it: the mapping names the fields and the getters that carry constraints, and each of them
 * names through its generic signature the type it holds and the type arguments a container element constraint
 * is declared for.
 *
 * <p>A mapping that does not ignore the annotations of a member asks for them as well, and a type with no
 * introspection has them nowhere but on the member itself, so the constraints and the cascades the member
 * declares are read from it - those, and nothing else: what a type declares beyond the members a mapping names
 * is what a generated introspection, or the reflection module where it is present, describes. The annotations
 * this description carries are the ones {@link XmlValidationMetadataProvider} then merges the mapping into, or
 * replaces where the mapping ignores them, exactly as it does over a generated introspection.</p>
 *
 * @param <T> The bean type
 * @since 5.2
 */
@Internal
final class XmlBeanIntrospection<T> implements BeanIntrospection<T> {

    private final Class<T> beanType;
    private final List<BeanProperty<T, Object>> properties;

    XmlBeanIntrospection(Class<T> beanType, Map<String, AnnotatedElement> members) {
        this.beanType = beanType;
        List<BeanProperty<T, Object>> mapped = new ArrayList<>(members.size());
        for (Map.Entry<String, AnnotatedElement> member : members.entrySet()) {
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

    /**
     * The generic signature of a member as the argument the validator reads: the type it holds, the type
     * arguments it binds, and on each of those the constraints and the cascade declared for it, which is what
     * a container element constraint of the mapping is declared alongside.
     *
     * @param name The name of the argument, the name of the type it erases to when {@code null}
     * @param type The annotated type
     * @return The argument
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Argument<?> argumentOf(@Nullable String name, AnnotatedType type) {
        Argument<?> structure = GenericArguments.of(type.getType());
        Argument<?>[] typeParameters = structure.getTypeParameters();
        if (type instanceof AnnotatedParameterizedType parameterized) {
            AnnotatedType[] annotatedArguments = parameterized.getAnnotatedActualTypeArguments();
            typeParameters = new Argument[annotatedArguments.length];
            for (int i = 0; i < annotatedArguments.length; i++) {
                typeParameters[i] = argumentOf(null, annotatedArguments[i]);
            }
        }
        return Argument.of(
            (Class) structure.getType(),
            name == null ? structure.getName() : name,
            declaredMetadata(type),
            typeParameters
        );
    }

    /**
     * The constraints and the cascade an element declares, as metadata: the annotations of a type a mapping
     * names are read for what the specification reads them for, and the rest of what the element carries is
     * not this module's to describe.
     */
    private static AnnotationMetadata declaredMetadata(AnnotatedElement element) {
        MutableAnnotationMetadata metadata = null;
        for (Annotation annotation : element.getAnnotations()) {
            metadata = addDeclared(metadata, annotation);
        }
        return metadata == null ? AnnotationMetadata.EMPTY_METADATA : metadata;
    }

    @Nullable
    private static MutableAnnotationMetadata addDeclared(@Nullable MutableAnnotationMetadata metadata, Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        for (Annotation repeated : repeated(annotation, annotationType)) {
            metadata = addDeclared(metadata, repeated);
        }
        boolean constraint = annotationType.isAnnotationPresent(Constraint.class);
        if (!constraint && annotationType != Valid.class) {
            return metadata;
        }
        MutableAnnotationMetadata declared = metadata == null ? new MutableAnnotationMetadata() : metadata;
        declared.addDeclaredAnnotation(annotationType.getName(), memberValues(annotation, annotationType));
        if (constraint) {
            declared.addDeclaredStereotype(List.of(annotationType.getName()), Constraint.class.getName(), Map.of());
        }
        return declared;
    }

    /**
     * The constraints a repeatable container holds, the container itself carrying none.
     */
    private static Annotation[] repeated(Annotation annotation, Class<? extends Annotation> annotationType) {
        Method value;
        try {
            value = annotationType.getDeclaredMethod("value"); // reflection: the constraints a repeatable container holds
        } catch (NoSuchMethodException e) {
            return new Annotation[0];
        }
        if (!value.getReturnType().isArray() || !value.getReturnType().getComponentType().isAnnotation()) {
            return new Annotation[0];
        }
        return (Annotation[]) invoke(value, annotation);
    }

    /**
     * The members a constraint annotation sets away from their defaults: a default is what
     * {@code AnnotationMetadata} reads for a member the declaration leaves out, the same as for a constraint
     * an XML mapping declares.
     */
    private static Map<CharSequence, Object> memberValues(Annotation annotation, Class<? extends Annotation> annotationType) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        for (Method member : annotationType.getDeclaredMethods()) { // reflection: the members a constraint annotation declares
            if (member.getParameterCount() > 0) {
                continue;
            }
            Object value = invoke(member, annotation);
            if (!Objects.deepEquals(value, member.getDefaultValue())) {
                values.put(member.getName(), memberValue(value));
            }
        }
        return values;
    }

    private static Object memberValue(Object value) {
        if (value instanceof Annotation nested) {
            return new AnnotationValue<>(nested.annotationType().getName(), memberValues(nested, nested.annotationType()));
        }
        if (value instanceof Annotation[] nested) {
            AnnotationValue<?>[] converted = new AnnotationValue[nested.length];
            for (int i = 0; i < nested.length; i++) {
                converted[i] = (AnnotationValue<?>) memberValue(nested[i]);
            }
            return converted;
        }
        return value;
    }

    private static Object invoke(Method member, Annotation annotation) {
        try {
            return member.invoke(annotation);
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Cannot read the annotation member "
                + annotation.annotationType().getName() + "." + member.getName(), e);
        }
    }

    /**
     * A property the mapping names, read through the field or the getter it names it by.
     */
    private final class XmlBeanProperty implements BeanProperty<T, Object> {

        private final String name;
        private final AnnotatedElement source;
        private final Argument<Object> argument;
        private final AnnotationMetadata annotationMetadata;

        @SuppressWarnings("unchecked")
        private XmlBeanProperty(String name, AnnotatedElement source) {
            this.name = name;
            this.source = source;
            if (source instanceof AccessibleObject accessible) {
                accessible.setAccessible(true); // reflection: the value of a member a mapping names, whatever its visibility
            }
            AnnotatedType annotatedType = source instanceof Field field
                ? field.getAnnotatedType()
                : ((Method) source).getAnnotatedReturnType(); // reflection: the type a getter a mapping names holds
            this.argument = (Argument<Object>) argumentOf(name, annotatedType);
            this.annotationMetadata = declaredMetadata(source);
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
        public Object get(T bean) {
            try {
                return source instanceof Field field ? field.get(bean) : ((Method) source).invoke(bean);
            } catch (ReflectiveOperationException e) {
                throw new ValidationException("Cannot read the property a validation XML mapping names: "
                    + beanType.getName() + "." + name, e);
            }
        }

        @Override
        public void set(T bean, Object value) {
            throw new UnsupportedOperationException(readOnly());
        }

        @Override
        public T withValue(T bean, Object value) {
            throw new UnsupportedOperationException(readOnly());
        }

        private String readOnly() {
            return "Cannot write a property described by validation XML alone: " + beanType.getName() + "." + name;
        }
    }
}
