package com.chaosledger.ledger.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_CHAOS_TESTS", matches = "true")
public class ReplayManualTest extends ManualChaosTestBase {

    @Test
    @DisplayName("Recorded chaos sequence can be saved, reloaded, and replayed")
    void chaosSequence_canBeRecordedAndReplayed() {
        UUID account = client.openAccount(UUID.randomUUID(), "INR");
        client.deposit(account, new BigDecimal("4000.00"), UUID.randomUUID());
        int leaderIdx = client.findLeader();
        for (int i = 0; i < 3; i++) {
            try {
                client.waitForBalance(i, account,
                        new BigDecimal("4000.00"), Duration.ofSeconds(15));
            } catch (Exception ignored) {}
        }

        // Record a small sequence: partition a follower, heal it
        int followerIdx = (leaderIdx + 1) % 3;
        int followerNodeId = nodeIdFromIdx(followerIdx);

        chaosEngine.clearEventLog();
        chaosEngine.partitionNode(followerNodeId);
        sleep(1000);
        chaosEngine.healPartition(followerNodeId);
        sleep(1000);

        List<ChaosEngine.ChaosEvent> recorded = chaosEngine.getEventLog();
        assertThat(recorded).hasSizeGreaterThanOrEqualTo(2);

        Path logFile = Path.of("target", "chaos-replay", "sample-run.json");
        ChaosReplay.save(recorded, logFile);

        // Reload from disk
        List<ChaosEngine.ChaosEvent> reloaded = ChaosReplay.load(logFile);
        assertThat(reloaded).hasSize(recorded.size());
        assertThat(reloaded.get(0).action()).isEqualTo(recorded.get(0).action());

        // Heal fully, then replay the reloaded sequence
        chaosEngine.healAll();
        chaosEngine.clearEventLog();
        ChaosReplay.replay(chaosEngine, reloaded, 2000);

        // Cluster should be healthy after replay
        chaosEngine.healAll();
        sleep(3000);
        client.waitForLeaderElection(Duration.ofSeconds(20));

        System.out.println("[Replay] Recorded → saved → reloaded → replayed successfully. "
                + "Log: " + logFile.toAbsolutePath());
    }
}
