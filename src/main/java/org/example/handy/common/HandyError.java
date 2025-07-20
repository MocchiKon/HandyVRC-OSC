package org.example.handy.common;

public record HandyError(
        int code,
        String name,
        String message,
        boolean connected
)
{
}
