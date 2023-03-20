package io.micronaut.validation.validator

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.context.annotation.Prototype
import io.micronaut.context.annotation.Value
import io.micronaut.context.exceptions.BeanInstantiationException
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.reflect.ClassUtils
import io.micronaut.validation.Validated
import io.micronaut.validation.validator.resolver.CompositeTraversableResolver
import jakarta.inject.Singleton
import jakarta.validation.Path
import jakarta.validation.Valid
import jakarta.validation.ValidatorFactory
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import jakarta.validation.metadata.BeanDescriptor
import org.apache.groovy.util.Maps
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class ValidatorSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run(["a.number": 40])
    @Shared
    Validator validator = applicationContext.getBean(Validator)

    void "test validator config"() {
        given:
        ValidatorConfiguration config = applicationContext.getBean(ValidatorConfiguration)
        ValidatorFactory factory = applicationContext.getBean(ValidatorFactory)

        expect:
        config.traversableResolver instanceof CompositeTraversableResolver
        factory instanceof DefaultValidatorFactory
    }

    void "test simple bean validation"() {
        given:
        Book b = new Book(title: "", pages: 50)
        def violations = validator.validate(b).sort { it.propertyPath.iterator().next().name }

        expect:
        violations.size() == 4
        violations[0].invalidValue == []
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof Size
        violations[0].constraintDescriptor.annotation.min() == 1
        violations[0].constraintDescriptor.annotation.max() == 10

        violations[1].invalidValue == 50
        violations[1].propertyPath.iterator().next().name == 'pages'
        violations[1].rootBean == b
        violations[1].rootBeanClass == Book
        violations[1].messageTemplate == '{jakarta.validation.constraints.Min.message}'
        violations[1].constraintDescriptor != null
        violations[1].constraintDescriptor.annotation instanceof Min
        violations[1].constraintDescriptor.annotation.value() == 100l

        violations[2].invalidValue == null
        violations[2].propertyPath.iterator().next().name == 'primaryAuthor'
        violations[2].constraintDescriptor != null
        violations[2].constraintDescriptor.annotation instanceof NotNull

        violations[3].invalidValue == ''
        violations[3].propertyPath.iterator().next().name == 'title'
        violations[3].constraintDescriptor != null
        violations[3].constraintDescriptor.annotation instanceof NotBlank

    }

    void "test array size"() {
        given:
        ObjectArray arrayTest = new ObjectArray(strings: [] as String[])
        def violations = validator.validate(arrayTest)

        expect:
        violations.size() == 1
        violations[0].invalidValue == []
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof Size
        violations[0].constraintDescriptor.annotation.min() == 1
        violations[0].constraintDescriptor.annotation.max() == 2

        when:
        arrayTest = new ObjectArray(strings: ["a", "b", "c"] as String[])
        violations = validator.validate(arrayTest)

        then:
        violations.size() == 1
        violations[0].invalidValue == ["a", "b", "c"]
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof Size
        violations[0].constraintDescriptor.annotation.min() == 1
        violations[0].constraintDescriptor.annotation.max() == 2

        when:
        arrayTest = new ObjectArray(numbers: [] as Long[])
        violations = validator.validate(arrayTest)

        then:
        violations.size() == 1
        violations[0].invalidValue == []
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof Size
        violations[0].constraintDescriptor.annotation.min() == 1
        violations[0].constraintDescriptor.annotation.max() == 2

        when:
        arrayTest = new ObjectArray(numbers: [1L, 2L, 3L] as long[])
        violations = validator.validate(arrayTest)

        then:
        violations.size() == 1
        violations[0].invalidValue == [1L, 2L, 3L]
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof Size
        violations[0].constraintDescriptor.annotation.min() == 1
        violations[0].constraintDescriptor.annotation.max() == 2
    }

    void "test validate bean property doesn't cascade"() {
        given:
        Book b = new Book(primaryAuthor: new Author(name: "", age: 200));
        def violations = validator.validateProperty(b, "primaryAuthor")

        expect:
        violations.size() == 0
    }

    void "test validate bean property"() {
        given:
        Book b = new Book(title: "", pages: 50)
        def violations = validator.validateProperty(b, "title").sort { it.propertyPath.iterator().next().name }

        expect:
        violations.size() == 1
        violations[0].invalidValue == ''
        violations[0].propertyPath.iterator().next().name == 'title'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof NotBlank

    }

    void "test validate value"() {
        given:
        def violations = validator.validateValue(Book, "title", "").sort { it.propertyPath.iterator().next().name }

        expect:
        violations.size() == 1
        violations[0].invalidValue == ''
        violations[0].propertyPath.iterator().next().name == 'title'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof NotBlank
    }

    void "test cascade to bean"() {
        given:
        Book b = new Book(
                title: "The Stand",
                pages: 1000,
                primaryAuthor: new Author(age: 150),
                authors: [
                        new Author(name: "Stephen King", age: 50)
                ]
        )
        def violations = validator.validate(b).sort { it.propertyPath.iterator().next().name }

        def v1 = violations.find { it.propertyPath.toString() == 'primaryAuthor.age'}
        def v2 = violations.find { it.propertyPath.toString() == 'primaryAuthor.name'}

        expect:
        violations.size() == 2

        v1.messageTemplate == '{jakarta.validation.constraints.Max.message}'
        v1.propertyPath.toString() == 'primaryAuthor.age'
        v1.invalidValue == 150
        v1.rootBean.is(b)
        v1.leafBean instanceof Author
        v1.constraintDescriptor != null
        v1.constraintDescriptor.annotation instanceof Max
        v1.constraintDescriptor.annotation.value() == 100l

        v2.messageTemplate == '{jakarta.validation.constraints.NotBlank.message}'
        v2.propertyPath.toString() == 'primaryAuthor.name'
        v2.constraintDescriptor != null
        v2.constraintDescriptor.annotation instanceof NotBlank
    }

    void "test cascade to bean - handle cycle"() {
        given:
        Book b = new Book(
                title: "The Stand",
                pages: 1000,
                primaryAuthor: new Author(age: 150),
                authors: [new Author(name: "Stephen King", age: 50)]
        )
        b.primaryAuthor.favouriteBook = b // create cycle
        def violations = validator.validate(b).sort { it.propertyPath.iterator().next().name }

        def v1 = violations.find { it.propertyPath.toString() == 'primaryAuthor.age'}
        def v2 = violations.find { it.propertyPath.toString() == 'primaryAuthor.name'}

        expect:
        violations.size() == 2
        v1.messageTemplate == '{jakarta.validation.constraints.Max.message}'
        v1.propertyPath.toString() == 'primaryAuthor.age'
        v1.invalidValue == 150
        v1.rootBean.is(b)
        v1.leafBean instanceof Author
        v1.constraintDescriptor != null
        v1.constraintDescriptor.annotation instanceof Max
        v1.constraintDescriptor.annotation.value() == 100l

        v2.messageTemplate == '{jakarta.validation.constraints.NotBlank.message}'
        v2.propertyPath.toString() == 'primaryAuthor.name'
        v2.constraintDescriptor != null
        v2.constraintDescriptor.annotation instanceof NotBlank
    }

    // PROPERTY GENERIC ARGUMENT (Java only)

    void "test validate property argument"() {
        given:
        def listOfNames = new ValidatorSpecClasses.ListOfNames(['X', 'Ann', 'TooLongName'])
        def violations = validator.validate(listOfNames)
                .sort { it.propertyPath.toString() }
        def v1 = violations.find { it.propertyPath.toString() == 'authors[0].age'}
        def v2 = violations.find { it.propertyPath.toString() == 'authors[0].name'}

        expect:
        violations.size() == 2
        violations[0].invalidValue == 'X'
        violations[0].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[0].propertyPath.toString() == 'names[0]<list element>'

        violations[1].invalidValue == 'TooLongName'
        violations[1].messageTemplate == '{jakarta.validation.constraints.Size.message}'
        violations[1].propertyPath.toString() == 'names[2]<list element>'
    }

    void "validate property argument - with iterable constraints"() {
        given:
        var e = new ValidatorSpecClasses.Email(["me@oracle.com", "", "me2@oracle.com"])
        var violations = validator.validate(e)
        violations = violations.sort{it->it.getPropertyPath().toString()}

        expect:
        violations.size() == 2
        violations[0].constraintDescriptor.annotation instanceof Size

        violations[1].constraintDescriptor.annotation instanceof NotBlank
        violations[1].getPropertyPath().size() == 2

        when:
        var path = violations[1].getPropertyPath().iterator()
        var path0 = path.next()
        var path1 = path.next()

        then:
        path0.getName() == "recoveryEmails"
        path0 instanceof Path.PropertyNode
        !path0.isInIterable()
        path0.getIndex() == null
        path0.getKey() == null

        path1.getName() == "<list element>"
        path1 instanceof Path.ContainerElementNode
        path1.isInIterable()
        path1.getIndex() == 1
        path1.getKey() == null
    }

    void "test validate property argument of map"() {
        given:
        def phoneBook = new ValidatorSpecClasses.PhoneBook([
                "Andriy": 2000,
                "Bob": -10,
                "": 911
        ])
        def violations = validator.validate(phoneBook)
                .sort{it.propertyPath.toString() }

        expect:
        violations.size() == 2
        violations[0].invalidValue == -10
        violations[0].propertyPath.toString() == 'numbers[Bob]<map value>'

        violations[1].invalidValue == ""
        violations[1].propertyPath.toString() == 'numbers[]<map key>'
    }

    void "test validate property argument cascade"() {
        given:
        def book = new ValidatorSpecClasses.Book("LOTR", [new ValidatorSpecClasses.Author("")])
        def violations = validator.validate(book);

        expect:
        violations.size() == 1
        violations[0].invalidValue == ""
        violations[0].propertyPath.toString() == "authors[0].name"
    }

    void "test validate property argument cascade with cycle"() {
        given:
        def book = new ValidatorSpecClasses.Book("LOTR", [new ValidatorSpecClasses.Author("")])
        book.authors[0].books.add(book)
        def violations = validator.validate(book)

        expect:
        violations.size() == 1
        violations[0].invalidValue == ""
        violations[0].propertyPath.toString() == "authors[0].name"
    }

    void "test validate property argument cascade of null container"() {
        given:
        def book = new ValidatorSpecClasses.Book("LOTR")
        def violations = validator.validate(book)

        expect:
        violations.size() == 0
    }

    void "test validate property argument cascade - nested"() {
        given:
        Set books = [
                new ValidatorSpecClasses.Book("Alice in wonderland", []),
                new ValidatorSpecClasses.Book("LOTR", [
                        new ValidatorSpecClasses.Author("Bob"),
                        new ValidatorSpecClasses.Author("")
                ]),
                new ValidatorSpecClasses.Book("?")
        ]
        def library = new ValidatorSpecClasses.Library(books)
        def violations = validator.validate(library)
                .sort{it.propertyPath.toString()}

        expect:
        violations.size() == 2
        violations[0].invalidValue == ""
        violations[0].propertyPath.toString() == "books[].authors[1].name"

        violations[1].invalidValue == "?"
        violations[1].propertyPath.toString() == "books[].name"
    }

    void "test validate property argument cascade - to non-introspected - inside map"(){
        when:
        def notIntrospected = new ValidatorSpecClasses.Person("")
        def apartmentBuilding = new ValidatorSpecClasses.ApartmentBuilding([1: notIntrospected])
        def violations = validator.validate(apartmentBuilding)

        then:
        violations[0].constraintDescriptor
        violations[0].message == "Cannot validate io.micronaut.validation.validator.ValidatorSpecClasses\$Person. No bean introspection present. " +
                "Please add @Introspected to the class and ensure Micronaut annotation processing is enabled"
        // violations[0].propertyPath.toString() ==
        //        'apartmentLivers[1]<V class io.micronaut.validation.validator.ValidatorSpecClasses.Person>'

        when:
        apartmentBuilding = new ValidatorSpecClasses.ApartmentBuilding([2: null])
        var violations2 = validator.validate(apartmentBuilding)

        then:
        violations2.size() == 0
    }

    void "test validate property argument cascade - enum"() {
        given:
        def inventory = new ValidatorSpecClasses.BooksInventory([
                ValidatorSpecClasses.BookCondition.USED,
                null,
                ValidatorSpecClasses.BookCondition.NEW
        ])
        def violations = validator.validate(inventory)

        expect:
        violations[0].message == "must not be null"
    }

    void "test validate property argument cascade - nested iterables"() {
        given:
        var matrix = new ValidatorSpecClasses.PositiveMatrix([[1, -1], [-3, 4]])
        var violations = validator.validate(matrix)
        violations = violations.sort{it -> it.getPropertyPath().toString(); }

        expect:
        violations.size() == 2
        violations[0].getPropertyPath().toString() == "matrix[0][1]<list element>"
        violations[1].getPropertyPath().toString() == "matrix[1][0]<list element>"
    }

    void "test validate argument annotations null"() {
        when:
        var book = new ValidatorSpecClasses.Book("Alice In Wonderland", [null])
        var violations = validator.validate(book)

        then:
        violations.size() == 0

        when:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var methDecl = ValidatorSpecClasses.Bank.getDeclaredMethod(
                "createAccount",
                ValidatorSpecClasses.Client,
                Map<Integer, ValidatorSpecClasses.Client>
        )
        // client, clientsWithAccess
        var params = [null, ["child": null]]
        var methodViolations = validator.forExecutables().validateParameters(b, methDecl, params as Object[])

        then:
        methodViolations.size() == 0
    }

    void "test validate Optional property type argument"() {
        def objectWithOptional = new ValidatorSpecClasses.ClassWithOptional(0)
        def violations = validator.validate(objectWithOptional)

        violations[0].constraintDescriptor.annotation instanceof Min
        violations[0].propertyPath.toString() == "number<T Integer>"
    }

    // EXECUTABLE ARGUMENTS VALIDATOR

    void "test executable validator"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)
        def constraintViolations = validator.forExecutables().validateParameters(
                bookService,
                BookService.getDeclaredMethod("saveBook", String, int.class),
                ["", 50] as Object[]
        ).toList().sort({ it.propertyPath.toString() })

        expect:
        constraintViolations.size() == 2
        constraintViolations[0].invalidValue == 50
        constraintViolations[0].propertyPath.toString() == 'saveBook.pages'
        constraintViolations[0].constraintDescriptor != null
        constraintViolations[0].constraintDescriptor.annotation instanceof  Min
        constraintViolations[0].constraintDescriptor.annotation.value() == 100l

        constraintViolations[1].constraintDescriptor != null
        constraintViolations[1].constraintDescriptor.annotation instanceof  NotBlank
    }

    void "test executable validator - cascade"() {
        given:
        def bookService = applicationContext.getBean(ValidatorSpecClasses.BookService)
        def book = new ValidatorSpecClasses.Book("X", [
                new ValidatorSpecClasses.Author("")
        ])
        def violations = validator.forExecutables().validateParameters(
                bookService,
                ValidatorSpecClasses.BookService.getDeclaredMethod("saveBook", ValidatorSpecClasses.Book),
                [book] as Object[]
        ).sort{it.propertyPath.toString()}

        expect:
        violations.size() == 2
        violations[0].invalidValue == ""
        violations[0].propertyPath.toString() == 'saveBook.book.authors[0].name'
        violations[0].constraintDescriptor != null
        violations[0].constraintDescriptor.annotation instanceof NotBlank

        violations[1].invalidValue == "X"
        violations[1].propertyPath.toString() == 'saveBook.book.name'
        violations[1].constraintDescriptor != null
        violations[1].constraintDescriptor.annotation instanceof Size
    }

    void "test validate method argument generic annotations"() {
        when:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var violations = validator.forExecutables().validateParameters(
                b,
                ValidatorSpecClasses.Bank.getDeclaredMethod("deposit", List<Integer>),
                [[0, 100, 500, -1]] as Object[]
        )
        violations = violations.sort({it -> it.getPropertyPath().toString() })

        then:
        violations.size() == 2
        violations[0].getPropertyPath().toString() == "deposit.banknotes[0]<list element>"
        violations[1].getPropertyPath().toString() == "deposit.banknotes[3]<list element>"
        violations[0].getPropertyPath().size() == 3

        when:
        def path0 = violations[0].getPropertyPath().iterator()
        def node0 = path0.next()
        def node1 = path0.next()
        def node2 = path0.next()

        then:
        node0 instanceof Path.MethodNode
        node0.getName() == "deposit"

        node1 instanceof Path.ParameterNode
        node1.getName() == "banknotes"

        node2 instanceof Path.ContainerElementNode
        node2.getName() == "<list element>"
    }

    void "test validate method argument generic annotations cascade"() {
        when:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var methDecl = ValidatorSpecClasses.Bank.getDeclaredMethod(
                "createAccount",
                ValidatorSpecClasses.Client,
                Map<Integer, ValidatorSpecClasses.Client>
        )
        // client, clientsWithAccess
        var params = [
                new ValidatorSpecClasses.Client(""),
                [
                        "child": new ValidatorSpecClasses.Client("Ann"),
                        "spouse": new ValidatorSpecClasses.Client("X"),
                        "": new ValidatorSpecClasses.Client("Jack")
                ]
        ]
        var violations = validator.forExecutables().validateParameters(b, methDecl, params as Object[])
        violations = violations.sort({it -> it.getPropertyPath().toString() })

        then:
        violations.size() == 3
        violations[0].getPropertyPath().toString() == "createAccount.client.name"
        violations[1].getPropertyPath().toString() == "createAccount.clientsWithAccess[]<map key>"
        violations[2].getPropertyPath().toString() == "createAccount.clientsWithAccess[spouse].name"

        when:
        var path0 = violations[0].getPropertyPath().iterator()
        var path1 = violations[1].getPropertyPath().iterator()
        var path2 = violations[2].getPropertyPath().iterator()

        then:
        violations[0].getPropertyPath().size() == 3
        violations[1].getPropertyPath().size() == 3
        violations[2].getPropertyPath().size() == 3

        path0.next() instanceof Path.MethodNode
        path0.next() instanceof Path.ParameterNode
        path0.next() instanceof Path.PropertyNode

        path1.next() instanceof Path.MethodNode
        path1.next() instanceof Path.ParameterNode
        path1.next() instanceof Path.ContainerElementNode

        path2.next() instanceof Path.MethodNode
        path2.next() instanceof Path.ParameterNode
        path2.next() instanceof Path.PropertyNode

    }

    void "test validate Optional method argument type argument"() {
        given:
        def singleton = applicationContext.getBean(ValidatorSpecClasses.ClassWithOptionalMethod)
        def violations = validator.forExecutables().validateParameters(
                singleton,
                ValidatorSpecClasses.ClassWithOptionalMethod.getDeclaredMethod("optionalMethod", Optional<Integer>),
                [Optional.of(Integer.valueOf(0))] as Object[]
        )

        expect:
        violations.size() == 1
        violations[0].invalidValue == 0
        violations[0].getPropertyPath().toString() == "optionalMethod.number"
        violations[0].constraintDescriptor.annotation instanceof Min

        violations[0].getPropertyPath().size() == 2
        def path = violations[0].getPropertyPath().iterator()
        path.next() instanceof Path.MethodNode
        def element = path.next()
        element instanceof Path.ParameterNode
    }

    // EXECUTABLE RETURN VALUE VALIDATOR

    void "test validate return value"() {
        given:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var violations = validator.forExecutables().validateReturnValue(
                b,
                ValidatorSpecClasses.Bank.getDeclaredMethod("getBalance"),
                -100
        )

        expect:
        violations.size() == 1
        violations[0].invalidValue == -100
        violations[0].getPropertyPath().toString() == "getBalance.<return value>"

        def iterator = violations[0].getPropertyPath().iterator()
        iterator.next() instanceof Path.MethodNode
        iterator.next() instanceof Path.ReturnValueNode
    }

    void "test validate return value type annotations - cascade"() {
        given:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var violations = validator.forExecutables().validateReturnValue(
                b,
                ValidatorSpecClasses.Bank.getDeclaredMethod("getClientsWithAccess", Integer),
                [
                        "spouse": new ValidatorSpecClasses.Client(""),
                        "son": new ValidatorSpecClasses.Client("Joe"),
                        "": new ValidatorSpecClasses.Client("Rick")
                ]
        )
        violations = violations.sort{it -> it.getPropertyPath().toString() }

        expect:
        violations[0].getPropertyPath().toString() == "getClientsWithAccess.<return value>[]<map key>"
        violations[1].getPropertyPath().toString() == "getClientsWithAccess.<return value>[spouse].name"

        when:
        var path0 = violations[0].getPropertyPath().iterator()
        var path1 = violations[1].getPropertyPath().iterator()

        then:
        violations[0].getPropertyPath().size() == 3
        path0.next() instanceof Path.MethodNode
        path0.next() instanceof Path.ReturnValueNode
        path0.next() instanceof Path.ContainerElementNode

        violations[1].getPropertyPath().size() == 3
        path1.next() instanceof Path.MethodNode
        path1.next() instanceof Path.ReturnValueNode
        path1.next() instanceof Path.PropertyNode
    }

    void "test validate return type annotations cascade - nested iterables"() {
        given:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var value = [
                11: [new ValidatorSpecClasses.Client("Andriy")],
                22: [],
                33: [
                        new ValidatorSpecClasses.Client("Jerry"),
                        new ValidatorSpecClasses.Client("")
                ],
                0: [
                        new ValidatorSpecClasses.Client("Too long name")
                ]
        ]
        var violations = validator.forExecutables().validateReturnValue(
                b,
                ValidatorSpecClasses.Bank.getDeclaredMethod("getAllAccounts"),
                value
        )
        violations = violations.sort{it -> it.getPropertyPath().toString() }

        expect:
        violations.size() == 2

        violations[0].propertyPath.toString() == "getAllAccounts.<return value>[0][0].name"
        violations[0].invalidValue == "Too long name"

        violations[1].propertyPath.toString() == "getAllAccounts.<return value>[33][1].name"
        violations[1].invalidValue == ""
    }

    void "test validate argument type parameters cascade - nested iterables"() {
        given:
        var b = applicationContext.getBean(ValidatorSpecClasses.Bank)
        var value = [
                0: [new ValidatorSpecClasses.Client("")]
        ]
        var violations = validator.forExecutables().validateParameters(
                b,
                ValidatorSpecClasses.Bank.getDeclaredMethod("setAllAccounts", Map<Integer, List<ValidatorSpecClasses.Client>>),
                [value] as Object[]
        )
        violations = violations.sort{it.getPropertyPath().toString()}

        expect:
        violations.size() == 1
    }

    // DESCRIPTOR

    void "test bean descriptor"() {
        given:
        BeanDescriptor beanDescriptor = validator.getConstraintsForClass(Book)

        def descriptors = beanDescriptor.getConstraintsForProperty("authors")
                .getConstraintDescriptors()

        expect:
        beanDescriptor.isBeanConstrained()
        beanDescriptor.getConstrainedProperties().size() == 4
        descriptors.size() == 1
        descriptors.first().annotation instanceof Size
        descriptors.first().annotation.min() == 1
        descriptors.first().annotation.max() == 10
    }

    void "test empty bean descriptor"() {
        given:
        BeanDescriptor beanDescriptor = validator.getConstraintsForClass(String)


        expect:
        !beanDescriptor.isBeanConstrained()
        beanDescriptor.getConstrainedProperties().size() == 0
    }

    void "test cascade to container of non-introspected class" () {
        when:
        def notIntrospected = new ValidatorSpecClasses.Bee("")
        def beeHive = new ValidatorSpecClasses.HiveOfBeeList([notIntrospected])
        def violations = validator.validate(beeHive)

        then:
        violations.size() == 1
        violations[0].constraintDescriptor
        violations[0].message == 'Cannot validate io.micronaut.validation.validator.ValidatorSpecClasses$Bee. No bean introspection present. ' +
                "Please add @Introspected to the class and ensure Micronaut annotation processing is enabled"

        when:
        beeHive = new ValidatorSpecClasses.HiveOfBeeList([null])
        violations = validator.validate(beeHive)

        then:
        violations.size() == 1
        violations[0].message == "must not be null"
    }

    void "test cascade to map of non-introspected value class" () {
        when:
        def notIntrospected = new ValidatorSpecClasses.Bee("")
        def beeHive = new ValidatorSpecClasses.HiveOfBeeMap(["blank" : notIntrospected])
        def violations = validator.validate(beeHive)

        then:
        violations.size() == 1
        violations[0].constraintDescriptor
        violations[0].message == 'Cannot validate io.micronaut.validation.validator.ValidatorSpecClasses$Bee. No bean introspection present. ' +
                "Please add @Introspected to the class and ensure Micronaut annotation processing is enabled"

        when:
        Map<String, ValidatorSpecClasses.Bee> map = [:]
        map.put("blank", null)
        beeHive = new ValidatorSpecClasses.HiveOfBeeMap(map)
        violations = validator.validate(beeHive)

        then:
        violations.size() == 0
    }

    void "test cascade to bean - enum"() {
        given:
        def b = new ValidatorSpecClasses.EnumList([null])

        def violations = validator.validate(b)

        expect:
        violations.size() == 1
        violations.first().message == "must not be null"
    }

    void "test helpful toString() message for constraintViolation"() {
        given:
        BookService bookService = applicationContext.getBean(BookService)
        def constraintViolations = validator.forExecutables().validateParameters(
                bookService,
                BookService.getDeclaredMethod("saveBook", String, int.class),
                ["", 50] as Object[]
        ).toList().sort({ it.propertyPath.toString() })

        expect:
        constraintViolations.size() == 2
        constraintViolations[0].toString() == 'DefaultConstraintViolation{rootBean=class io.micronaut.validation.validator.$BookService$Definition$Intercepted, invalidValue=50, path=saveBook.pages}'
        constraintViolations[1].toString() == 'DefaultConstraintViolation{rootBean=class io.micronaut.validation.validator.$BookService$Definition$Intercepted, invalidValue=, path=saveBook.title}'
    }

    void "test cascade to container"() {
        given:
        def salad = new ValidatorSpecClasses.Salad([
                new ValidatorSpecClasses.Ingredient("carrot"),
                new ValidatorSpecClasses.Ingredient("")
        ])
        def violations = validator.validate(salad)

        expect:
        violations.size() == 1
        violations[0].invalidValue == ""
    }

    void "test cascade to container with setter"() {
        given:
        def salad = new ValidatorSpecClasses.SaladWithSetter()
        salad.ingredients = [
                new ValidatorSpecClasses.Ingredient("carrot"),
                new ValidatorSpecClasses.Ingredient("")
        ]
        def violations = validator.validate(salad)

        expect:
        violations.size() == 1
        violations[0].invalidValue == ""
    }

    void "test @Introspected is required to validate the bean"() {
        when:
        applicationContext.getBean(A)
        then:
        BeanInstantiationException e = thrown()
        e.message.contains('''Cannot validate bean [io.micronaut.validation.validator.A]. No bean introspection present. Please add @Introspected.''')
        and:
        ClassUtils.forName('io.micronaut.validation.validator.$A$Definition', getClass().getClassLoader()).isPresent()
        ClassUtils.forName('io.micronaut.validation.validator.$A$Definition$Intercepted', getClass().getClassLoader()).isEmpty()
    }

    void "test @Introspected is required to validate the bean and it's intercepted if one of the methods requires validation"() {
        when:
        def beanB = applicationContext.getBean(B)
        then:
        BeanInstantiationException e = thrown()
        e.message.contains('''number - must be less than or equal to 20''')
        and:
        ClassUtils.forName('io.micronaut.validation.validator.$B$Definition', getClass().getClassLoader()).isPresent()
        ClassUtils.forName('io.micronaut.validation.validator.$B$Definition$Intercepted', getClass().getClassLoader()).isPresent()
    }

    void "test @Introspected is required to validate the bean and it's intercepted if one of the methods requires validation 2"() {
        when:
        def beanC = applicationContext.getBean(C)
        then:
        beanC.number == 40
        when:
        beanC.updateNumber(100)
        then:
        Exception e = thrown()
        e.message.contains('''updateNumber.number: must be less than or equal to 50''')
        beanC.number == 40

        and:
        ClassUtils.forName('io.micronaut.validation.validator.$C$Definition', getClass().getClassLoader()).isPresent()
        ClassUtils.forName('io.micronaut.validation.validator.$C$Definition$Intercepted', getClass().getClassLoader()).isPresent()
    }

    void "test validating implemented map"() {
        when:
        def service = applicationContext.getBean(MyService)
        service.myBeans()
        then:
        noExceptionThrown()
    }
}

