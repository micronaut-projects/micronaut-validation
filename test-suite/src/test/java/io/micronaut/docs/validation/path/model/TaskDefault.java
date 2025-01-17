package io.micronaut.docs.validation.path.model;

import io.micronaut.core.convert.format.MapFormat;
import io.micronaut.core.naming.conventions.StringConvention;

import java.util.Map;

public class TaskDefault {
    private final String type;

    private final boolean forced;

    @MapFormat(transformation = MapFormat.MapTransformation.NESTED, keyFormat = StringConvention.CAMEL_CASE)
    private final Map<String, Object> values;

    public TaskDefault(String type, boolean forced, Map<String, Object> values) {
        this.type = type;
        this.forced = forced;
        this.values = values;
    }

    private static boolean $default$forced() {
        return false;
    }

    public static TaskDefaultBuilder builder() {
        return new TaskDefaultBuilder();
    }

    public String getType() {
        return this.type;
    }

    public boolean isForced() {
        return this.forced;
    }

    public Map<String, Object> getValues() {
        return this.values;
    }

    public TaskDefaultBuilder toBuilder() {
        return new TaskDefaultBuilder().type(this.type).forced(this.forced).values(this.values);
    }

    public static class TaskDefaultBuilder {
        private String type;
        private boolean forced$value;
        private boolean forced$set;
        private Map<String, Object> values;

        TaskDefaultBuilder() {
        }

        public TaskDefaultBuilder type(String type) {
            this.type = type;
            return this;
        }

        public TaskDefaultBuilder forced(boolean forced) {
            this.forced$value = forced;
            this.forced$set = true;
            return this;
        }

        public TaskDefaultBuilder values(Map<String, Object> values) {
            this.values = values;
            return this;
        }

        public TaskDefault build() {
            boolean forced$value = this.forced$value;
            if (!this.forced$set) {
                forced$value = TaskDefault.$default$forced();
            }
            return new TaskDefault(this.type, forced$value, this.values);
        }

        public String toString() {
            return "TaskDefault.TaskDefaultBuilder(type=" + this.type + ", forced$value=" + this.forced$value + ", values=" + this.values + ")";
        }
    }
}

