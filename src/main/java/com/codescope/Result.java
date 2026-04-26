package com.codescope;

/**
 * A Result type for handling errors without exceptions.
 * Either contains a value (success) or an error message (failure).
 */
public final class Result<T> {
    private final T value;
    private final String error;

    private Result(T value, String error) {
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> success(T value) {
        return new Result<>(value, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(null, message);
    }

    public boolean isSuccess() {
        return error == null;
    }

    public boolean isError() {
        return error != null;
    }

    public T getValue() {
        return value;
    }

    public String getError() {
        return error;
    }

    public T orElse(T defaultValue) {
        return isSuccess() ? value : defaultValue;
    }

    public T orElseThrow() {
        if (isError()) {
            throw new IllegalStateException(error);
        }
        return value;
    }

    @Override
    public String toString() {
        if (isSuccess()) {
            return "Result[success=" + value + "]";
        }
        return "Result[error=" + error + "]";
    }
}
