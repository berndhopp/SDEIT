package org.sdeit.example;

import com.google.common.collect.ImmutableMap;

import org.whispersystems.curve25519.Curve25519;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import lombok.extern.log4j.Log4j;

import static com.google.common.hash.Hashing.murmur3_128;
import static java.lang.Math.pow;
import static java.time.LocalDate.now;
import static java.util.Comparator.comparing;

@Log4j
public class Sdeit {

    private double sumOfTlotIncrease = 0;

    enum DiodeState{
        GREEN, GREEN_BLINK, YELLOW, YELLOW_BLINK,
        RED, RED_BLINK, WHITE, WHITE_BLINK
    }

    private static final int INCREASE_TLOT_FOR_NEARBY_UUIDS_PERIOD_MILIS = 3000;
    //distance 2 meters -> half the infection risk of distance 1 meter
    private static final double HALF_LIFE_DISTANCE_METERS = 1;
    //a three second hug with an infected person gives a 6.2% chance of being infected
    private static final double TLOT_IN_SCAN_PERIOD_WITH_NO_DISTANCE = 0.062;
    private static final long CLEAN_UP_UUIDS_LAST_UPDATE_OLDER_THAN_7_DAYS_PERIOD_MILIS = 1000 * 60 * 60 * 24;
    private static final long PULL_LATEST_DELTAS_FROM_SERVER_PERIOD_MILIS = 1000 * 60 * 60; // one hour

    private static byte[] PUBLIC_ED25519_KEY_OF_HEALTH_AUTHORITY = {110, 3, 27 /*...*/};
    private static final int HASH_SEED = 123456;

    private final Curve25519 cypher = Curve25519.getInstance(Curve25519.BEST);

    private LocalDateTime lastDeltaUpdate = LocalDateTime.now();

    private double infectionRiskTestThreshold;
    private double dailyTlotIncreaseAllowance;
    private final Map<UUID, LocalDate> lastUpdates = new HashMap<>();
    private final Map<UUID, Double> uuidToTLOTMap = new HashMap<>();
    private final Map<UUID, Double> knownInfectionRisks = new HashMap<>();

