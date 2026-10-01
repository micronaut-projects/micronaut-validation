/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.validation.validator;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.InternalConstraintValidatorFactory;

import jakarta.validation.ConstraintTarget;
import jakarta.validation.ValidationException;

import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Owns initialized instances and prevents release while validation is in progress. */
@Internal
final class ConstraintValidatorInstances implements AutoCloseable {
    private final InternalConstraintValidatorFactory factory;
    private final Map<InitializedValidatorKey, jakarta.validation.ConstraintValidator<?, ?>>
            initializedValidators = new HashMap<>();
    private final ReentrantReadWriteLock validatorLifecycle = new ReentrantReadWriteLock();
    private volatile boolean closed;

    ConstraintValidatorInstances(InternalConstraintValidatorFactory factory) {
        this.factory = factory;
    }

    void checkOpen() {
        if (closed) {
            throw new ValidationException("Validator factory is closed");
        }
    }

    void beginValidation() {
        validatorLifecycle.readLock().lock();
        if (closed) {
            validatorLifecycle.readLock().unlock();
            throw new ValidationException("Validator factory is closed");
        }
    }

    void endValidation() {
        validatorLifecycle.readLock().unlock();
    }

    void checkCloseAllowed() {
        if (validatorLifecycle.getReadHoldCount() != 0) {
            throw new ValidationException(
                    "Cannot close a validator factory from a running constraint validator");
        }
    }

    @Override
    public void close() {
        checkCloseAllowed();
        validatorLifecycle.writeLock().lock();
        try {
            releaseValidators();
        } finally {
            validatorLifecycle.writeLock().unlock();
        }
    }

    private void releaseValidators() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        Set<jakarta.validation.ConstraintValidator<?, ?>> released =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (jakarta.validation.ConstraintValidator<?, ?> instance :
                initializedValidators.values()) {
            if (released.add(instance)) {
                try {
                    factory.releaseInstance(instance);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        initializedValidators.clear();
        if (failure != null) {
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    synchronized <E> jakarta.validation.@Nullable ConstraintValidator<Annotation, E> get(
            Class<jakarta.validation.ConstraintValidator<Annotation, E>> type,
            Class<?> targetType,
            ConstraintTarget target,
            DefaultConstraintDescriptor<Annotation> constraint) {
        if (closed) {
            throw new ValidationException("Validator factory is closed");
        }
        InitializedValidatorKey key =
                new InitializedValidatorKey(
                        type, targetType, target, constraint.getAnnotationValue());
        var found = initializedValidators.get(key);
        if (found != null) {
            return (jakarta.validation.ConstraintValidator<Annotation, E>) found;
        }
        var instance = factory.getInstance(type, targetType, target);
        if (instance == null) {
            return null;
        }
        if (!(instance instanceof ConstraintValidator<?, ?>)
                && initializedValidators.values().stream()
                        .anyMatch(existing -> existing == instance)) {
            throw new ValidationException(
                    "ConstraintValidatorFactory reused an initialized instance for a different"
                            + " constraint: "
                            + type.getName());
        }
        try {
            if (!(instance instanceof ConstraintValidator<?, ?>)) {
                instance.initialize(constraint.getAnnotation());
            }
        } catch (RuntimeException e) {
            try {
                factory.releaseInstance(instance);
            } catch (RuntimeException release) {
                e.addSuppressed(release);
            }
            throw e instanceof ValidationException
                    ? e
                    : new ValidationException("Cannot initialize validator " + type.getName(), e);
        }
        initializedValidators.put(key, instance);
        return instance;
    }

    private record InitializedValidatorKey(
            Class<?> type,
            Class<?> targetType,
            ConstraintTarget target,
            AnnotationValue<?> annotation) { }
}
