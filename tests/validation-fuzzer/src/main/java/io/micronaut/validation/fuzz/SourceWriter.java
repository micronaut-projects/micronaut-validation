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

import io.micronaut.validation.fuzz.Model.Anno;
import io.micronaut.validation.fuzz.Model.BeanSpec;
import io.micronaut.validation.fuzz.Model.CaseSpec;
import io.micronaut.validation.fuzz.Model.PropertySpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders fuzz cases, and the support types they use, as Java sources.
 */
final class SourceWriter {

    static final String IMPORTS = """
        import io.micronaut.core.annotation.Introspected;
        import jakarta.validation.*;
        import jakarta.validation.constraints.*;
        import jakarta.validation.groups.*;
        import java.lang.annotation.*;
        import java.math.*;
        import java.time.*;
        import java.util.*;
        """;

    private static final String TARGETS = "@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE, "
        + "ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})\n@Retention(RetentionPolicy.RUNTIME)\n@Documented\n";

    private static final String MEMBERS = """
            Class<?>[] groups() default {};
            Class<? extends Payload>[] payload() default {};
        """;

    private SourceWriter() {
    }

    /**
     * The sources, by file path relative to the source root, of the support types of a package.
     */
    static Map<String, String> supportSources(String pkg) {
        Map<String, String> files = new LinkedHashMap<>();
        put(files, pkg, "G1", "public interface G1 { }");
        put(files, pkg, "G2", "public interface G2 extends G1 { }");
        put(files, pkg, "G3", "public interface G3 { }");
        put(files, pkg, "Seq", "@GroupSequence({G1.class, G3.class})\npublic interface Seq { }");
        put(files, pkg, "FuzzPayload", "public interface FuzzPayload extends Payload { }");
        put(files, pkg, "FuzzColor", "public enum FuzzColor { RED, GREEN }");
        put(files, pkg, "FuzzClassCheck", """
            @Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
            @Retention(RetentionPolicy.RUNTIME)
            @Documented
            @Constraint(validatedBy = FuzzClassCheckValidator.class)
            public @interface FuzzClassCheck {
                String message() default "class check failed {mode}";
            """ + MEMBERS + """
                int mode() default 0;
            }
            """);
        put(files, pkg, "FuzzClassCheckValidator", """
            @Introspected
            public class FuzzClassCheckValidator implements ConstraintValidator<FuzzClassCheck, Object> {
                private int mode;

                @Override
                public void initialize(FuzzClassCheck annotation) {
                    mode = annotation.mode();
                }

                @Override
                public boolean isValid(Object value, ConstraintValidatorContext context) {
                    if (value == null) {
                        return true;
                    }
                    switch (mode) {
                        case 0:
                            return false;
                        case 1:
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate("node {mode}").addPropertyNode("alpha").addConstraintViolation();
                            return false;
                        case 2:
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate("nested").addPropertyNode("alpha").addPropertyNode("beta").addConstraintViolation();
                            return false;
                        case 3:
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate("element")
                                .addPropertyNode("items").addContainerElementNode("<list element>", List.class, 0).inIterable().atIndex(2)
                                .addConstraintViolation();
                            return false;
                        case 4:
                            context.disableDefaultConstraintViolation();
                            context.buildConstraintViolationWithTemplate("keyed")
                                .addPropertyNode("map").addBeanNode().inIterable().atKey("k").addConstraintViolation();
                            return false;
                        case 5:
                            context.buildConstraintViolationWithTemplate("first").addConstraintViolation();
                            context.buildConstraintViolationWithTemplate("{mode} second").addBeanNode().addConstraintViolation();
                            return false;
                        default:
                            return true;
                    }
                }
            }
            """);
        put(files, pkg, "FuzzCheck", TARGETS + """
            @Constraint(validatedBy = FuzzCheckValidator.class)
            public @interface FuzzCheck {
                String message() default "fuzz check {minLength}";
            """ + MEMBERS + """
                int minLength() default 1;
            }
            """);
        put(files, pkg, "FuzzCheckValidator", """
            @Introspected
            public class FuzzCheckValidator implements ConstraintValidator<FuzzCheck, CharSequence> {
                private int minLength;

                @Override
                public void initialize(FuzzCheck annotation) {
                    minLength = annotation.minLength();
                }

                @Override
                public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
                    return value == null || value.length() >= minLength;
                }
            }
            """);
        put(files, pkg, "FuzzLength", TARGETS + """
            @Constraint(validatedBy = FuzzLengthValidator.class)
            public @interface FuzzLength {
                String message() default "{fuzz.length.message}";
            """ + MEMBERS + """
                int max() default 3;
            }
            """);
        put(files, pkg, "FuzzLengthValidator", """
            @Introspected
            public class FuzzLengthValidator implements ConstraintValidator<FuzzLength, CharSequence> {
                private int max;

                @Override
                public void initialize(FuzzLength annotation) {
                    max = annotation.max();
                }

                @Override
                public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
                    return value == null || value.length() <= max;
                }
            }
            """);
        put(files, pkg, "FuzzComposed", TARGETS + """
            @NotNull
            @Size(min = 2, max = 5)
            @Constraint(validatedBy = {})
            public @interface FuzzComposed {
                String message() default "composed {max}";
            """ + MEMBERS + """
                @OverridesAttribute(constraint = Size.class, name = "max")
                int max() default 5;
            }
            """);
        put(files, pkg, "FuzzComposedSingle", TARGETS + """
            @NotNull
            @Size(min = 2, max = 5)
            @ReportAsSingleViolation
            @Constraint(validatedBy = {})
            public @interface FuzzComposedSingle {
                String message() default "composed single {max}";
            """ + MEMBERS + """
                @OverridesAttribute(constraint = Size.class, name = "max")
                int max() default 5;
            }
            """);
        return files;
    }

