package io.micronaut.validation.validator;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.GroupSequence;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.ConvertGroup;
import jakarta.validation.groups.Default;

import java.util.Optional;

/**
 * Beans validated the same way the reference implementation validates them.
 */
public final class ReferenceParity {

    private ReferenceParity() {
    }

    public interface A {
    }

    public interface B {
    }

    @GroupSequence({A.class, B.class})
    public interface Seq {
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class ChildB {
        @NotNull(groups = B.class)
        public String d;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class ChildA {
        @NotNull(groups = A.class)
        public String d;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class FailsInSecond {
        @Valid
        public ChildB child = new ChildB();
        @NotNull(groups = B.class)
        public String p;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class FailsInFirst {
        @Valid
        public ChildB child = new ChildB();
        @NotNull(groups = A.class)
        public String p;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class ChildFailsInFirst {
        @Valid
        public ChildA child = new ChildA();
        @NotNull(groups = B.class)
        public String p;
    }

    public interface Other {
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    @GroupSequence({Repeated.class, Other.class})
    public static class Repeated {
        @NotNull(groups = Other.class)
        public String x;
    }

    public interface CA {
    }

    public interface CB {
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class GrandChild {
        @NotNull(groups = CB.class)
        public String x;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class Mid {
        @Valid
        @ConvertGroup(from = Default.class, to = CA.class)
        @ConvertGroup(from = CA.class, to = CB.class)
        public GrandChild gc = new GrandChild();
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class Root {
        @Valid
        @ConvertGroup(from = Default.class, to = CA.class)
        @ConvertGroup(from = CA.class, to = CB.class)
        public Mid child = new Mid();
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class Address {
        @NotNull
        public String street;
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class Order {
        @Valid
        public Optional<Address> address = Optional.of(new Address());
    }

    @Introspected(accessKind = Introspected.AccessKind.FIELD)
    public static class Numbers {
        @Max(5)
        public Double d = 5.5;
        @Min(0)
        public Double nan = Double.NaN;
        @Max(5)
        public Double nan2 = Double.NaN;
        @Digits(integer = 3, fraction = 0)
        public Double price = 100.0;
        @DecimalMin("0")
        public Double inf = Double.POSITIVE_INFINITY;
        @DecimalMax("0")
        public Double ninf = Double.NEGATIVE_INFINITY;
        @DecimalMax("9007199254740992")
        public Long big = 9007199254740993L;
    }

    @Introspected
    public static class WrapperGetter {
        @NotNull
        public Boolean isActive() {
            return null;
        }
    }
}
