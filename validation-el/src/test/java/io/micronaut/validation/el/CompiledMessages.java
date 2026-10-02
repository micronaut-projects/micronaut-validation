package io.micronaut.validation.el;

import io.micronaut.el.annotation.ELEnvironment;
import io.micronaut.el.annotation.ELExpression;
import io.micronaut.el.annotation.ELVariable;

// tag::compiled-messages[]
@ELEnvironment(variables = @ELVariable(name = "validatedValue", type = String.class))
@ELExpression("${validatedValue.toUpperCase()}")
public final class CompiledMessages {
    private CompiledMessages() { }
}
// end::compiled-messages[]
