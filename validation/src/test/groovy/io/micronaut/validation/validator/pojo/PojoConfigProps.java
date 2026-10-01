package io.micronaut.validation.validator.pojo;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.validation.Pojo;

import jakarta.validation.Valid;
import java.util.List;

@ConfigurationProperties("test.valid")
public class PojoConfigProps {

    @io.micronaut.core.annotation.ReflectiveAccess
    private List<@Valid Pojo> pojos;

    public List<@Valid Pojo> getPojos() {
        return pojos;
    }

    public void setPojos(List<@Valid Pojo> pojos) {
        this.pojos = pojos;
    }

}
