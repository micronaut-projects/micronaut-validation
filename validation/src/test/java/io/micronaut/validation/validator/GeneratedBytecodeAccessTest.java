package io.micronaut.validation.validator;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedBytecodeAccessTest {
    @Test
    void sourcegenOutputCannotHideRuntimeReflection() throws Exception {
        Path classes = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        var violations = new ArrayList<String>();
        int checked = 0;
        var generated = new java.util.LinkedHashMap<String, byte[]>();
        try (var files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.getFileName().toString().contains("$ValidationAnnotations")
                && path.toString().endsWith(".class")).toList()) {
                generated.put(file.toString(), Files.readAllBytes(file));
            }
        }
        Path builtins = Path.of(Class.forName("io.micronaut.validation.metadata.generated.Size$ValidationAnnotations",
            false, getClass().getClassLoader()).getProtectionDomain().getCodeSource().getLocation().toURI());
        int applicationCount = generated.size();
        if (Files.isDirectory(builtins)) {
            try (var files = Files.walk(builtins)) {
                for (Path file : files.filter(path -> path.getFileName().toString().contains("$ValidationAnnotations")
                    && path.toString().endsWith(".class")).toList()) {
                    generated.put(file.toString(), Files.readAllBytes(file));
                }
            }
        } else {
            try (var jar = new JarFile(builtins.toFile())) {
                for (var file : jar.stream().filter(file -> file.getName().contains("$ValidationAnnotations") && file.getName().endsWith(".class")).toList()) {
                    try (var bytes = jar.getInputStream(file)) {
                        generated.put(file.getName(), bytes.readAllBytes());
                    }
                }
            }
        }
        assertTrue(applicationCount > 0, "Application generated metadata must be inspected");
        assertTrue(generated.size() > applicationCount, "Built-in generated metadata must be inspected");
        {
            for (var entry : generated.entrySet()) {
                String file = entry.getKey();
                checked++;
                new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                if (owner.startsWith("java/lang/reflect/") || owner.startsWith("kotlin/reflect/")
                                    || owner.equals("java/lang/Class") && (method.startsWith("getDeclared")
                                        || method.startsWith("getGeneric") || Set.of("forName", "newInstance", "getMethods", "getFields",
                                        "getConstructors", "getInterfaces", "getSuperclass", "getEnumConstants", "getAnnotation",
                                        "getAnnotations", "getAnnotationsByType").contains(method))
                                    || owner.equals("io/micronaut/inject/annotation/AnnotationMetadataSupport") && method.equals("buildAnnotation")
                                    || owner.equals("io/micronaut/core/annotation/AnnotationSource") && method.startsWith("synthesize")) {
                                    violations.add(file + ":" + name + " calls " + owner + "." + method);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertTrue(checked > 0, "The audit must inspect actual generated annotation bytecode");
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }
}
