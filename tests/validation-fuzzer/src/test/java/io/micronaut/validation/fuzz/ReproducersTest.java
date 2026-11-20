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

import jakarta.validation.metadata.BeanDescriptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Minimal reproducers of the differences the fuzzer found between Micronaut Validation and Hibernate Validator.
 * Each test asserts that both validators agree, so a test fails if its difference comes back. The {@code test} task
 * runs them; the {@code reproducers} task runs them alone, with their output.
 */
@Tag("reproducer")
class ReproducersTest {

    private static Differential.Result validate(String body) throws Exception {
        return Differential.run(body, (v, bean) -> v.validate(bean));
    }

    private static Class<?> group(Object bean, String name) {
        try {
            return Class.forName(bean.getClass().getPackageName() + "." + name, false, bean.getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertAgree(Differential.Result result) {
        System.out.println(result);
        Assertions.assertTrue(result.agree(), result::toString);
    }

    // ---------------------------------------------------------------- messages

    @Test
    void decimalMaxExclusiveDefaultMessage() throws Exception {
        // the default message of an exclusive bound said "or equal to"
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @DecimalMax(value = "10", inclusive = false) public Integer value = 10;
                @DecimalMin(value = "10", inclusive = false) public Integer other = 10;
                public Integer getValue() { return value; }
                public Integer getOther() { return other; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    @Test
    void unbalancedBraceInMessageTemplate() throws Exception {
        // a template with an unbalanced '{' is kept as written; it lost the brace
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @NotNull(message = "unclosed {min") public String value;
                public String getValue() { return value; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    @Test
    void containerElementPathString() throws Exception {
        Differential.Result result = Differential.run("""
            @Introspected
            public class Subject {
                private List<@NotNull String> list = Arrays.asList((String) null);
                private Map<@NotNull String, @NotNull String> map = new HashMap<>();
                {
                    map.put(null, null);
                }
                public List<String> getList() { return list; }
                public Map<String, String> getMap() { return map; }
                public static Object instance() { return new Subject(); }
            }
            """, (v, bean) -> {
            throw new IllegalStateException(v.validate(bean).stream().map(c -> c.getPropertyPath().toString()).sorted().toList().toString());
        });
        assertAgree(result);
    }

    // ---------------------------------------------------------------- container elements of cascaded beans

    @Test
    void containerElementConstraintUnderPropertyLevelValid() throws Exception {
        // @Valid on the container property (not on the type argument) dropped the container element node
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @Valid private Set<@Null Item> items = new LinkedHashSet<>(List.of(new Item()));
                @Valid private Map<String, @NotNull Item> map = new HashMap<>();
                {
                    map.put("k", null);
                }
                public Set<Item> getItems() { return items; }
                public Map<String, Item> getMap() { return map; }
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Item {
            }
            """));
    }

    @Test
    void mapKeyNodeOfMapInsideCascadedArrayElement() throws Exception {
        // the <map key> node reported Object[] as its container class instead of Map
        Differential.Result result = Differential.run("""
            @Introspected
            public class Subject {
                @Valid private Holder[] holders = {new Holder()};
                public Holder[] getHolders() { return holders; }
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Holder {
                private Map<@NotNull String, String> map = new HashMap<>();
                {
                    map.put(null, "v");
                }
                public Map<String, String> getMap() { return map; }
            }
            """, (v, bean) -> {
            throw new IllegalStateException(v.validate(bean).stream().map(c -> Canonical.violation(c).details().get("nodes")).sorted().toList().toString());
        });
        assertAgree(result);
    }

    @Test
    void validGetterArrayInheritedBySubclass() throws Exception {
        // Micronaut reported a violation for a cascaded array a getter marks with @Valid
        Differential.Result result = Differential.run("""
            @Introspected
            public class Subject extends Base {
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Base {
                private Item[] items = {};
                @Valid public Item[] getItems() { return items; }
            }

            @Introspected
            class Item {
            }
            """, (v, bean) -> {
            throw new IllegalStateException(v.validate(bean).stream()
                .map(c -> c.getPropertyPath() + " template=" + c.getMessageTemplate() + " value=" + Canonical.render(c.getInvalidValue())).sorted().toList().toString());
        });
        assertAgree(result);
    }

    // ---------------------------------------------------------------- inheritance

    @Test
    void sameConstraintOnSuperclassFieldAndOverridingGetter() throws Exception {
        assertAgree(validate("""
            @Introspected
            public class Subject extends Base {
                @Override
                @AssertTrue public boolean isFlag() { return super.isFlag(); }
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Base {
                @AssertTrue private boolean flag;
                public boolean isFlag() { return flag; }
                public void setFlag(boolean flag) { this.flag = flag; }
            }
            """));
    }

    @Test
    void overridingGetterDuplicatesSuperclassContainerElementConstraints() throws Exception {
        assertAgree(validate("""
            @Introspected
            public class Subject extends Base {
                @Override
                @NotNull public Map<String, List<String>> getMap() { return super.getMap(); }
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Base {
                private Map<String, List<String>> map = Map.of("k", List.of(""));
                public Map<String, List<@NotBlank String>> getMap() { return map; }
            }
            """));
    }

    @Test
    void classLevelConstraintOfTheSameTypeOnSuperclassAndSubclass() throws Exception {
        // validation reported both, the descriptor of the subclass only its own
        assertMetadataAgrees("""
            @Introspected
            @FuzzClassCheck(mode = 1)
            public class Subject extends Base {
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            @FuzzClassCheck(mode = 0)
            class Base {
            }
            """);
    }

    @Test
    void classLevelConstraintDoesNotInheritTheGroupsOfTheSuperclassOne() throws Exception {
        // the constraint of the subclass took the groups of the superclass constraint of the same type
        String body = """
            @Introspected
            @FuzzClassCheck(mode = 3)
            public class Subject extends Base {
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            @FuzzClassCheck(mode = 0, groups = {Default.class, G2.class})
            class Base {
            }
            """;
        assertMetadataAgrees(body);
        assertAgree(Differential.run(body, (v, bean) -> v.validate(bean, group(bean, "G2"))));
    }

    @Test
    void validateValueOnSubclassOverridingGetter() throws Exception {
        // validateValue ignored the constraint of the superclass field once a subclass overrides the getter
        assertAgree(Differential.run("""
            @Introspected
            public class Subject extends Base {
                @Override
                @Null public String getName() { return super.getName(); }
                public static Object instance() { return new Subject(); }
            }

            @Introspected
            class Base {
                @Null(message = "base message") private String name;
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """, (v, bean) -> {
            @SuppressWarnings("unchecked")
            Class<Object> type = (Class<Object>) bean.getClass();
            return v.validateValue(type, "name", "x");
        }));
    }

    // ---------------------------------------------------------------- composed constraints

    @Test
    void declaredConstraintNextToComposedOneOfTheSameType() throws Exception {
        // FuzzComposed composes @NotNull and @Size(min = 2, max = 5), max overridden; the declared @Size(2..4) passes
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @FuzzComposed(max = 2) @Size(min = 2, max = 4) private String sized = "xxxx";
                @NotNull @FuzzComposed private String nulled;
                public String getSized() { return sized; }
                public String getNulled() { return nulled; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    @Test
    void twoCompositesComposingTheSameConstraint() throws Exception {
        // both compose @Size(min = 2, max = 5): the single-violation composite was lost
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @FuzzComposed @FuzzComposedSingle private String value = "abcdefgh";
                public String getValue() { return value; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    @Test
    void emptyCharSequenceIsAValidEmail() throws Exception {
        // an empty String passes @Email in both; an empty CharSequence property failed in Micronaut
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @Email private CharSequence value = "";
                private Set<@Email CharSequence> values = Set.of("");
                @Email(regexp = ".+@example[.]com") private String matched = "";
                public CharSequence getValue() { return value; }
                public String getMatched() { return matched; }
                public Set<CharSequence> getValues() { return values; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    // ---------------------------------------------------------------- numbers

    @Test
    void negativeZero() throws Exception {
        assertAgree(validate("""
            @Introspected
            public class Subject {
                @Negative private Float negative = -0.0f;
                @PositiveOrZero private double positiveOrZero = -0.0;
                public Float getNegative() { return negative; }
                public double getPositiveOrZero() { return positiveOrZero; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    // ---------------------------------------------------------------- custom violations

    @Test
    void beanNodeAddedToClassLevelViolation() throws Exception {
        // FuzzClassCheck(mode = 5) adds a violation with addBeanNode() at class level, which lost its second bean node
        assertAgree(validate("""
            @Introspected
            @FuzzClassCheck(mode = 5)
            public class Subject {
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    // ---------------------------------------------------------------- invalid definitions

    @Test
    void sizeWithMinGreaterThanMax() throws Exception {
        // both reject the definition, whatever the value; the messages are each implementation's own
        assertSameException(validate("""
            @Introspected
            public class Subject {
                @Size(min = 3, max = 1) private String value = "ab";
                public String getValue() { return value; }
                public static Object instance() { return new Subject(); }
            }
            """));
        assertSameException(validate("""
            @Introspected
            public class Subject {
                @Size(min = -1) private String value;
                @Digits(integer = -1, fraction = 0) private Integer number;
                public String getValue() { return value; }
                public Integer getNumber() { return number; }
                public static Object instance() { return new Subject(); }
            }
            """));
        assertSameException(validate("""
            @Introspected
            public class Subject {
                @Pattern(regexp = "(") private String value;
                public String getValue() { return value; }
                public static Object instance() { return new Subject(); }
            }
            """));
        assertSameException(validate("""
            @Introspected
            public class Subject {
                @DecimalMax("abc") private Integer value;
                public Integer getValue() { return value; }
                public static Object instance() { return new Subject(); }
            }
            """));
    }

    private static void assertSameException(Differential.Result result) {
        System.out.println(result);
        java.util.function.Function<java.util.List<String>, java.util.List<String>> type =
            lines -> lines.stream().map(l -> l.replaceAll("^(throws \\w+):.*", "$1")).toList();
        Assertions.assertTrue(result.hibernate().get(0).startsWith("throws "), result::toString);
        Assertions.assertEquals(type.apply(result.hibernate()), type.apply(result.micronaut()), result::toString);
    }

    // ---------------------------------------------------------------- metadata

    private static Differential.Result describe(String body) throws Exception {
        return Differential.run(body, (v, bean) -> {
            throw new MetadataResult(v.getConstraintsForClass(bean.getClass()));
        });
    }

    @Test
    void recordComponentConstraintsAreDescribedOnce() throws Exception {
        String body = """
            @Introspected
            public record Subject(@NotNull @Size(max = 2) String name) {
                public static Object instance() { return new Subject("abc"); }
            }
            """;
        assertMetadataAgrees(body);
    }

    @Test
    void groupsAttributeKeepsDeclarationOrder() throws Exception {
        assertMetadataAgrees("""
            @Introspected
            public class Subject {
                @Email(groups = {G1.class, G3.class}) private String name;
                public String getName() { return name; }
                public static Object instance() { return new Subject(); }
            }
            """);
    }

    private static void assertMetadataAgrees(String body) throws Exception {
        Differential.Result result = describe(body);
        // the call throws to carry the descriptor out: compare the rendered descriptors
        System.out.println(result);
        Assertions.assertEquals(result.hibernate(), result.micronaut());
    }

    /**
     * Carries a rendered descriptor out of {@link Differential#run} as its "exception".
     */
    static final class MetadataResult extends RuntimeException {
        MetadataResult(BeanDescriptor descriptor) {
            super(String.join(" | ", Canonical.beanDescriptor(descriptor, false)));
        }
    }
}
