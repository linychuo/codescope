package com.example;

/** Caller for the special-form fixtures (enum / record). */
public class SpecialCaller {
    public void useEnumMethod() {
        Kind.ALPHA.label();
    }

    public void useRecordMethod() {
        new Point(3, 4).distanceFromOrigin();
    }
}
