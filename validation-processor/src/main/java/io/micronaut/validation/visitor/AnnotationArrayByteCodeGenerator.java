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

import io.micronaut.inject.ast.Element;
import io.micronaut.inject.processing.ProcessingException;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.bytecode.ByteCodeWriter;
import io.micronaut.sourcegen.model.ClassDef;

import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.util.Map;

/**
 * Bytecode backend extension for typed, dynamically sized annotation arrays. Sourcegen 2.1 models
 * only constant array lengths. The complete provider remains a Sourcegen model; this writer
 * replaces only its named array-allocation methods with ANEWARRAY using a compile-time component
 * type. No runtime member discovery or reflective array allocation is emitted.
 */
final class AnnotationArrayByteCodeGenerator {
    private AnnotationArrayByteCodeGenerator() { }

    static void write(
            ClassDef definition,
            Map<String, String> allocators,
            VisitorContext context,
            Element origin) {
        byte[] modeled = new ByteCodeWriter().write(definition);
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(modeled)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public @Nullable MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    @Nullable String signature,
                                    @Nullable String[] exceptions) {
                                MethodVisitor method =
                                        super.visitMethod(
                                                access, name, descriptor, signature, exceptions);
                                String component = allocators.get(name);
                                if (component == null) {
                                    return method;
                                }
                                method.visitCode();
                                method.visitVarInsn(Opcodes.ILOAD, 0);
                                method.visitTypeInsn(
                                        Opcodes.ANEWARRAY, component.replace('.', '/'));
                                method.visitInsn(Opcodes.ARETURN);
                                method.visitMaxs(1, 1);
                                method.visitEnd();
                                return null;
                            }
                        },
                        0);
        try (var stream = context.visitClass(definition.getName(), origin)) {
            stream.write(writer.toByteArray());
        } catch (IOException e) {
            throw new ProcessingException(
                    origin, "Cannot emit annotation arrays for " + definition.getName(), e);
        }
    }
}
