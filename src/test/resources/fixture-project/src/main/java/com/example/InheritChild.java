package com.example;

public class InheritChild extends InheritParent {
    @Override
    public void inheritDoPublic() {
        inheritDoPrivate();
    }
    private void inheritDoPrivate() {
        System.out.println("InheritChild.inheritDoPrivate");
    }
}
