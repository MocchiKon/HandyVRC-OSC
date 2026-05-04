package org.example.handy.common.dto;

import org.example.handy.common.HandyError;

// Common response structure with error for v2 and v3
public record HandySetupResponse(
        HandyError error,
        HandySetupResult result
)
{
}
