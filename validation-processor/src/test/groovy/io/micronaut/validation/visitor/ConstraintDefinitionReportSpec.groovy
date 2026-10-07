package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.annotation.processing.test.JavaParser

/**
 * An invalid constraint definition is reported where it is compiled: as a warning, which leaves the failure to
 * validation as the specification times it, or as a compilation error when the processor option asks for it.
 */
class ConstraintDefinitionReportSpec extends AbstractTypeElementSpec {

    private static final String SOURCE = '''
package test;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Introspected
class Test {
    @Invalid
    public String value;
}

@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@interface Invalid {
    String message() default "invalid";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    String validName() default "";
}
'''

    private boolean strict

    def cleanup() {
        // the Java processor copies every micronaut option into a system property, which a Groovy compilation
        // later in the same JVM reads as its own option
        System.clearProperty(ValidationVisitor.STRICT_DEFINITIONS_OPTION)
    }

    @Override
    protected JavaParser newJavaParser() {
        boolean failing = strict
        return new JavaParser() {
            @Override
            protected Set<String> getCompilerOptions() {
                Set<String> options = new LinkedHashSet<>(super.getCompilerOptions())
                if (failing) {
                    options.add("-Amicronaut.validation.strictConstraintDefinitions=true")
                }
                return options
            }
        }
    }

    void "an invalid constraint definition compiles and is left to fail at validation by default"() {
        when:
        def introspection = buildBeanIntrospection('test.Test', SOURCE)

        then:
        introspection != null
    }

    void "an invalid constraint definition fails the compilation when the option asks for it"() {
        given:
        strict = true

        when:
        buildBeanIntrospection('test.Test', SOURCE)

        then:
        def e = thrown(RuntimeException)
        e.message.contains("Constraint member names must not start with 'valid'")
    }
}
