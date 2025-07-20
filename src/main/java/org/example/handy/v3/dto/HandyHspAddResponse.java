package org.example.handy.v3.dto;

import org.example.handy.common.HandyError;

public record HandyHspAddResponse(
        HandyError error,
        HspState result
)
{
}
