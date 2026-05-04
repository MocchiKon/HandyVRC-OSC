package org.example.handy.common.dto;

import org.example.handy.common.HandyError;

public record HandyHspAddResponse(
        HandyError error,
        HspState result
)
{
}
