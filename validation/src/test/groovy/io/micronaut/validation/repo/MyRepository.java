package io.micronaut.validation.repo;

public interface MyRepository<E, ID> {

    void save(E entity);

    E findById(ID id);

}
