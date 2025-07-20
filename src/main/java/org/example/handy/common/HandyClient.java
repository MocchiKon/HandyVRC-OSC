package org.example.handy.common;

public interface HandyClient
{
    HandyBaseResponseWithError changeMode(int mode);
    boolean checkConnectionStatus();
}
