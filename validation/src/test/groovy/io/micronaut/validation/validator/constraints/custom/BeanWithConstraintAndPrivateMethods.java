package io.micronaut.validation.validator.constraints.custom;

import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Singleton
@Introspected
@CustomMessageConstraint
class BeanWithConstraintAndPrivateMethods {

    @NotBlank
    public String publicCombine(@NotNull String a, String b) {
        return privateCombine(a, b);
    }

    public String publicCombine2(String a, String b) {
        return privateCombine(a, b);
    }

    private String privateCombine(String a, String b) {
        return a + b;
    }

}
