package io.micronaut.validation;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import jakarta.validation.constraints.NotBlank;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 3, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ParameterBenchmark {
    private ApplicationContext ctx;
    private MyBean bean;

    public static void main(String[] args) throws RunnerException {
        ParameterBenchmark test = new ParameterBenchmark();
        test.init();
        try {
            test.string(new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous."));
        } finally {
            test.destroy();
        }

        Options opt = new OptionsBuilder()
            .include(".*" + ParameterBenchmark.class.getSimpleName() + ".*")
            //.addProfiler(AsyncProfiler.class, "libPath=/home/yawkat/bin/async-profiler-4.1-linux-x64/lib/libasyncProfiler.so;output=flamegraph")
            .build();

        new Runner(opt).run();
    }

    @Setup
    public void init() {
        ctx = ApplicationContext.run(Map.of("spec.name", "ParameterBenchmark"));
        bean = ctx.getBean(MyBean.class);
    }

    @TearDown
    public void destroy() {
        ctx.close();
    }

    @Benchmark
    public void string(Blackhole blackhole) {
        bean.string(blackhole, "foo");
    }

    @Singleton
    @Requires(property = "spec.name", value = "ParameterBenchmark")
    static class MyBean {
        void string(Blackhole blackhole, @NotBlank String string) {
            blackhole.consume(string);
        }
    }
}
