package org.sdeit.example;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.Value;

@Value
public class InfectionDataDelta {
    private final byte[] signature;
    private final Map<UUID, Double> infectionRiskUpdates;
    private final double dailyTlotIncreaseAllowance;
    private final LocalDateTime timeStamp;
}
