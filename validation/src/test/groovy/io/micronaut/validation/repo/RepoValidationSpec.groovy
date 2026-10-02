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
package io.micronaut.validation.repo

import io.micronaut.context.BeanContext
import io.micronaut.inject.BeanDefinition
import io.micronaut.test.extensions.spock.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import spock.lang.Specification

@MicronautTest
class RepoValidationSpec extends Specification {

    @Inject
    BookRepository bookRepository
    @Inject
    BeanContext beanContext

    void "test repo entity validation"() {
        given:
            Book book = new Book("")
        when:
            bookRepository.save(book)
        then:
            ConstraintViolationException e = thrown(ConstraintViolationException)
            e.message == "save.entity.name: must not be blank"
    }

    void "test repo id validation"() {
        when:
            bookRepository.findById(2L)
        then:
            ConstraintViolationException e = thrown(ConstraintViolationException)
            e.message == "findById.id: must be greater than or equal to 5"
    }

    void "test repo return type validation"() {
        when:
            bookRepository.findById(5L)
        then:
            ConstraintViolationException e = thrown(ConstraintViolationException)
            e.message == "findById.<return value>.name: must not be blank"
    }

    void "test repo validation annotations"() {
        when:
            BeanDefinition<BookRepository> definition = beanContext.getBeanDefinition(BookRepository)
        then:
            definition.getRequiredMethod("save", Book).getArguments()[0].getAnnotationMetadata().hasAnnotation(Valid)
    }
}
