package org.example.handy.common.dto;

import org.example.handy.common.HandyError;

public record ConnectionStatusResponse(ConnectionResult result, HandyError error)
{
}
