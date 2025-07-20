package org.example.handy.v3.dto;

import java.util.List;

public record HspAddRequest(List<HspPoint> points, boolean flush) {
}
