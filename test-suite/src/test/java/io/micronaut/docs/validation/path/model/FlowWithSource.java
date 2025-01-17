package io.micronaut.docs.validation.path.model;

import io.micronaut.core.annotation.Introspected;
import org.slf4j.Logger;

@Introspected
public class FlowWithSource extends Flow {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(FlowWithSource.class);
    String source;

    public FlowWithSource(String source) {
        this.source = source;
    }

    public FlowWithSource() {
    }

    public static FlowWithSourceBuilder<?, ?> builder() {
        return new FlowWithSourceBuilderImpl();
    }

    public Flow toFlow() {
        return Flow.builder()
            .id(this.id)
            .namespace(this.namespace)
            .revision(this.revision)
            .description(this.description)
            .labels(this.labels)
            .variables(this.variables)
            .tasks(this.tasks)
            .errors(this.errors)
            .listeners(this.listeners)
            .triggers(this.triggers)
            .taskDefaults(this.taskDefaults)
            .disabled(this.disabled)
            .deleted(this.deleted)
            .build();
    }

    private static String cleanupSource(String source) {
        return source.replaceFirst("(?m)^revision: \\d+\n?", "");
    }


    public static FlowWithSource of(Flow flow, String source) {
        return FlowWithSource.builder()
            .id(flow.id)
            .namespace(flow.namespace)
            .revision(flow.revision)
            .description(flow.description)
            .labels(flow.labels)
            .variables(flow.variables)
            .tasks(flow.tasks)
            .errors(flow.errors)
            .listeners(flow.listeners)
            .triggers(flow.triggers)
            .taskDefaults(flow.taskDefaults)
            .disabled(flow.disabled)
            .deleted(flow.deleted)
            .source(source)
            .build();
    }

    public String getSource() {
        return this.source;
    }

    public String toString() {
        return "FlowWithSource(source=" + this.getSource() + ")";
    }

    public FlowWithSourceBuilder<?, ?> toBuilder() {
        return new FlowWithSourceBuilderImpl().$fillValuesFrom(this);
    }

    public static abstract class FlowWithSourceBuilder<C extends FlowWithSource, B extends FlowWithSourceBuilder<C, B>> extends FlowBuilder<C, B> {
        protected String source;

        private static void $fillValuesFromInstanceIntoBuilder(FlowWithSource instance, FlowWithSourceBuilder<?, ?> b) {
            b.source(instance.source);
        }

        public B source(String source) {
            this.source = source;
            return self();
        }

        protected B $fillValuesFrom(C instance) {
            super.$fillValuesFrom(instance);
            FlowWithSourceBuilder.$fillValuesFromInstanceIntoBuilder(instance, this);
            return self();
        }

        protected abstract B self();

        public abstract C build();

        public String toString() {
            return "FlowWithSource.FlowWithSourceBuilder(super=" + super.toString() + ", source=" + this.source + ")";
        }
    }

    private static final class FlowWithSourceBuilderImpl extends FlowWithSourceBuilder<FlowWithSource, FlowWithSourceBuilderImpl> {
        private FlowWithSourceBuilderImpl() {
        }

        protected FlowWithSourceBuilderImpl self() {
            return this;
        }

        public FlowWithSource build() {
            return new FlowWithSource(source);
        }
    }
}
