package com.example;

public class IfaceRepositoryImpl implements IfaceRepository {
    private final IfaceDao dao;
    public IfaceRepositoryImpl(IfaceDao dao) { this.dao = dao; }
    @Override
    public String findById(long id) {
        return dao.findById(id);
    }
}