class Bean extends AbstractMap<String, Integer> {

    @Override
    Set<Entry<String, Integer>> entrySet() {
        return Maps.of("A", 1, "B", 2, "C", 3).entrySet()
    }

}

@Validated
@Singleton
class MyService {

    List<Bean> myBeans() {
        return [new Bean(), new Bean()] as List
    }

}

@Singleton
class A {
    @Max(20l)
    @NotNull
    @Value('${a.number}')
    Integer number
}

@Introspected
@Singleton
class B {
    @Max(20l)
    @NotNull
    @Value('${a.number}')
    Integer number
    void updateNumber(@Max(20l)
                      @NotNull
                              Integer number) {
        this.number = number
    }
}

@Introspected
@Singleton
class C {
    @Max(50l)
    @NotNull
    @Value('${a.number}')
    Integer number
    void updateNumber(@Max(50l)
                      @NotNull
                              Integer number) {
        this.number = number
    }
}

@Introspected
class ObjectArray {
    @Size(min = 1, max = 2)
    String[] strings

    @Size(min = 1, max = 2)
    Long[] numbers
}

@Introspected
class Book {
    @NotBlank
    String title

    @Min(100l)
    int pages

    @Valid
    @NotNull
    Author primaryAuthor

    @Size(min = 1, max = 10)
    List<@Valid Author> authors = []
}

@Introspected
class Author {
    @NotBlank
    String name
    @Max(100l)
    Integer age

    @Valid
    Book favouriteBook
}

@Introspected
@Prototype
class ArrayTest {
    @Valid
    @Max(20l)
    @NotNull
    Integer[] integers

    @Valid
    ArrayTest child

    @Executable
    void saveChild(@Valid ArrayTest arrayTest) {

    }

    @Executable
    ArrayTest saveIntArray(@Valid
                           @Max(20l)
                           @NotNull int[] integers
    ) {
        new ArrayTest(integers: integers)
    }
}

@Singleton
class BookService {
    @Executable
    Book saveBook(@NotBlank String title, @Min(100l) int pages) {
        new Book(title: title, pages: pages)
    }
}

