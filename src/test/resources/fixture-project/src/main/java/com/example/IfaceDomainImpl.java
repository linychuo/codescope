package com.example;

public class IfaceDomainImpl implements IfaceDomain {
    private final IfaceRepository repository;
    public IfaceDomainImpl(IfaceRepository repository) { this.repository = repository; }
    @Override
    public String findById(long id) {
        return repository.findById(id);
    }
}
