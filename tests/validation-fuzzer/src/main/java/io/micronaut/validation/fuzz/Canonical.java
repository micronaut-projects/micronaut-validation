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
package io.micronaut.validation.fuzz;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Implementation-neutral renderings of violations and descriptors, so that the results of two validators
 * can be compared.
 */
final class Canonical {

    private Canonical() {
    }

    /**
     * A violation: what identifies it, and the details compared once two violations are matched.
     *
     * @param identity The path (kinds, names, indexes and keys) and the constraint type
     * @param details The other observable attributes
     * @param leafBeanType The simple name of the leaf bean type
     * @param lastProperty The last property name of the path, if any
     * @param constraint The constraint simple name
     */
    record Violation(String identity, Map<String, String> details, String leafBeanType, String lastProperty, String constraint) {

        String describe() {
            return identity + " " + details;
        }
    }

    static Violation violation(ConstraintViolation<?> v) {
        StringBuilder identity = new StringBuilder();
        StringBuilder nodeDetails = new StringBuilder();
        String lastProperty = null;
        for (Path.Node node : v.getPropertyPath()) {
            if (!identity.isEmpty()) {
                identity.append('/');
                nodeDetails.append('/');
            }
            identity.append(node.getKind()).append('(').append(node.getName()).append(')');
            if (node.getIndex() != null) {
                identity.append("[i=").append(node.getIndex()).append(']');
            }
            if (node.getKey() != null) {
                identity.append("[k=").append(render(node.getKey())).append(']');
            }
            nodeDetails.append(node.getKind()).append("{iter=").append(node.isInIterable());
            appendContainer(node, nodeDetails);
            nodeDetails.append('}');
            if (node.getKind() == ElementKind.PROPERTY) {
                lastProperty = node.getName();
            }
        }
        ConstraintDescriptor<?> descriptor = v.getConstraintDescriptor();
        String constraint = descriptor.getAnnotation().annotationType().getSimpleName();
        identity.append(" :: @").append(constraint);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("template", v.getMessageTemplate());
        details.put("message", v.getMessage());
        details.put("invalidValue", render(v.getInvalidValue()));
        details.put("leafBean", render(v.getLeafBean()));
        details.put("rootBeanClass", v.getRootBeanClass() == null ? "null" : v.getRootBeanClass().getSimpleName());
        details.put("pathString", v.getPropertyPath().toString());
        details.put("nodes", nodeDetails.toString());
        details.put("descriptor", descriptor(descriptor));
        details.put("attributeOrder", rawAttributes(descriptor));
        if (v.getExecutableParameters() != null || v.getExecutableReturnValue() != null) {
            details.put("executableParameters", render(v.getExecutableParameters()));
            details.put("executableReturnValue", render(v.getExecutableReturnValue()));
        }
        details.put("rootBean", v.getRootBean() == null ? "null" : v.getRootBean().getClass().getSimpleName());
        String leafType = v.getLeafBean() != null ? v.getLeafBean().getClass().getSimpleName()
            : v.getRootBeanClass() == null ? null : v.getRootBeanClass().getSimpleName();
        return new Violation(identity.toString(), details, leafType, lastProperty, constraint);
    }

    private static void appendContainer(Path.Node node, StringBuilder sb) {
        Class<?> containerClass = null;
        Integer typeArgumentIndex = null;
        switch (node.getKind()) {
            case PROPERTY -> {
                Path.PropertyNode n = node.as(Path.PropertyNode.class);
                containerClass = n.getContainerClass();
                typeArgumentIndex = n.getTypeArgumentIndex();
            }
            case BEAN -> {
                Path.BeanNode n = node.as(Path.BeanNode.class);
                containerClass = n.getContainerClass();
                typeArgumentIndex = n.getTypeArgumentIndex();
            }
            case CONTAINER_ELEMENT -> {
                Path.ContainerElementNode n = node.as(Path.ContainerElementNode.class);
                containerClass = n.getContainerClass();
                typeArgumentIndex = n.getTypeArgumentIndex();
            }
            case PARAMETER -> sb.append(",index=").append(node.as(Path.ParameterNode.class).getParameterIndex());
            case CROSS_PARAMETER, RETURN_VALUE, METHOD, CONSTRUCTOR -> sb.append(",name=").append(node.getName());
            default -> { }
        }
        if (containerClass != null || typeArgumentIndex != null) {
            sb.append(",container=").append(containerClass == null ? "null" : containerClass.getSimpleName())
                .append('#').append(typeArgumentIndex);
        }
    }

