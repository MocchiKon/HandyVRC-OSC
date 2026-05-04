package org.example.handy.common.dto;

import java.util.List;

public record HspAddRequest(List<HspPoint> points, boolean flush) {
}
