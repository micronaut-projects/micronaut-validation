package io.micronaut.validation.validator.constraints.unwrapped;

import jakarta.inject.Singleton;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

/**
 * An extractor bean that does not mark the extracted type argument: the container has one, which it extracts.
 */
@UnwrapByDefault
@Singleton
public class MyBoxExtractor implements ValueExtractor<MyBox<?>> {

    @Override
    public void extractValues(MyBox<?> originalValue, ValueReceiver receiver) {
        receiver.value("value", originalValue.value());
    }

}
