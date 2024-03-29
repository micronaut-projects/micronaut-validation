package io.micronaut.validation.validator.customwithdefaultconstraints

import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Executable
import io.micronaut.core.annotation.Introspected
import io.micronaut.core.annotation.Nullable
import io.micronaut.validation.validator.Validator
import jakarta.inject.Singleton
import spock.lang.AutoCleanup
import spock.lang.Issue
import spock.lang.Shared
import spock.lang.Specification

import jakarta.validation.ConstraintViolation
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.stream.Collectors


@Issue("https://github.com/micronaut-projects/micronaut-core/issues/6519")
class EmployeeValidationsSpec extends Specification {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run()


    void "test validations where both custom message constraint default validations fail"() {
        given:
        Validator validator = applicationContext.getBean(Validator.class)
        Employee emp = new Employee()
        emp.setName("")
        emp.setExperience(10)

        Set<String> messages = new HashSet<>()
        messages.add("must not be blank")
        messages.add("Experience Ineligible")
        messages.add("must not be null")

        when:
        final Set<ConstraintViolation<Employee>> constraintViolations = validator.validate(emp)

        then:
        Set<String> violationMessages = constraintViolations.stream()
                .map(ConstraintViolation::getMessage).collect(Collectors.toSet())
        constraintViolations.size() == 3
        violationMessages == messages
    }

    void "test whether exceptions thrown when both custom message constraint default validations fail"() {
        given:
        EmployeeService employeeService = applicationContext.getBean(EmployeeService.class)

        Designation designation = new Designation()
        designation.setName("")

        Employee emp = new Employee()
        emp.setName("")
        emp.setExperience(10)
        emp.setDesignation(designation)

        when:
        employeeService.startHoliday(emp)

        then:
        final ConstraintViolationException exception = thrown(ConstraintViolationException.class)

        String notBlankValidated = exception.getConstraintViolations().stream().filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp.name")).map(ConstraintViolation::getMessage).findFirst().get()
        String experienceValidated = exception.getConstraintViolations().stream().filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp")).map(ConstraintViolation::getMessage).findFirst().get()
        String notNullValidated = exception.getConstraintViolations().stream().filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp.designation.name")).map(ConstraintViolation::getMessage).findFirst().get()
        notBlankValidated == "must not be blank"
        experienceValidated == "Experience Ineligible"
        notNullValidated == "must not be empty"
    }

    void "test whether exceptions thrown when both custom message cascade constraint default validations fail"() {
        given:
        EmployeeService employeeService = applicationContext.getBean(EmployeeService.class)

        Employee alternateRepresentativeToBeContacted = new Employee()
        alternateRepresentativeToBeContacted.setName("")
        alternateRepresentativeToBeContacted.setExperience(20)
        alternateRepresentativeToBeContacted.setDesignation(null)

        Designation designation = new Designation()
        designation.setName("Senior Manager")

        Employee emp = new Employee()
        emp.setName("")
        emp.setExperience(20)
        emp.setDesignation(designation)
        emp.setAlternateRepresentative(alternateRepresentativeToBeContacted)

        when:
        employeeService.startHoliday(emp)

        then:
        final ConstraintViolationException exception = thrown(ConstraintViolationException.class)
        String notBlankValidated = exception.getConstraintViolations().stream()
                .filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp.name"))
                .map(ConstraintViolation::getMessage).findFirst().get()
        String experienceValidated = exception.getConstraintViolations().stream()
                .filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp"))
                .map(ConstraintViolation::getMessage).findFirst().get()
        String alternateRepresentativeToBeContactedDesignationValidated = exception.getConstraintViolations().stream()
                .filter(constraintViolation -> Objects.equals(constraintViolation.getPropertyPath().toString(), "startHoliday.emp.alternateRepresentative"))
                .map(ConstraintViolation::getMessage).findFirst().get()
        notBlankValidated == "must not be blank"
        experienceValidated == "Experience Ineligible"
        alternateRepresentativeToBeContactedDesignationValidated == "Experience Ineligible"
    }

}


@Singleton
class EmployeeService {
    @Executable
    String startHoliday(@Valid Employee emp) {
        return "Person " + emp.getName()
        +" is eligible for sabbatical holiday as the person is of " + emp.getExperience()
        +" years experienced. Please ensure his alternate representative contact "
        +emp.getAlternateRepresentative().getName()
        +" of designation " + emp.getAlternateRepresentative().getDesignation().getName() + " is aware of it"
    }
}

@Introspected
@EmployeeExperienceConstraint
class Employee {

    private String name

    private int experience

    private Employee alternateRepresentative

    @Valid
    @NotNull
    private Designation designation

    @EmployeeExperienceConstraint
    @Nullable
    Employee getAlternateRepresentative() {
        return alternateRepresentative
    }

    void setAlternateRepresentative(Employee alternateRepresentative) {
        this.alternateRepresentative = alternateRepresentative
    }

    int getExperience() {
        return experience
    }

    void setExperience(int experience) {
        this.experience = experience
    }

    @NotBlank
    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
    }

    Designation getDesignation() {
        return designation
    }

    void setDesignation(Designation designation) {
        this.designation = designation
    }
}

@Introspected
class Designation {

    @NotEmpty
    private String name

    String getName() {
        return name
    }

    void setName(String name) {
        this.name = name
    }

}
