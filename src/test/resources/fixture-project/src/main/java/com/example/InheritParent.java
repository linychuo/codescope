package com.example;

public class InheritParent {
    public void inheritDoPublic() {
        inheritDoPrivate();
    }
    private void inheritDoPrivate() {
        System.out.println("InheritParent.inheritDoPrivate");
    }
}
