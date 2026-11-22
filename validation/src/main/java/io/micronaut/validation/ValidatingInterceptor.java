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
package io.micronaut.validation;

import io.micronaut.aop.InterceptPhase;
import io.micronaut.aop.InterceptedMethod;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.ReturnType;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.validation.validator.ExecutableMethodValidator;
import io.micronaut.validation.validator.ReactiveValidator;
import io.micronaut.validation.validator.ReflectionSupport;
import io.micronaut.validation.validator.Validator;
import io.micronaut.validation.validator.ValidatorConfiguration;
import jakarta.inject.Singleton;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.executable.ExecutableValidator;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static io.micronaut.validation.ConstraintViolationExceptionUtil.createConstraintViolationException;

/**
 * A {@link MethodInterceptor} that validates method invocations.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Singleton
public class ValidatingInterceptor implements MethodInterceptor<Object, Object> {
    /** The position of the interceptor. See {@link io.micronaut.core.order.Ordered} */
    public static final int POSITION = InterceptPhase.VALIDATE.getPosition();

    private final ReflectionSupport reflectionSupport;
    private final @Nullable ExecutableValidator executableValidator;
    private final @Nullable ExecutableMethodValidator micronautValidator;
    private final ConversionService conversionService;
    private final boolean isPrependPropertyPath;
    /**
     * What intercepting a method reads of it, by method: its groups, the kind of its result and whether its return
     * value is validated. Resolved from the method, so it is the same for the life of the method. Keyed by identity:
     * methods equal by their signature, one inherited by beans validated in different groups, are different
     * methods of different beans.
     */
    private final Map<MethodKey, MethodPlan> methodPlans;

    /**
     * Creates ValidatingInterceptor from the validatorFactory.
     *
     * @param micronautValidator The micronaut validator use if no factory is available
     * @param validatorFactory   Factory returning initialized {@code Validator} instances
     * @param conversionService  The conversion service
     * @param validatorConfiguration validator configuration instance
     */
    public ValidatingInterceptor(@Nullable Validator micronautValidator,
                                 @Nullable ValidatorFactory validatorFactory,
                                 ConversionService conversionService,
                                 ValidatorConfiguration validatorConfiguration) {
        this.conversionService = conversionService;
        this.reflectionSupport = validatorConfiguration.getReflectionSupport();
        isPrependPropertyPath = validatorConfiguration.isPrependPropertyPath();
        this.methodPlans = new ConcurrentHashMap<>();

        if (validatorFactory != null) {
            jakarta.validation.Validator validator = validatorFactory.getValidator();
            if (validator instanceof Validator micronautFactoryValidator) {
                this.micronautValidator = micronautFactoryValidator.forExecutables();
                this.executableValidator = null;
            } else {
                this.micronautValidator = null;
                this.executableValidator = validator.forExecutables();
            }
        } else if (micronautValidator != null) {
            this.micronautValidator = micronautValidator.forExecutables();
            this.executableValidator = null;
        } else {
            this.micronautValidator = null;
            this.executableValidator = null;
        }
    }

    @Override
    public int getOrder() {
        return POSITION;
    }

    @Nullable
    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        MethodPlan plan = plan(context);
        if (executableValidator != null) {
            Method targetMethod = reflectionSupport.targetMethod(context.getExecutableMethod());
            if (context.getArguments().length != 0) {
                Set<ConstraintViolation<Object>> constraintViolations = executableValidator
                        .validateParameters(
                                context.getTarget(),
                                targetMethod,
                                context.getParameterValues(),
                                plan.groups()
                        );
                if (!constraintViolations.isEmpty()) {
                    throw createConstraintViolationException(isPrependPropertyPath, constraintViolations);
                }
            }
            return validateReturnExecutableValidator(executableValidator, context, targetMethod, plan.groups());
        } else if (micronautValidator != null) {
            ExecutableMethod<Object, Object> executableMethod = context.getExecutableMethod();
            if (executableMethod.getArguments().length != 0) {
                Set<ConstraintViolation<Object>> constraintViolations = micronautValidator.validateParameters(
                        context.getTarget(),
                        executableMethod,
                        context.getParameterValues(),
                        plan.groups());
                if (!constraintViolations.isEmpty()) {
                    throw createConstraintViolationException(isPrependPropertyPath, constraintViolations);
                }
            }
            if (micronautValidator instanceof ReactiveValidator reactiveValidator && plan.resultType() != InterceptedMethod.ResultType.SYNCHRONOUS) {
                InterceptedMethod interceptedMethod = InterceptedMethod.of(context, conversionService);
                try {
                    return switch (interceptedMethod.resultType()) {
                        case PUBLISHER -> {
                            var publisher = interceptedMethod.interceptResultAsPublisher();
                            // the constraints of the publisher itself apply to the publisher, before any value it emits
                            Set<ConstraintViolation<Object>> containerViolations = reactiveValidator.validateReturnedContainer(
                                context.getTarget(), executableMethod, publisher, plan.groups());
                            if (!containerViolations.isEmpty()) {
                                throw createConstraintViolationException(isPrependPropertyPath, containerViolations);
                            }
                            // each value is an element of the return value of the method invoked on the target
                            yield interceptedMethod.handleResult(reactiveValidator.validateReturnedPublisher(
                                context.getTarget(), executableMethod, (Publisher<Object>) publisher, plan.groups()));
                        }
                        case COMPLETION_STAGE -> {
                            if (executableMethod.isSuspend()) {
                                // a suspending method returns the value the coroutine produces, once it completes
                                if (!plan.returnValueValidated()) {
                                    yield context.proceed();
                                }
                                CompletionStage<Object> stage = (CompletionStage<Object>) interceptedMethod.interceptResultAsCompletionStage();
                                yield interceptedMethod.handleResult(stage.thenApply(value -> {
                                    Set<ConstraintViolation<Object>> violations = micronautValidator.validateReturnValue(
                                        context.getTarget(), executableMethod, value, plan.groups());
                                    if (!violations.isEmpty()) {
                                        throw createConstraintViolationException(isPrependPropertyPath, violations);
                                    }
                                    return value;
                                }));
                            }
                            // the stage as returned: its own constraints first, a null one among them
                            Object result = context.proceed();
                            Set<ConstraintViolation<Object>> containerViolations = reactiveValidator.validateReturnedContainer(
                                context.getTarget(), executableMethod, result, plan.groups());
                            if (!containerViolations.isEmpty()) {
                                throw createConstraintViolationException(isPrependPropertyPath, containerViolations);
                            }
                            if (!(result instanceof CompletionStage<?> stage)) {
                                // the method returned no stage, which is passed on as it is
                                yield result;
                            }
                            yield interceptedMethod.handleResult(reactiveValidator.validateReturnedCompletionStage(
                                context.getTarget(), executableMethod, (CompletionStage<Object>) stage, plan.groups()));
                        }
                        default -> interceptedMethod.unsupported();
                    };
                } catch (Exception e) {
                    return interceptedMethod.handleException(e);
                }
            } else if (plan.returnValueValidated()) {
                return validateReturnMicronautValidator(micronautValidator, context, executableMethod, plan.groups());
            } else {
                return context.proceed();
            }
        }
        return context.proceed();
    }

    /**
     * What intercepting a method reads of it, resolved once. Whether its return value can produce a violation at
     * all: one with no constraint and no {@link Valid} cannot. Validating it anyway resolves the group sequences,
     * an introspection lookup of the default group that misses, which is expensive on some runtimes. The
     * stereotypes are consulted, which errs towards validating.
     *
     * @param context The invocation
     * @return The plan of the method
     */
    private MethodPlan plan(MethodInvocationContext<Object, Object> context) {
        ExecutableMethod<Object, Object> executableMethod = context.getExecutableMethod();
        MethodKey key = new MethodKey(executableMethod);
        MethodPlan plan = methodPlans.get(key);
        if (plan == null) {
            ReturnType<Object> returnType = executableMethod.getReturnType();
            // the kind of the result is of the method, a suspending one included: a synchronous method is never
            // wrapped again
            InterceptedMethod.ResultType resultType = micronautValidator instanceof ReactiveValidator
                ? InterceptedMethod.of(context, conversionService).resultType()
                : InterceptedMethod.ResultType.SYNCHRONOUS;
            plan = new MethodPlan(context.classValues(Validated.class, "groups"), resultType,
                !returnType.isVoid() && requiresValidation(returnType.asArgument()));
            methodPlans.put(key, plan);
        }
        return plan;
    }

    private static boolean requiresValidation(Argument<?> argument) {
        AnnotationMetadata annotationMetadata = argument.getAnnotationMetadata();
        if (annotationMetadata.hasStereotype(Constraint.class) || annotationMetadata.hasStereotype(Valid.class)) {
            return true;
        }
        // a constraint can sit on a type argument, as in List<@NotBlank String>
        for (Argument<?> typeParameter : argument.getTypeParameters()) {
            if (requiresValidation(typeParameter)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable Object validateReturnMicronautValidator(ExecutableMethodValidator validator,
                                                              MethodInvocationContext<Object, Object> context,
                                                              ExecutableMethod<Object, Object> executableMethod,
                                                              Class<?>[] groups) {
        Object result = context.proceed();
        Set<ConstraintViolation<Object>> constraintViolations = validator.validateReturnValue(
                context.getTarget(),
                executableMethod,
                result,
                groups);
        if (!constraintViolations.isEmpty()) {
            throw createConstraintViolationException(isPrependPropertyPath, constraintViolations);
        }
        return result;
    }

    private @Nullable Object validateReturnExecutableValidator(ExecutableValidator validator,
                                                               MethodInvocationContext<Object, Object> context,
                                                               Method targetMethod,
                                                               Class<?>[] groups) {
        final Object result = context.proceed();
        Set<ConstraintViolation<Object>> constraintViolations = validator.validateReturnValue(
                context.getTarget(),
                targetMethod,
                result,
                groups
        );
        if (!constraintViolations.isEmpty()) {
            throw createConstraintViolationException(isPrependPropertyPath, constraintViolations);
        }
        return result;
    }

    /**
     * @param groups The groups the method is validated in
     * @param resultType The kind of its result
     * @param returnValueValidated Whether its return value can produce a violation at all
     */
    private record MethodPlan(Class<?>[] groups, InterceptedMethod.ResultType resultType, boolean returnValueValidated) {
    }

    /**
     * A method compared by identity.
     *
     * @param method The method
     */
    private record MethodKey(ExecutableMethod<?, ?> method) {
        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean equals(@Nullable Object o) {
            return o instanceof MethodKey other && other.method == method;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(method);
        }
    }
}