    static String descriptor(ConstraintDescriptor<?> d) {
        return descriptor(d, true);
    }

    /**
     * The attributes of a descriptor as declared, class arrays in declaration order.
     */
    static String rawAttributes(ConstraintDescriptor<?> d) {
        Map<String, String> attributes = new TreeMap<>();
        d.getAttributes().forEach((k, v) -> attributes.put(k, render(v)));
        return "@" + d.getAnnotation().annotationType().getSimpleName() + attributes
            + d.getComposingConstraints().stream().map(Canonical::rawAttributes).sorted().toList();
    }

    /**
     * @param sortArrays Whether the elements of the class arrays among the attributes are sorted: the order the
     * annotation declares them in is compared separately
     */
    static String descriptor(ConstraintDescriptor<?> d, boolean sortArrays) {
        Map<String, String> attributes = new TreeMap<>();
        d.getAttributes().forEach((k, v) -> attributes.put(k, sortArrays && v instanceof Class<?>[] array
            ? Arrays.stream(array).map(Class::getSimpleName).sorted().toList().toString() : render(v)));
        return "@" + d.getAnnotation().annotationType().getSimpleName()
            + "{groups=" + classes(d.getGroups())
            + ", payload=" + classes(d.getPayload())
            + ", single=" + d.isReportAsSingleViolation()
            + ", appliesTo=" + d.getValidationAppliesTo()
            + ", template=" + d.getMessageTemplate()
            + ", attributes=" + attributes
            + ", composing=" + d.getComposingConstraints().stream().map(Canonical::descriptor).sorted().toList()
            + "}";
    }

    private static String simpleName(Class<?> type) {
        return type == null ? "null" : type.getSimpleName();
    }

    static String classes(Collection<? extends Class<?>> classes) {
        return classes.stream().map(Class::getSimpleName).sorted().toList().toString();
    }

