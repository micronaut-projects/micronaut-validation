package io.micronaut.validation.tck.runtime;

import jakarta.validation.Configuration;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.spi.BootstrapState;
import jakarta.validation.spi.ConfigurationState;
import jakarta.validation.spi.ValidationProvider;
import org.hibernate.beanvalidation.tck.common.TCKValidatorConfiguration;

public class TckValidationProvider implements ValidationProvider<TCKValidatorConfiguration> {

    @Override
    public TCKValidatorConfiguration createSpecializedConfiguration(BootstrapState state) {
        throw new RuntimeException();
    }

    @Override
    public Configuration<?> createGenericConfiguration(BootstrapState state) {
        throw new RuntimeException();
    }

    @Override
    public ValidatorFactory buildValidatorFactory(ConfigurationState configurationState) {
        throw new RuntimeException();
    }

}
