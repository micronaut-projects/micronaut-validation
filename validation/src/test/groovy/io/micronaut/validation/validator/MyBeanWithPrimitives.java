package io.micronaut.validation.validator;

import io.micronaut.core.annotation.ReflectiveAccess;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Max;

@Introspected
public class MyBeanWithPrimitives {

    @Max(20)
    @ReflectiveAccess
    private int number;

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }
}
