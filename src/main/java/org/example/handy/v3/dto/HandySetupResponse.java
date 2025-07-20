package org.example.handy.v3.dto;

import org.example.handy.common.HandyError;

// Common response structure with error for v2 and v3
public record HandySetupResponse(
        HandyError error,
        HandySetupResult result
)
{
}
