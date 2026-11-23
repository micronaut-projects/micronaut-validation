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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.HibernateValidator;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Compiles a hand-written type with the fuzzer support types and runs a call on both validators: the reproducers
 * of the differences the fuzzer reports are written with it.
 */
public final class Differential {

    private static int counter;

    private Differential() {
    }

    /**
     * The canonical results of a call on both validators.
     *
     * @param micronaut What Micronaut reported, one line per violation, or the exception
     * @param hibernate What Hibernate Validator reported
     */
    public record Result(List<String> micronaut, List<String> hibernate) {

        /**
         * @return Whether both validators agree
         */
        public boolean agree() {
            return micronaut.equals(hibernate);
        }

        @Override
        public String toString() {
            return "Micronaut:\n  " + String.join("\n  ", micronaut) + "\nHibernate:\n  " + String.join("\n  ", hibernate);
        }
    }

    /**
     * Compiles the body of a class named {@code Subject}, with the package declaration and imports added, and
     * runs the call.
     *
     * @param body The source after the imports: the type {@code Subject}, any other non-public types, and a
     * {@code public static Object instance()} method inside {@code Subject}
     * @param call The call, given the validator and the instance
     * @return The canonical results
     * @throws Exception If compilation fails
     */
    public static synchronized Result run(String body, BiFunction<Validator, Object, Set<? extends ConstraintViolation<?>>> call) throws Exception {
        String pkg = "fuzz.repro" + (counter++);
        Path dir = Files.createTempDirectory("fuzz-repro");
        try {
            Map<String, String> files = new LinkedHashMap<>(SourceWriter.supportSources(pkg));
            files.put(pkg.replace('.', '/') + "/Subject.java", "package " + pkg + ";\n\n" + SourceWriter.IMPORTS + "\n" + body);
            List<Path> paths = new ArrayList<>();
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path p = dir.resolve("src").resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue());
                paths.add(p);
            }
            Path classes = dir.resolve("classes");
            CorpusCompiler.Result compiled = CorpusCompiler.compile(paths, classes, true);
            if (!compiled.success()) {
                throw new IllegalStateException("Compilation failed:\n" + compiled.output());
            }
            Files.writeString(classes.resolve("ValidationMessages.properties"), SourceWriter.messageBundle());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, Differential.class.getClassLoader())) {
                Thread.currentThread().setContextClassLoader(loader);
                try (ApplicationContext context = ApplicationContext.builder().classLoader(loader).deduceEnvironment(false)
                         .singletons(new ResourceBundleMessageSource("ValidationMessages", Locale.US)).start();
                     ValidatorFactory factory = Validation.byProvider(HibernateValidator.class).configure()
                         .externalClassLoader(loader).buildValidatorFactory()) {
                    Object instance = loader.loadClass(pkg + ".Subject").getMethod("instance").invoke(null);
                    Validator mn = context.getBean(io.micronaut.validation.validator.Validator.class);
                    return new Result(describe(() -> call.apply(mn, instance)), describe(() -> call.apply(factory.getValidator(), instance)));
                } finally {
                    Thread.currentThread().setContextClassLoader(previous);
                }
            }
        } finally {
            FuzzRunner.deleteRecursively(dir);
        }
    }

    private static List<String> describe(java.util.function.Supplier<Set<? extends ConstraintViolation<?>>> call) {
        try {
            return call.get().stream()
                .map(v -> Canonical.violation(v).identity() + " message=" + v.getMessage() + " value=" + Canonical.render(v.getInvalidValue()))
                .sorted().toList();
        } catch (RuntimeException e) {
            return List.of("throws " + e.getClass().getSimpleName() + ": " + Canonical.exceptionMessage(e));
        }
    }
}
