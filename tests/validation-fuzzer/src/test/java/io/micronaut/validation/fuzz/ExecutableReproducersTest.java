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
import jakarta.validation.Validator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * Reproducers of the differences the fuzzer found in method and constructor validation.
 */
@Tag("reproducer")
class ExecutableReproducersTest {

    private static Method method(Object bean, String name) {
        return Arrays.stream(bean.getClass().getMethods()).filter(m -> m.getName().equals(name) && !m.isBridge()).findFirst().orElseThrow();
    }

    private static Class<?> type(Object bean, String name) {
        try {
            return Class.forName(bean.getClass().getPackageName() + "." + name, false, bean.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object call(Object bean, String staticMethod) {
        try {
            return bean.getClass().getMethod(staticMethod).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<? extends ConstraintViolation<?>> parameters(Validator v, Object bean, String name, Object[] args, Class<?>... groups) {
        return v.forExecutables().validateParameters(bean, method(bean, name), args, groups);
    }

    private static void assertAgree(Differential.Result result) {
        System.out.println(result);
        Assertions.assertTrue(result.agree(), result::toString);
    }

    @Test
    void customConstraintDeclaredOnAMethodNeedsNoReflection() throws Exception {
        // a custom constraint declared on a method or a constructor, cross-parameter or on the return value, had no
        // generated annotation builder: validating it required micronaut-validation-reflection
        assertAgree(Differential.run("""
            @Introspected
            public class Subject {
                @Executable
                @FuzzParams(mode = 0) public void call(String a, String b) {
                }
                @Executable
                @FuzzLength(max = 2) public String name() {
                    return "abc";
                }
                public static Object instance() { return new Subject(); }
            }
            """, (v, bean) -> {
            var result = new java.util.LinkedHashSet<ConstraintViolation<?>>(parameters(v, bean, "call", new Object[] {"a", null}));
            result.addAll(v.forExecutables().validateReturnValue(bean, method(bean, "name"), "abc"));
            return result;
        }));
    }

    @Test
    void crossParameterViolationWithoutNodeEndsInTheCrossParameterNode() throws Exception {
        // a violation a cross-parameter validator builds without adding a node was reported on the method node
        assertAgree(Differential.run("""
            @Introspected
            public class Subject {
                @Executable
                @FuzzParams(mode = 3) public void call(String a) {
                }
                public static Object instance() { return new Subject(); }
            }
            """, (v, bean) -> parameters(v, bean, "call", new Object[] {"a"})));
    }

    @Test
    void overrideInheritingACascadedContainerElementReturnValue() throws Exception {
        // an override declaring nothing inherited the cascade of a type argument of the return value, and was then
        // rejected for marking the return value cascaded a second time
        assertAgree(Differential.run("""
            @Introspected
            public class Subject extends Base {
                @Override
                @Executable
                public List<Item> items() {
                    return null;
                }
                public static Object instance() { return new Subject(); }
                public static Object value() { return List.of(new Item()); }
            }

            @Introspected
            class Base {
                @Executable
                public List<@Valid Item> items() {
                    return null;
                }
            }

            @Introspected
            class Item {
                @NotNull public String name;
                public String getName() { return name; }
            }
            """, (v, bean) -> v.forExecutables().validateReturnValue(bean, method(bean, "items"), call(bean, "value"))));
    }

    @Test
    @Disabled("not fixed: the hierarchy of an executable merges the constraints of its levels without their origin")
    void constraintsOfAnInterfaceMethodBelongToTheInterfaceGroup() throws Exception {
        // a constraint declared on an interface belongs to the interface as an implicit group (section 5.4.2):
        // validating the interface group must apply it
        assertAgree(Differential.run("""
            @Introspected
            public class Subject implements Api {
                @Override
                @Executable
                public void call(String a) {
                }
                public static Object instance() { return new Subject(); }
            }

            interface Api {
                void call(@NotNull String a);
            }
            """, (v, bean) -> parameters(v, bean, "call", new Object[] {null}, type(bean, "Api"))));
    }

    @Test
    void cascadedParameterUnderAGroupSequence() throws Exception {
        assertAgree(Differential.run("""
            @Introspected
            public class Subject {
                public Subject() {
                }
                public Subject(@Valid @NotNull(groups = G3.class) @Null(groups = G3.class) Item item) {
                }
                public static Object instance() { return new Subject(); }
                public static Object value() { return new Item(); }
            }

            @Introspected
            class Item {
                @NotNull(groups = G3.class) public String name;
                public String getName() { return name; }
                @Override public String toString() { return "item"; }
            }
            """, (v, bean) -> {
            // a cascaded parameter failed the first step of the sequence with the violations its bean has in the
            // later steps, so the constraints of the parameter in those steps were skipped
            @SuppressWarnings("unchecked")
            Constructor<Object> c = (Constructor<Object>) Arrays.stream(bean.getClass().getConstructors()).filter(k -> k.getParameterCount() == 1).findFirst().orElseThrow();
            return v.forExecutables().validateConstructorParameters(c, new Object[] {call(bean, "value")}, type(bean, "Seq"));
        }));
    }


    @Test
    @Disabled("not fixed: telling a cross-parameter constraint of the declaration apart needs its validators")
    void crossParameterConstraintAddedByAnImplementation() throws Exception {
        // a cross-parameter constraint is a parameter constraint: an implementation cannot add one (section 5.6.5)
        assertAgree(Differential.run("""
            @Introspected
            public class Subject implements Api {
                @Override
                @Executable
                @FuzzParams(mode = 0) public void call(String a) {
                }
                public static Object instance() { return new Subject(); }
            }

            interface Api {
                void call(@NotNull String a);
            }
            """, (v, bean) -> parameters(v, bean, "call", new Object[] {"a"})));
    }

    @Test
    void returnValueGroupConversionAppliesToTypeArgumentCascade() throws Exception {
        // the group conversion of a return value did not apply to the beans cascaded through its type argument
        assertAgree(Differential.run("""
            @Introspected
            public class Subject {
                @Executable
                @Valid @ConvertGroup(from = Default.class, to = G1.class) public List<@Valid Item> items() {
                    return null;
                }
                @Valid @ConvertGroup(from = Default.class, to = G1.class) public List<@Valid Item> list = List.of(new Item());
                public List<Item> getList() { return list; }
                public static Object instance() { return new Subject(); }
                public static Object value() { return List.of(new Item()); }
            }

            @Introspected
            class Item {
                @NotNull private String p;
                public String getP() { return p; }
                @Override public String toString() { return "item"; }
            }
            """, (v, bean) -> {
            var all = new java.util.LinkedHashSet<ConstraintViolation<?>>(v.forExecutables().validateReturnValue(bean, method(bean, "items"), call(bean, "value")));
            all.addAll(v.validate(bean));
            return all;
        }));
    }

    @Test
    void methodMarkedValidReturningAList() throws Exception {
        // @Valid on a method returning a list cascades into the elements; it reported the list as not introspected
        assertAgree(Differential.run("""
            @Introspected
            public class Subject {
                @Executable
                @Valid public List<Item> items() {
                    return null;
                }
                public static Object instance() { return new Subject(); }
                public static Object value() { return List.of(new Item()); }
            }

            @Introspected
            class Item {
                @Negative private int p = 5;
                public int getP() { return p; }
                @Override public String toString() { return "item"; }
            }
            """, (v, bean) -> v.forExecutables().validateReturnValue(bean, method(bean, "items"), call(bean, "value"))));
    }
}
