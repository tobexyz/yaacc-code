package de.yaacc.util;

public class Exceptions {

    public static Throwable unwrap(Throwable throwable) {
        if (throwable == null) {
            throw new IllegalArgumentException("Cannot unwrap null throwable");
        }
        while (throwable.getCause() != null) {
            throwable = throwable.getCause();
        }
        return throwable;
    }
}
