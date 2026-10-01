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

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** Explicit Java model grammar; shrinking removes declarations and graph structure before values. */
record ModelDescription(String name, Kind kind, int minimum, int maximum, int width, boolean group, boolean permission, int reductions) {
    ModelDescription(String name, Kind kind, int minimum, int maximum, int width, boolean group, boolean permission) {
        this(name, kind, minimum, maximum, width, group, permission, 0);
    }
    enum Kind { SCALARS, CONTAINERS, REORDERED, CYCLES, COMPOSITION, DECLARATIONS, PRIVATE_FIELDS, EXECUTABLES, CUSTOM, EXTRACTORS, INHERITANCE, SEQUENCES, RECURSIVE_BOUNDS, INVALID_DECLARATIONS, XML, BOOTSTRAP }

    static ModelDescription generate(String name, RandomGenerator random) {
        int minimum = random.nextInt(0, 4);
        return new ModelDescription(name, Kind.values()[random.nextInt(Kind.values().length)], minimum,
            minimum + random.nextInt(1, 6), random.nextInt(1, 4), random.nextBoolean(), random.nextBoolean());
    }

    boolean comprehensiveOnly() { return kind == Kind.XML || kind == Kind.BOOTSTRAP || kind == Kind.EXTRACTORS; }

    boolean expectsCapability(int operation) { return kind == Kind.PRIVATE_FIELDS && !permission; }

    List<ModelDescription> smaller() {
        List<ModelDescription> models = new ArrayList<>();
        for (int remove : kind == Kind.SCALARS || kind == Kind.CONTAINERS ? new int[]{1, 2, 4, 8} : new int[]{1, 2, 4}) {
            if ((reductions & remove) == 0) { models.add(new ModelDescription(name, kind, minimum, maximum, width, group, permission, reductions | remove)); }
        }
        if (width > 0) { models.add(new ModelDescription(name, kind, minimum, maximum, 0, group, permission, reductions)); }
        if (width > 1) { models.add(new ModelDescription(name, kind, minimum, maximum, 1, group, permission, reductions)); }
        if (group) { models.add(new ModelDescription(name, kind, minimum, maximum, width, false, permission, reductions)); }
        if (minimum > 0) { models.add(new ModelDescription(name, kind, 0, maximum, width, group, permission, reductions)); }
        if (maximum > 1) { models.add(new ModelDescription(name, kind, 0, 1, width, group, permission, reductions)); }
        return models;
    }

