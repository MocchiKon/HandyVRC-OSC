package org.example.handy.common.dto;

import java.util.List;

public record HspAddRequest(List<MovementPoint> points, boolean flush) {
}
