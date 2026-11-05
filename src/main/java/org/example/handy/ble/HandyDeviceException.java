package org.example.handy.ble;

import dev.handy.proto.HandyRpc;
import lombok.Getter;

/**
 * A device-reported error returned in a response (as opposed to a timeout or a transport failure).
 */
@Getter
public class HandyDeviceException extends RuntimeException
{
    private final int code;

    public HandyDeviceException(HandyRpc.Error error)
    {
        super("Device error: code=" + error.getCode() + " msg=" + error.getMessage());
        this.code = error.getCode();
    }
}