    static String messageBundle() {
        return """
            fuzz.bundle.key=bundle text {min} and {max}
            fuzz.bundle.nested=nested {fuzz.bundle.key}
            fuzz.length.message=length must be at most {max}
            """;
    }

    private static void put(Map<String, String> files, String pkg, String name, String body) {
        files.put(pkg.replace('.', '/') + "/" + name + ".java", "package " + pkg + ";\n\n" + IMPORTS + "\n" + body);
    }

    /**
     * The sources of the beans of a case.
     */
    static Map<String, String> caseSources(String pkg, CaseSpec spec) {
        Map<String, String> files = new LinkedHashMap<>();
        for (BeanSpec bean : spec.beans) {
            put(files, pkg, bean.name, bean(bean));
        }
        return files;
    }

    static String bean(BeanSpec bean) {
        StringBuilder sb = new StringBuilder();
        sb.append("@Introspected\n");
        if (bean.redefinesDefaultGroup) {
            sb.append("@GroupSequence({G1.class, ").append(bean.name).append(".class})\n");
        }
        for (Anno a : bean.classAnnos) {
            sb.append(a.render()).append('\n');
        }
        if (bean.record) {
            sb.append("public record ").append(bean.name).append("(");
            sb.append(bean.properties.stream().map(p -> declarationAnnotations(p) + p.type.render(false, true) + " " + p.name)
                .collect(Collectors.joining(",\n        ")));
            sb.append(") {\n}\n");
            return sb.toString();
        }
        sb.append("public class ").append(bean.name);
        if (bean.parent != null) {
            sb.append(" extends ").append(bean.parent.name);
        }
        sb.append(" {\n");
        for (PropertySpec p : bean.properties) {
            sb.append("    ");
            if (!bean.getterConstraints) {
                sb.append(declarationAnnotations(p));
            }
            sb.append("private ").append(p.type.render(false, !bean.getterConstraints)).append(' ').append(p.name).append(";\n");
        }
        for (PropertySpec p : bean.properties) {
            String plain = p.type.render(false, false);
            sb.append("\n    ");
            if (bean.getterConstraints) {
                sb.append(declarationAnnotations(p));
            }
            sb.append("public ").append(p.type.render(false, bean.getterConstraints)).append(' ').append(p.getterName())
                .append("() {\n        return ").append(p.name).append(";\n    }\n");
            sb.append("\n    public void ").append(p.setterName()).append('(').append(plain).append(' ').append(p.name)
                .append(") {\n        this.").append(p.name).append(" = ").append(p.name).append(";\n    }\n");
        }
        for (PropertySpec o : bean.overrides) {
            sb.append("\n    @Override\n    ").append(declarationAnnotations(o)).append("public ").append(o.type.render(false, false))
                .append(' ').append(o.getterName()).append("() {\n        return super.").append(o.getterName()).append("();\n    }\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String declarationAnnotations(PropertySpec p) {
        StringBuilder sb = new StringBuilder();
        if (p.valid) {
            sb.append("@Valid ");
        }
        if (p.convertGroup != null) {
            sb.append("@ConvertGroup(").append(p.convertGroup).append(") ");
        }
        for (Anno a : p.annos) {
            sb.append(a.render()).append(' ');
        }
        return sb.toString();
    }

    /**
     * The class building the instances of the cases.
     */
    static String fixtures(String pkg, List<CaseSpec> cases) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(";\n\n").append(IMPORTS).append("""

            public final class Fixtures {
                @SafeVarargs
                static <T> List<T> list(T... elements) {
                    return elements == null ? new ArrayList<>(Collections.singletonList(null)) : new ArrayList<>(Arrays.asList(elements));
                }

                @SafeVarargs
                static <T> Set<T> set(T... elements) {
                    return elements == null ? new LinkedHashSet<>(Collections.singletonList(null)) : new LinkedHashSet<>(Arrays.asList(elements));
                }

                @SuppressWarnings("unchecked")
                static <K, V> Map<K, V> map(Object... keysAndValues) {
                    Map<K, V> map = new LinkedHashMap<>();
                    for (int i = 0; i < keysAndValues.length; i += 2) {
                        map.put((K) keysAndValues[i], (V) keysAndValues[i + 1]);
                    }
                    return map;
                }

                static <T> T with(T bean, java.util.function.Consumer<T> initializer) {
                    initializer.accept(bean);
                    return bean;
                }

                public static List<Object[]> cases() {
                    List<Object[]> cases = new ArrayList<>();
            """);
        for (CaseSpec spec : cases) {
            for (int i = 0; i < spec.instances.size(); i++) {
                sb.append("        cases.add(new Object[] {").append(spec.id).append(", ").append(i).append(", ")
                    .append("instance").append(spec.id).append('_').append(i).append("()});\n");
            }
        }
        sb.append("        return cases;\n    }\n");
        for (CaseSpec spec : cases) {
            for (int i = 0; i < spec.instances.size(); i++) {
                sb.append("\n    static Object instance").append(spec.id).append('_').append(i).append("() {\n        return ")
                    .append(spec.instances.get(i)).append(";\n    }\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }
}