    String source() {
        String groups = group ? ", groups = G.class" : "";
        String notNull = (reductions & 1) != 0 ? "" : "@NotNull(message=\"null\"" + groups + ")";
        String size = (reductions & 2) != 0 ? "" : "@Size(min=" + minimum + ",max=" + maximum + ",message=\"size\"" + groups + ")";
        String declarations;
        String setup;
        String operations;
        switch (kind) {
            case SCALARS -> {
                boolean auxiliary = (reductions & 8) == 0;
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { "
                    + notNull + " " + size + " public String value; " + (auxiliary
                    ? "@Min(value=" + minimum + ",message=\"min\"" + groups + ") public long number; "
                        + "@Past(message=\"past\"" + groups + ") public java.time.LocalDate date; " : "") + "}";
                setup = "Bean bean=new Bean();bean.value=text(seed);" + (auxiliary
                    ? "bean.number=switch((int)Math.floorMod(seed,5)){case 0->Long.MIN_VALUE;case 1->Long.MAX_VALUE;default->seed%9;};"
                        + "bean.date=switch((int)Math.floorMod(seed,5)){case 0->java.time.LocalDate.MIN;case 1->java.time.LocalDate.MAX;case 2->java.time.LocalDate.of(2024,2,29);default->java.time.LocalDate.of(2026,1,15).plusDays(seed%3-1);};" : "");
                operations = propertyOperations("String.class", "bean.value");
            }
            case CONTAINERS -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { "
                    + size + " public java.util.List<" + notNull + " String> value; public java.util.Map<" + notNull + " String,java.util.List<" + notNull + " String>> map; }";
                setup = "Bean bean=new Bean();bean.value=new java.util.ArrayList<>();for(int i=0;i<" + width + ";i++){bean.value.add((seed+i)%3==0?null:text(seed+i));}"
                    + "bean.map=new java.util.LinkedHashMap<>();bean.map.put(seed%2==0?null: text(seed),bean.value);";
                if ((reductions & 8) != 0) {
                    declarations = declarations.substring(0, declarations.indexOf(" public java.util.Map<")) + " }";
                    setup = setup.substring(0, setup.indexOf("bean.map="));
                }
                operations = propertyOperations("java.util.List.class", "bean.value");
            }
            case REORDERED -> {
                declarations = "@Introspected public static class Reordered<A,B> extends java.util.HashMap<B,A> {} "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { public Reordered<" + notNull + " String," + notNull + " String> value; }";
                setup = "Bean bean=new Bean();bean.value=new Reordered<>();bean.value.put(seed%2==0?null:text(seed),seed%3==0?null:text(seed+1));";
                operations = "return validator.validate(bean, selected);";
            }
            case CYCLES -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { " + notNull + " public String value; @Valid public Bean next; @Valid public java.util.List<Bean> nodes; }";
                setup = "Bean bean=new Bean();bean.value=text(seed);Bean shared=new Bean();shared.value=text(seed+1);bean.next=shared;" + ((reductions & 4) != 0 ? "" : "shared.next=bean;") + "bean.nodes=new java.util.ArrayList<>();"
                    + "for(int i=0;i<" + width + ";i++){bean.nodes.add(shared);}";
                operations = "return validator.validate(bean, selected);";
            }
            case COMPOSITION -> {
                declarations = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,java.lang.annotation.ElementType.TYPE_USE}) "
                    + "@Constraint(validatedBy={}) @NotNull(message=\"composed-null\") @Size(min=1,max=5,message=\"composed-size\") public @interface Composed { "
                    + "String message() default \"composed\"; Class<?>[] groups() default {}; Class<? extends Payload>[] payload() default {}; "
                    + "@OverridesAttribute(constraint=Size.class,name=\"min\") int minimum() default 1; } "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { @Composed(minimum=" + minimum + (group ? ",groups=G.class" : "") + ") public String value; }";
                setup = "Bean bean=new Bean();bean.value=text(seed);";
                operations = propertyOperations("String.class", "bean.value");
            }
            case DECLARATIONS -> {
                declarations = "@Introspected(accessKind={Introspected.AccessKind.FIELD,Introspected.AccessKind.METHOD}) public static class Bean { "
                    + notNull + " public String value; " + size + " public String getValue(){return value==null?\"getter\":value+\"x\";} }";
                setup = "Bean bean=new Bean();bean.value=text(seed);";
                operations = propertyOperations("String.class", "bean.value");
            }
            case PRIVATE_FIELDS -> {
                declarations = "@Introspected(accessKind={Introspected.AccessKind.FIELD,Introspected.AccessKind.METHOD},visibility=Introspected.Visibility.ANY) public static class Bean { "
                    + (permission ? "@ReflectiveAccess " : "") + notNull + " private String value; public String getValue(){return \"getter\";} }";
                setup = "Bean bean=new Bean();bean.value=text(seed);";
                operations = "return validator.validate(bean, selected);";
            }
            case EXECUTABLES -> {
                declarations = "@Introspected public static class Bean { public Bean(){} public Bean(" + notNull + " String value){} "
                    + notNull + " public String method(" + notNull + " String value){return value;} }";
                setup = "Bean bean=new Bean();String value=text(seed);";
                operations = "if(operation%3==0){return validator.forExecutables().validateParameters(bean,Bean.class.getMethod(\"method\",String.class),new Object[]{value},selected);}"
                    + "if(operation%3==1){return validator.forExecutables().validateReturnValue(bean,Bean.class.getMethod(\"method\",String.class),value,selected);}"
                    + "return validator.forExecutables().validateConstructorParameters(Bean.class.getConstructor(String.class),new Object[]{value},selected);";
            }
            case CUSTOM -> {
                declarations = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @java.lang.annotation.Target(java.lang.annotation.ElementType.FIELD) "
                    + "@Constraint(validatedBy=CustomValidator.class) public @interface Custom { int threshold() default 1; String message() default \"custom\"; Class<?>[] groups() default {}; Class<? extends Payload>[] payload() default {}; } "
                    + "@Introspected public static class CustomValidator implements ConstraintValidator<Custom,Integer> { private int threshold; public void initialize(Custom c){threshold=c.threshold();} public boolean isValid(Integer value,ConstraintValidatorContext c){return value==null||value>=threshold;} } "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { @Custom(threshold=" + minimum + (group ? ",groups=G.class" : "") + ") public Integer value; "
                    + "@Custom(threshold=" + maximum + (group ? ",groups=G.class" : "") + ") public Integer other; }";
                setup = "Bean bean=new Bean();bean.value=(int)(seed%9);bean.other=bean.value;";
                operations = propertyOperations("Integer.class", "bean.value");
            }
            case INHERITANCE -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Base<T> { " + notNull + " public String inherited; public List<" + notNull + " T> items; } "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean extends Base<String> { " + size + " public String value; }";
                setup = "Bean bean=new Bean();bean.inherited=text(seed);bean.value=text(seed+1);bean.items=new ArrayList<>();for(int i=0;i<" + width + ";i++){bean.items.add(text(seed+i));}";
                operations = propertyOperations("String.class", "bean.value");
            }
            case SEQUENCES -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { @NotNull(message=\"parent\",groups=Parent.class) public String value; "
                    + "@Size(min=" + minimum + ",max=" + maximum + ",message=\"child\",groups=G.class) public String other; }";
                setup = "selected=new Class<?>[]{Sequence.class};Bean bean=new Bean();bean.value=text(seed);bean.other=text(seed+1);";
                operations = "return validator.validate(bean,selected);";
            }
            case RECURSIVE_BOUNDS -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Base<T extends Base<T>> { " + notNull + " public String value; @Valid public T next; } "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean extends Base<Bean> {}";
                setup = "Bean bean=new Bean();bean.value=text(seed);" + ((reductions & 4) != 0 ? "" : "bean.next=bean;");
                operations = "return validator.validate(bean,selected);";
            }
            case INVALID_DECLARATIONS -> {
                declarations = "@Introspected public interface Contract { void method(@NotNull String value); } @Introspected public static class Bean implements Contract { public void method(@Size(min=1) String value){} }";
                setup = "Bean bean=new Bean();";
                operations = "return validator.forExecutables().validateParameters(bean,Bean.class.getMethod(\"method\",String.class),new Object[]{text(seed)},selected);";
            }
            case EXTRACTORS -> {
                declarations = "@Introspected public static class Box<T> { public T item; public Box(T item){this.item=item;} } "
                    + "@Introspected public static class Extractor implements jakarta.validation.valueextraction.ValueExtractor<Box<@jakarta.validation.valueextraction.ExtractedValue ?>> { public void extractValues(Box<?> box,ValueReceiver receiver){receiver.value(\"<box>\",box.item);} } "
                    + "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { public Box<" + notNull + " String> value; }";
                setup = "Bean bean=new Bean();bean.value=new Box<>(text(seed));";
                operations = "try(var owned=Validation.byDefaultProvider().configure().ignoreXmlConfiguration().addValueExtractor(new Extractor()).buildValidatorFactory()){return owned.getValidator().validate(bean,selected);}";
            }
            case XML -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { @NotNull public String value; public List<@NotNull String> items; }";
                setup = "Bean bean=new Bean();bean.value=text(seed);bean.items=new ArrayList<>();for(int i=0;i<" + width + ";i++){bean.items.add(text(seed+i));}";
                String mapping = "<constraint-mappings xmlns='https://jakarta.ee/xml/ns/validation/mapping' version='3.1'><bean class='fuzz." + name
                    + "$Bean'><field name='value'><constraint annotation='jakarta.validation.constraints.Size'><message>xml-size</message><element name='min'><value>" + minimum
                    + "</value></element><element name='max'><value>" + maximum + "</value></element></constraint></field><field name='items'><container-element-type><constraint annotation='jakarta.validation.constraints.NotNull'><message>xml-null</message></constraint></container-element-type></field></bean></constraint-mappings>";
                operations = "try(var owned=Validation.byDefaultProvider().configure().ignoreXmlConfiguration().addMapping(new java.io.ByteArrayInputStream(\"" + mapping
                    + "\".getBytes(java.nio.charset.StandardCharsets.UTF_8))).buildValidatorFactory()){return owned.getValidator().validate(bean,selected);}";
            }
            case BOOTSTRAP -> {
                declarations = "@Introspected(accessKind=Introspected.AccessKind.FIELD) public static class Bean { " + notNull + " " + size + " public String value; }";
                setup = "Bean bean=new Bean();bean.value=text(seed);";
                operations = "try(var owned=Validation.byDefaultProvider().configure().ignoreXmlConfiguration().buildValidatorFactory()){var configured=owned.usingContext().messageInterpolator(new MessageInterpolator(){public String interpolate(String template,Context context){return \"fixed:\"+template;} public String interpolate(String template,Context context,Locale locale){return interpolate(template,context);}}).getValidator();return configured.validate(bean,selected);}";
            }
            default -> throw new AssertionError(kind);
        }
        return "package fuzz; import io.micronaut.core.annotation.*;import jakarta.validation.*;import jakarta.validation.constraints.*;import java.util.*; "
            + "public class " + name + " { public interface Parent {} public interface G extends Parent {} @GroupSequence({Parent.class,G.class}) public interface Sequence {} "
            + declarations + " public static Collection<? extends ConstraintViolation<?>> run(Validator validator,int operation,long seed) throws Exception {"
            + "Class<?>[] selected=" + (group ? "new Class<?>[]{G.class}" : "new Class<?>[0]") + ";" + setup + operations + "}"
            + "private static String text(long seed){String[] values={null,\"\",\"a\",\"abcdef\",\"é𝄞\",\"\\000\",\"a\\n\"};return values[(int)Math.floorMod(seed,values.length)];}}";
    }

    private static String propertyOperations(String type, String value) {
        return "if(operation%3==1){return validator.validateProperty(bean,\"value\",selected);}"
            + "if(operation%3==2){return validator.validateValue(Bean.class,\"value\"," + value + ",selected);}return validator.validate(bean,selected);";
    }
}
