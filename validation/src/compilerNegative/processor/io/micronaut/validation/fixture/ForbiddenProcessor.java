package io.micronaut.validation.fixture;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.io.IOException;
import java.util.Set;

@SupportedAnnotationTypes("*")
@SupportedOptions("fixtureNullness")
public final class ForbiddenProcessor extends AbstractProcessor {
    private boolean generated;
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (generated || round.processingOver()) { return false; }
        generated = true;
        boolean nullness = Boolean.parseBoolean(processingEnv.getOptions().get("fixtureNullness"));
        try (var writer = processingEnv.getFiler().createSourceFile("io.micronaut.validation.fixture.ForbiddenGenerated").openWriter()) {
            writer.write("package io.micronaut.validation.fixture; @javax.annotation.processing.Generated(\"compiler-fixture\") class ForbiddenGenerated { "
                + (nullness ? "int invalid(){ String value=null; return value.length(); }"
                    : "Object invalid(Class<?> type) throws Exception { return type.getDeclaredConstructor().newInstance(); }") + "}");
        } catch (IOException e) { throw new IllegalStateException(e); }
        return false;
    }
}
