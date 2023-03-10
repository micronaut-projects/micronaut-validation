/*
 * Copyright 2017-2019 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.validation.validator.pojo

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import io.micronaut.core.annotation.Introspected
import io.micronaut.validation.Pojo
import io.micronaut.validation.PojoService
import io.micronaut.validation.validator.Validator
import io.micronaut.validation.validator.constraints.ConstraintValidator
import jakarta.inject.Singleton
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull

class PojoValidatorSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run(
        ['spec.name': 'customValidatorPOJO'],
        Environment.TEST
    )

    @Shared
    Validator validator = applicationContext.getBean(Validator)

    void "test custom constraint validator on a Pojo"() {
        given:
        Search search = new Search()

        when:
        def constraintViolations = validator.validate(search)

        then:
        constraintViolations.size() == 1
        constraintViolations.first().message == "Both name and lastName can't be null"
    }

    void "test custom constraint validator on Pojo as method argument"() {
        given:
        SearchService service = applicationContext.getBean(SearchService)
        var violations = validator.forExecutables().validateParameters(
                service,
                SearchService.getMethod("performSearch", Search),
                [new Search()] as Object[]
        )

        expect:
        violations.size() == 1
        violations[0].getPropertyPath().toString() == "performSearch.search"
        violations[0].message == "Both name and lastName can't be null"
    }

    void "test custom constraint validator on a nested Pojo"() {
        given:
        SearchAny search = new SearchAny(new Search())

        when:
        def constraintViolations = validator.validate(search)

        then:
        constraintViolations.size() == 1
        constraintViolations.first().message == "Both name and lastName can't be null"
    }

    void "test custom constraint validator on pojo method argument"() {
        when:
        applicationContext.getBean(SearchAny2).validate(new Search())

        then:
        def ex = thrown(ConstraintViolationException)
        ex.constraintViolations.size() == 1
    }

    void "test cascade to iterable with @Valid"() {
        when:
        applicationContext.getBean(SearchAny2).validateIterable([new Search()])

        then:
        def ex = thrown(ConstraintViolationException)
        ex.constraintViolations.size() == 1

        when:
        applicationContext.getBean(PojoService).validateIterable([new Pojo()])

        then:
        ex = thrown(ConstraintViolationException)
        ex.constraintViolations.size() == 1
    }

    void "test don't cascade to iterable without @Valid"() {
        expect:
        applicationContext.getBean(SearchAny2).validateIterableWithoutCascade([new Search()])
        applicationContext.getBean(PojoService).validateIterableWithoutCascade([new Pojo()], new Pojo(name:"John", email:"john@doe.com"))
    }

    void "test don't cascade to raw iterable without @Valid"() {
        given:
        def pojoService = applicationContext.getBean(PojoService)
        expect:
        applicationContext.getBean(SearchAny2).validateRawIterableWithoutCascade([new Search()])
        pojoService.validateRawIterableWithoutCascade([new Pojo()], new Pojo(name:"John", email:"john@doe.com"))
        pojoService.validateRawIterableWithoutCascadeCustomIterable({
            ['test'].iterator()
        } as PojoService.Session, new Pojo(name:"John", email:"john@doe.com"))
    }
}

@Singleton
class SearchService {
    @Executable
    String performSearch(@Valid Search search) {
        return "Not found"
    }
}

@Introspected
@NameAndLastNameValidator
class Search {
    String name
    String lastName
}

@Introspected
class SearchAny {
    @Valid
    List<Search> searches;
    SearchAny(Search... searches) {
        this.searches = searches;
    }
}

@Singleton
class SearchAny2 {
    void validate(@NotNull @Valid Search search) {

    }

    void validateIterable(@Valid Iterable<Search> search) {

    }

    boolean validateIterableWithoutCascade(Iterable<Search> search) {
        return true
    }

    boolean validateRawIterableWithoutCascade(Iterable search) {
        return true
    }
}

@Factory
@Requires(property = "spec.name", value = "customValidatorPOJO")
class NameAndLastNameValidatorFactory {

    @Singleton
    ConstraintValidator<NameAndLastNameValidator, Search> nameAndLastNameValidator() {
        return { value, annotationMetadata, context ->
            Objects.requireNonNull(annotationMetadata)
            Objects.requireNonNull(context)
            value != null && (value.getName() != null || value.getLastName() != null)
        }
    }

}