    Sdeit() {
        new Timer().schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        increaseTlotForNearbyUUIDs();
                    }
                },
                0,
                INCREASE_TLOT_FOR_NEARBY_UUIDS_PERIOD_MILIS
        );

        new Timer().schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        cleanUpUUIDsLastUpdateOlderThanSevenDays();
                    }
                },
                0,
                CLEAN_UP_UUIDS_LAST_UPDATE_OLDER_THAN_7_DAYS_PERIOD_MILIS
        );

        new Timer().schedule(
                new TimerTask() {
                    @Override
                    public void run() {
                        pullLatestDeltasFromServer();
                    }
                },
                0,
                PULL_LATEST_DELTAS_FROM_SERVER_PERIOD_MILIS
        );
    }

    private void pullLatestDeltasFromServer() {
        final List<InfectionDataDelta> newDeltas = pullDeltas(lastDeltaUpdate);

        final boolean verifiedSignature = newDeltas.stream().allMatch(
            newDelta -> {
                final byte[] hash = getDeltaHash(newDelta);
                return cypher.verifySignature(PUBLIC_ED25519_KEY_OF_HEALTH_AUTHORITY, hash, newDelta.getSignature());
            }
        );

        if(!verifiedSignature){
            //TODO log
            return;
        }

        lastDeltaUpdate = newDeltas
                .stream()
                .map(InfectionDataDelta::getTimeStamp)
                .max(LocalDateTime::compareTo)
                .orElse(lastDeltaUpdate);

        //should be sorted in the first place, but to be sure
        newDeltas.sort(comparing(InfectionDataDelta::getTimeStamp));

        newDeltas.forEach(
            newDelta -> {
                dailyTlotIncreaseAllowance = newDelta.getDailyTlotIncreaseAllowance();

                newDelta.getInfectionRiskUpdates().forEach(
                    (uuid, infectionRisk) -> {
                        if (infectionRisk == 0D){
                            //healed out seven days ago,
                            //nobody can currently have an undetected
                            // infection from that carrier
                            knownInfectionRisks.remove(uuid);
                        } else {
                            knownInfectionRisks.put(uuid, infectionRisk);
                        }
                    }
                );
            }
        );
    }

    @SuppressWarnings("UnstableApiUsage")
    private byte[] getDeltaHash(InfectionDataDelta newDelta) {
        return murmur3_128(HASH_SEED)
                .newHasher()
                .putLong(newDelta.getTimeStamp().toEpochSecond(ZoneOffset.UTC))
                .putDouble(newDelta.getDailyTlotIncreaseAllowance())
                .putObject(
                    newDelta.getInfectionRiskUpdates(),
                    (riskInfectionMap, primitiveSink) -> {
                        riskInfectionMap.forEach((uuid, infectionRisk) -> {
                            primitiveSink
                                .putLong(uuid.getLeastSignificantBits())
                                .putLong(uuid.getMostSignificantBits())
                                .putDouble(infectionRisk);
                            }
                        );
                    })
                .hash()
                .asBytes();
    }

    private List<InfectionDataDelta> pullDeltas(LocalDateTime olderThan) {
        return new ArrayList<>(
                //sample data goes here
        );
    }


    private void cleanUpUUIDsLastUpdateOlderThanSevenDays() {
        //after seven days without contact, we clean up the tlot
        final Iterator<Map.Entry<UUID, LocalDate>> iterator = lastUpdates.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, LocalDate> entry = iterator.next();
            UUID uuid = entry.getKey();
            LocalDate localDate = entry.getValue();

            if (Period.between(localDate, now()).getDays() > 7) {
                iterator.remove();
                uuidToTLOTMap.remove(uuid);
            }
        }
    }

    private void increaseTlotForNearbyUUIDs() {
        updateTlotFromScan();

        double myInfectionRisk = 0;

        for (Map.Entry<UUID, Double> entry : uuidToTLOTMap.entrySet()) {
            final UUID uuid = entry.getKey();
            final double tlot = entry.getValue();

            double infectionRiskForOtherCarrier = knownInfectionRisks.getOrDefault(uuid, 0D);
            double infectionRiskForMe = infectionRiskForOtherCarrier * tlot;

            double myNonInfectionLikelyHood = 1 - myInfectionRisk;

            myInfectionRisk += myNonInfectionLikelyHood * infectionRiskForMe;
        }

        if(myInfectionRisk >= infectionRiskTestThreshold ) {
            //get a test now
            setDiodeTo(DiodeState.RED_BLINK);
        }
    }

    private void setDiodeTo(DiodeState diodeState) {
        //implement in hardware

        //there should be some more logic here,
        //e.g. when state = RED_BLINK it should
        //stay like that until a negative corona test for that carrier
        //comes in with the InfectionDataDelta ( risk = 0 for my own UUID)
    }

    private void updateTlotFromScan() {

        Map<UUID, Float> surroundingUUIDS = scanSurroundingUUIDS();

        for (Map.Entry<UUID, Float> entry : surroundingUUIDS.entrySet()) {
            UUID uuid = entry.getKey();
            float distance = entry.getValue();

            //There is some math in here that is not explained, it's
            //relatively simple statistics

            double tlotIncrease = TLOT_IN_SCAN_PERIOD_WITH_NO_DISTANCE * pow(2, -(distance / HALF_LIFE_DISTANCE_METERS));

            final double existingTlot = uuidToTLOTMap.getOrDefault(uuid, 0d);

            //the theoretical likelihood of non-transmission
            final double tlnt = 1 - existingTlot;

            double newTlot = existingTlot + (tlnt * tlotIncrease);

            double tlotDiff = newTlot - existingTlot;

            sumOfTlotIncrease += tlotDiff;

            uuidToTLOTMap.put(uuid, newTlot);
            lastUpdates.put(uuid, now());
        }

        if(sumOfTlotIncrease > dailyTlotIncreaseAllowance) {
            setDiodeTo(DiodeState.RED);
        } else if(sumOfTlotIncrease > (dailyTlotIncreaseAllowance / 2)) {
            //blinking green or yellow indicates that you are close to somebody,
            //your allowance is being eaten up
            setDiodeTo(surroundingUUIDS.isEmpty() ? DiodeState.YELLOW : DiodeState.YELLOW_BLINK);
        } else {
            setDiodeTo(surroundingUUIDS.isEmpty() ? DiodeState.GREEN : DiodeState.GREEN_BLINK);
        }
    }

    private Map<UUID, Float> scanSurroundingUUIDS() {
        return ImmutableMap.of(
                //sample entries go here
        );
    }
}
