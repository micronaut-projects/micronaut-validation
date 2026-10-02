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
package io.micronaut.validation.differential;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test-only normalization: sorted lists retain multiplicity and never depend on graph identity. */
final class OutcomeNormalizer {
    private OutcomeNormalizer() { }

    static String violations(Collection<? extends ConstraintViolation<?>> violations) {
        List<String> outcomes = new ArrayList<>();
        for (ConstraintViolation<?> violation : violations) {
            List<String> nodes = new ArrayList<>();
            for (Path.Node node : violation.getPropertyPath()) {
                String typed = "";
                switch (node.getKind()) {
                    case PARAMETER -> typed = ":parameter=" + node.as(Path.ParameterNode.class).getParameterIndex();
                    case CONTAINER_ELEMENT -> {
                        var element = node.as(Path.ContainerElementNode.class);
                        typed = ":container=" + value(element.getContainerClass()) + ":argument=" + element.getTypeArgumentIndex();
                    }
                    case BEAN -> {
                        var element = node.as(Path.BeanNode.class);
                        typed = ":container=" + value(element.getContainerClass()) + ":argument=" + element.getTypeArgumentIndex();
                    }
                    case PROPERTY -> {
                        var element = node.as(Path.PropertyNode.class);
                        typed = ":container=" + value(element.getContainerClass()) + ":argument=" + element.getTypeArgumentIndex();
                    }
                    case METHOD -> typed = ":arguments=" + value(node.as(Path.MethodNode.class).getParameterTypes());
                    case CONSTRUCTOR -> typed = ":arguments=" + value(node.as(Path.ConstructorNode.class).getParameterTypes());
                    default -> { }
                }
                nodes.add(node.getKind() + ":" + node.getName() + ":iterable=" + node.isInIterable()
                    + ":index=" + node.getIndex() + ":key=" + value(node.getKey()) + typed);
            }
            outcomes.add("path=" + nodes + ";template=" + value(violation.getMessageTemplate())
                + ";message=" + value(violation.getMessage()) + ";invalid=" + value(violation.getInvalidValue())
                + ";descriptor=" + descriptor(violation.getConstraintDescriptor()));
        }
        outcomes.sort(String::compareTo);
        return outcomes.toString();
    }

    private static String descriptor(ConstraintDescriptor<?> descriptor) {
        List<String> composition = descriptor.getComposingConstraints().stream().map(OutcomeNormalizer::descriptor).sorted().toList();
        return annotationType(descriptor) + ":attributes=" + value(descriptor.getAttributes())
            + ":groups=" + sorted(descriptor.getGroups()) + ":payload=" + sorted(descriptor.getPayload())
            + ":target=" + descriptor.getValidationAppliesTo() + ":unwrap=" + descriptor.getValueUnwrapping()
            + ":single=" + descriptor.isReportAsSingleViolation() + ":composition=" + composition;
    }

    private static String annotationType(ConstraintDescriptor<?> descriptor) {
        if (descriptor.getClass().getName().startsWith("io.micronaut.validation.")) {
            try {
                var method = descriptor.getClass().getMethod("getType");
                method.setAccessible(true);
                return ((Class<?>) method.invoke(descriptor)).getName();
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Micronaut descriptor must expose its metadata type", e);
            }
        }
        return descriptor.getAnnotation().annotationType().getName();
    }

    static String exception(Throwable failure) {
        Throwable category = failure;
        while (category.getCause() != null && !(category instanceof ValidationException)) { category = category.getCause(); }
        if (category instanceof ValidationException && category.getMessage() != null
            && (category.getMessage().contains("micronaut-validation-reflection") || rootMessage(category).contains("@ReflectiveAccess"))) {
            return "CAPABILITY_ERROR";
        }
        return "EXCEPTION:" + category.getClass().getName();
    }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) { failure = failure.getCause(); }
        return String.valueOf(failure.getMessage());
    }

    private static String sorted(Collection<?> values) { return values.stream().map(OutcomeNormalizer::value).sorted().toList().toString(); }

    static String value(Object value) {
        return value(value, new java.util.IdentityHashMap<>());
    }

    private static String value(Object value, java.util.IdentityHashMap<Object, Boolean> ancestors) {
        if (value == null) { return "null"; }
        if (value instanceof Class<?> type) { return "class:" + type.getName(); }
        if (value instanceof Enum<?> constant) { return "enum:" + constant.getDeclaringClass().getName() + ":" + constant.name(); }
        if (value instanceof CharSequence sequence) { return "text:" + sequence.toString().codePoints().mapToObj(Integer::toHexString).toList(); }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof TemporalAccessor) {
            return value.getClass().getName() + ":" + value;
        }
        if (value instanceof Annotation annotation) {
            Map<String, String> attributes = new TreeMap<>();
            for (var member : annotation.annotationType().getDeclaredMethods()) {
                try { attributes.put(member.getName(), value(member.invoke(annotation))); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
            return annotation.annotationType().getName() + attributes;
        }
        if (value.getClass().isArray() || value instanceof Map<?, ?> || value instanceof Collection<?>) {
            if (ancestors.put(value, Boolean.TRUE) != null) { return "cycle:" + value.getClass().getName(); }
        }
        if (value.getClass().isArray()) {
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) { elements.add(value(Array.get(value, i), new java.util.IdentityHashMap<>(ancestors))); }
            return elements.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(value(key, new java.util.IdentityHashMap<>(ancestors))
                + "=" + value(item, new java.util.IdentityHashMap<>(ancestors))));
            entries.sort(String::compareTo);
            return entries.toString();
        }
        if (value instanceof Collection<?> collection) { return collection.stream().map(item -> value(item, new java.util.IdentityHashMap<>(ancestors))).toList().toString(); }
        // User model instances are represented by their declared type, never their identity or toString.
        return "bean:" + value.getClass().getName();
    }
}
