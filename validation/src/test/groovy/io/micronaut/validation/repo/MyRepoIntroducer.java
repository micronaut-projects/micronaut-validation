package io.micronaut.validation.repo;

import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import org.jspecify.annotations.Nullable;
import jakarta.inject.Singleton;

@Singleton
public class MyRepoIntroducer implements MethodInterceptor<Object, Object> {

    @Override
    public int getOrder() {
        return 0;
    }

    @Nullable
    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        if (context.getExecutableMethod().getName().equals("findById")) {
            return new Book("");
        }
        return null;
    }
}
