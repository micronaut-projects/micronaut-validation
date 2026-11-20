package io.micronaut.validation.execution;

import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.executable.ExecutableType;
import jakarta.validation.executable.ValidateOnExecution;

/**
 * Beans declaring which of their executables are validated.
 */
public final class ExecutionTypes {

    private ExecutionTypes() {
    }

    @Singleton
    public static class MethodDeclared {
        @ValidateOnExecution(type = ExecutableType.NONE)
        public String skipped(@NotBlank String name) {
            return name;
        }

        public String validated(@NotBlank String name) {
            return name;
        }
    }

    @Singleton
    @ValidateOnExecution(type = ExecutableType.GETTER_METHODS)
    public static class GettersOnly {
        @NotNull
        public String getName() {
            return null;
        }

        public String save(@NotBlank String name) {
            return name;
        }

        @ValidateOnExecution(type = ExecutableType.NON_GETTER_METHODS)
        public String update(@NotBlank String name) {
            return name;
        }
    }
}
