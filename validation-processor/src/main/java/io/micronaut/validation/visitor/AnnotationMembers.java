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
package io.micronaut.validation.visitor;

import io.micronaut.inject.visitor.VisitorContext;

import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import java.util.LinkedHashMap;
import java.util.List;

/** Annotation declarations exposed as properties by Kotlin use their JVM member names and types. */
final class AnnotationMembers {
    private AnnotationMembers() { }

    static List<MethodElement> of(ClassElement annotation, VisitorContext context) {
        var members = new LinkedHashMap<String, MethodElement>();
        annotation.getMethods().stream().filter(method -> !method.isStatic() && method.getParameters().length == 0
            && method.getDeclaringType().getName().equals(annotation.getName()))
            .forEach(method -> members.put(method.getName(), method));
        for (var property : annotation.getBeanProperties()) {
            ClassElement type = property.getGenericType();
            if (type.getName().equals("kotlin.reflect.KClass")) {
                ClassElement mapped = context.getClassElement(Class.class.getName()).orElseThrow().withTypeArguments(type.getTypeArguments());
                type = type.isArray() ? mapped.toArray() : mapped;
            }
            members.putIfAbsent(property.getName(), MethodElement.of(annotation, property.getAnnotationMetadata(), type, type, property.getName()));
        }
        return List.copyOf(members.values());
    }
}
