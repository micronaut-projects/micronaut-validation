package io.micronaut.validation;

import io.micronaut.annotation.processing.JavaAnnotationMetadataBuilder;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import jakarta.inject.Singleton;

@Singleton
class Cleaner implements ApplicationEventListener<ShutdownEvent> {

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        JavaAnnotationMetadataBuilder.clearCaches();
    }

}
