package io.micronaut.validation.validator.constraints.unwrapped;

import jakarta.inject.Singleton;
import jakarta.validation.valueextraction.ExtractedValue;
import jakarta.validation.valueextraction.UnwrapByDefault;
import jakarta.validation.valueextraction.ValueExtractor;

@UnwrapByDefault
@Singleton
public class MyOptionalExtractor implements ValueExtractor<MyOptional<@ExtractedValue ?>> {

    @Override
    public void extractValues(MyOptional<?> originalValue, ValueReceiver receiver) {
        receiver.value("value", originalValue.value());
    }

}
