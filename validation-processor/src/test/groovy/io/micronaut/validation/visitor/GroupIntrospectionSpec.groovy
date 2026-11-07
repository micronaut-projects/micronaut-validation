package io.micronaut.validation.visitor

import io.micronaut.annotation.processing.test.AbstractTypeElementSpec
import io.micronaut.inject.beans.visitor.IntrospectedTypeElementVisitor
import io.micronaut.inject.visitor.TypeElementVisitor

/**
 * A group a constraint names is introspected wherever the constraint is declared.
 */
class GroupIntrospectionSpec extends AbstractTypeElementSpec {

    @Override
    protected Collection<TypeElementVisitor> getLocalTypeElementVisitors() {
        return [new ValidationVisitor(), new IntrospectedValidationIndexesVisitor(), new IntrospectedTypeElementVisitor()]
    }

    void "the group of a #location constraint is introspected"() {
        when:
        def introspection = buildBeanIntrospection('test.Strict', """
package test;

import jakarta.validation.constraints.*;
import java.util.List;

class Bean {
    $declaration
}

interface Strict {
}
""")

        then:
        introspection != null

        where:
        location               | declaration
        'field'                | '@NotNull(groups = Strict.class) String name;'
        'container element'    | 'List<@NotNull(groups = Strict.class) String> names;'
        'getter'               | '@NotNull(groups = Strict.class) public String getName() { return null; }'
        'parameter'            | 'public void name(@NotNull(groups = Strict.class) String name) { }'
        'constructor argument' | 'Bean(@NotNull(groups = Strict.class) String name) { }'
        'repeated'             | '@Size.List({@Size(min = 1, groups = Strict.class), @Size(max = 3)}) String name;'
    }
}
