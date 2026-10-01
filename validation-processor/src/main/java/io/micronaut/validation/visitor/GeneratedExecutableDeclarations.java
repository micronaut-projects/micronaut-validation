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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.annotation.AnnotationMetadataGenUtils;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.writer.ArgumentExpUtils;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.ExpressionDef;
import io.micronaut.sourcegen.model.MethodDef;
import io.micronaut.sourcegen.model.StatementDef;
import io.micronaut.sourcegen.model.TypeDef;
import io.micronaut.validation.validator.metadata.ValidationDeclaration;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** Emits declaration metadata independently of whether a method can be invoked. */
@Internal
final class GeneratedExecutableDeclarations {
    private GeneratedExecutableDeclarations() {
    }

    static MethodDef method(ClassElement element, List<MethodElement> methods, ClassTypeDef owner, Function<String, ExpressionDef> loadClass) {
        return MethodDef.builder("methodDeclaration").addModifiers(Modifier.PUBLIC)
            .returns(ValidationDeclaration.class).addParameter("name", String.class)
            .addParameter("parameters", Class[].class).build((self, parameters) -> {
                List<StatementDef> statements = new ArrayList<>();
                for (var method : methods) {
                    if (method.isStatic() || method.isSynthetic()) {
                        continue;
                    }
                    var metadata = MutableAnnotationMetadata.of(method.getAnnotationMetadata().getDeclaredMetadata());
                    var names = parameters.getFirst().invoke("equals", List.of(TypeDef.OBJECT), TypeDef.Primitive.BOOLEAN,
                        List.of(ExpressionDef.constant(method.getName()))).isTrue();
                    var types = TypeDef.CLASS.array().instantiate(Arrays.stream(method.getParameters())
                        .map(parameter -> ExpressionDef.constant(TypeDef.erasure(parameter.getType()))).toList());
                    var signature = ClassTypeDef.of(Arrays.class).invokeStatic("equals",
                        List.of(TypeDef.of(Object[].class), TypeDef.of(Object[].class)), TypeDef.Primitive.BOOLEAN,
                        List.of(parameters.get(1), types)).isTrue();
                    ExpressionDef arguments = ArgumentExpUtils.pushBuildArgumentsForMethod(metadata, element, owner,
                        List.of(method.getParameters()), loadClass);
                    ExpressionDef argumentList = ClassTypeDef.of(List.class).invokeStatic("of",
                        List.of(TypeDef.of(Object[].class)), TypeDef.of(List.class), List.of(arguments));
                    ExpressionDef returnArgument = ArgumentExpUtils.pushCreateArgument(metadata, element, owner,
                        "<return value>", method.getGenericReturnType(), loadClass);
                    var declaration = ClassTypeDef.of(ValidationDeclaration.class).instantiate(
                        List.of(TypeDef.CLASS, TypeDef.STRING, TypeDef.of(Argument.class), TypeDef.of(AnnotationMetadata.class),
                            TypeDef.of(List.class), TypeDef.of(ValidationDeclaration.Reader.class)),
                        List.of(ExpressionDef.constant(TypeDef.erasure(element)), ExpressionDef.constant(method.getName()),
                            returnArgument, AnnotationMetadataGenUtils.instantiateNewMetadata(metadata, loadClass),
                            argumentList, ExpressionDef.nullValue()));
                    statements.add(names.and(signature).doIf(declaration.returning()));
                }
                statements.add(ExpressionDef.nullValue().returning());
                return StatementDef.multi(statements);
            });
    }
}
