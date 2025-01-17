package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class FlowWithException extends FlowWithSource {
    String exception;

    public FlowWithException(String exception) {
        this.exception = exception;
    }

    public FlowWithException() {
    }

    public static FlowWithExceptionBuilder<?, ?> builder() {
        return new FlowWithExceptionBuilderImpl();
    }

    public String getException() {
        return this.exception;
    }

    public String toString() {
        return "FlowWithException(exception=" + this.getException() + ")";
    }

    public FlowWithExceptionBuilder<?, ?> toBuilder() {
        return new FlowWithExceptionBuilderImpl().$fillValuesFrom(this);
    }

    public static abstract class FlowWithExceptionBuilder<C extends FlowWithException, B extends FlowWithExceptionBuilder<C, B>> extends FlowWithSourceBuilder<C, B> {
        protected String exception;

        private static void $fillValuesFromInstanceIntoBuilder(FlowWithException instance, FlowWithExceptionBuilder<?, ?> b) {
            b.exception(instance.exception);
        }

        public B exception(String exception) {
            this.exception = exception;
            return self();
        }

        protected B $fillValuesFrom(C instance) {
            super.$fillValuesFrom(instance);
            FlowWithExceptionBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "FlowWithException.FlowWithExceptionBuilder(super=" + super.toString() + ", exception=" + this.exception + ")";
        }
    }

    private static final class FlowWithExceptionBuilderImpl extends FlowWithExceptionBuilder<FlowWithException, FlowWithExceptionBuilderImpl> {
        private FlowWithExceptionBuilderImpl() {
        }

        protected FlowWithExceptionBuilderImpl self() {
            return this;
        }

        public FlowWithException build() {
            return new FlowWithException(exception);
        }
    }
}
