package io.micronaut.docs.validation.path;

import io.micronaut.docs.validation.path.model.Dag;
import io.micronaut.docs.validation.path.model.Flow;
import io.micronaut.docs.validation.path.model.Log;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest(startApplication = false)
public class ValidationTest {
    @Inject
    Validator validator;

    @Test
    void testValidationOk() {
        Flow f = Flow.builder()
            .id("id")
            .namespace("namespace")
            .tasks(List.of(Log.builder().id("task").type(Log.class.getName()).message("").build()))
            .build();

        var violation = validator.validate(f).stream().findFirst().get();
        assertEquals("tasks[0].message", violation.getPropertyPath().toString());
        assertEquals("must not be blank", violation.getMessage());
    }

    @Test
    void testValidationKo() {
        Flow f = Flow.builder()
            .id("id")
            .namespace("namespace")
            .tasks(List.of(
                Dag.builder()
                    .id("dag")
                    .type(Dag.class.getName())
                    .dagTasks(List.of(
                        Dag.DagTask.builder()
                            .task(Log.builder()
                                .id("cycle")
                                .type(Log.class.getName())
                                .message("")
                                .build())
                            .dependsOn(List.of("cycle"))
                            .build()
                    ))
                .build())
            )
            .build();

        var violation = validator.validate(f).stream().findFirst().get();
        assertEquals("tasks[0].dagTasks", violation.getPropertyPath().toString());
        assertEquals("Cyclic dependency detected: cycle", violation.getMessage());
    }

    @Test
    void testRootError() {
        Flow f = Flow.builder()
            .id("id")
            .namespace("namespace")
            .tasks(List.of(
                Dag.builder()
                    .id("dag")
                    .type(Dag.class.getName())
                    .dagTasks(List.of(
                        Dag.DagTask.builder()
                            .task(Log.builder()
                                .id("cycle")
                                .type(Log.class.getName())
                                .message("")
                                .build())
                            .dependsOn(List.of("xyz"))
                            .build()
                    ))
                .build())
            )
            .build();

        var violation = validator.validate(f).stream().findFirst().get();
        assertEquals("tasks[0]", violation.getPropertyPath().toString());
        assertEquals("Not existing task id in dependency: xyz", violation.getMessage());
    }
}