    static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value.getClass().isArray()) {
            List<String> elements = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                elements.add(render(Array.get(value, i)));
            }
            return elements.toString();
        }
        if (value instanceof Class<?> c) {
            return c.getSimpleName();
        }
        if (value instanceof Enum<?> e && e.getDeclaringClass().getName().startsWith("jakarta.validation")) {
            return e.name();
        }
        return String.valueOf(value);
    }

    /**
     * The constraints a bean descriptor declares, one line per element.
     */
    static List<String> beanDescriptor(BeanDescriptor descriptor) {
        return beanDescriptor(descriptor, true);
    }

    static List<String> beanDescriptor(BeanDescriptor descriptor, boolean sortArrays) {
        SORT.set(sortArrays);
        List<String> lines = new ArrayList<>();
        lines.add("bean " + descriptor.getElementClass().getSimpleName() + " constrained=" + descriptor.isBeanConstrained()
            + " constructors=" + descriptor.getConstrainedConstructors().size()
            + " methods=" + descriptor.getConstrainedMethods(jakarta.validation.metadata.MethodType.GETTER,
            jakarta.validation.metadata.MethodType.NON_GETTER).size());
        lines.add("  class constraints=" + constraints(descriptor.getConstraintDescriptors()));
        descriptor.getConstrainedProperties().stream()
            .sorted((a, b) -> a.getPropertyName().compareTo(b.getPropertyName()))
            .forEach(p -> lines.add("  property " + property(p)));
        return lines;
    }

    /**
     * The constraint descriptors of the class and of each constrained property, keyed by "class" or the property name.
     */
    static Map<String, List<String>> constraintsByElement(BeanDescriptor descriptor) {
        Map<String, List<String>> result = new TreeMap<>();
        result.put("<class>", descriptor.getConstraintDescriptors().stream().map(Canonical::descriptor).sorted().toList());
        for (PropertyDescriptor p : descriptor.getConstrainedProperties()) {
            result.put(p.getPropertyName(), p.getConstraintDescriptors().stream().map(Canonical::descriptor).sorted().toList());
        }
        return result;
    }

    /**
     * The container element descriptors of each constrained property.
     */
    static Map<String, String> containersByProperty(BeanDescriptor descriptor) {
        Map<String, String> result = new TreeMap<>();
        for (PropertyDescriptor p : descriptor.getConstrainedProperties()) {
            result.put(p.getPropertyName(), containers(p.getConstrainedContainerElementTypes()));
        }
        return result;
    }

    private static String property(PropertyDescriptor p) {
        return p.getPropertyName() + " type=" + p.getElementClass().getSimpleName() + " cascaded=" + p.isCascaded()
            + " conversions=" + conversions(p.getGroupConversions())
            + " constraints=" + constraints(p.getConstraintDescriptors())
            + " containers=" + containers(p.getConstrainedContainerElementTypes());
    }

    private static String containers(Set<ContainerElementTypeDescriptor> descriptors) {
        return descriptors.stream()
            .map(c -> simpleName(c.getContainerClass()) + "#" + c.getTypeArgumentIndex() + "<" + simpleName(c.getElementClass())
                + ">{cascaded=" + c.isCascaded() + ", conversions=" + conversions(c.getGroupConversions())
                + ", constraints=" + constraints(c.getConstraintDescriptors())
                + ", nested=" + containers(c.getConstrainedContainerElementTypes()) + "}")
            .sorted().toList().toString();
    }

    private static String conversions(Set<GroupConversionDescriptor> conversions) {
        return conversions.stream().map(c -> c.getFrom().getSimpleName() + "->" + c.getTo().getSimpleName()).sorted().toList().toString();
    }

    private static final ThreadLocal<Boolean> SORT = ThreadLocal.withInitial(() -> true);

    private static String constraints(Set<ConstraintDescriptor<?>> descriptors) {
        return descriptors.stream().map(d -> descriptor(d, SORT.get())).sorted().collect(Collectors.toList()).toString();
    }

    /**
     * An executable descriptor, its parts keyed by name so that the differing ones can be named.
     */
    static Map<String, String> executable(jakarta.validation.metadata.ExecutableDescriptor d) {
        Map<String, String> parts = new LinkedHashMap<>();
        if (d == null) {
            parts.put("descriptor", "null");
            return parts;
        }
        parts.put("name", d.getName());
        parts.put("constrainedParameters", String.valueOf(d.hasConstrainedParameters()));
        parts.put("constrainedReturnValue", String.valueOf(d.hasConstrainedReturnValue()));
        parts.put("constraints", constraints(d.getConstraintDescriptors()));
        for (jakarta.validation.metadata.ParameterDescriptor p : d.getParameterDescriptors()) {
            parts.put("parameter " + p.getIndex(), p.getName() + " type=" + simpleName(p.getElementClass()) + " cascaded=" + p.isCascaded()
                + " conversions=" + conversions(p.getGroupConversions()) + " constraints=" + constraints(p.getConstraintDescriptors())
                + " containers=" + containers(p.getConstrainedContainerElementTypes()));
        }
        jakarta.validation.metadata.CrossParameterDescriptor cross = d.getCrossParameterDescriptor();
        parts.put("crossParameter", cross == null ? "null" : "type=" + simpleName(cross.getElementClass())
            + " constraints=" + constraints(cross.getConstraintDescriptors()));
        jakarta.validation.metadata.ReturnValueDescriptor ret = d.getReturnValueDescriptor();
        parts.put("returnValue", ret == null ? "null" : "type=" + simpleName(ret.getElementClass()) + " cascaded=" + ret.isCascaded()
            + " conversions=" + conversions(ret.getGroupConversions()) + " constraints=" + constraints(ret.getConstraintDescriptors())
            + " containers=" + containers(ret.getConstrainedContainerElementTypes()));
        return parts;
    }

    static String exception(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            sb.append(" <- ").append(cause.getClass().getName());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    static String exceptionMessage(Throwable t) {
        String message = t.getMessage();
        Throwable cause = t;
        while ((message == null || message.isBlank()) && cause.getCause() != null) {
            cause = cause.getCause();
            message = cause.getMessage();
        }
        return message == null ? "" : message.lines().limit(3).collect(Collectors.joining(" | "));
    }

    static String groups(Class<?>[] groups) {
        return groups.length == 0 ? "[]" : Arrays.stream(groups).map(Class::getSimpleName).toList().toString();
    }
}
